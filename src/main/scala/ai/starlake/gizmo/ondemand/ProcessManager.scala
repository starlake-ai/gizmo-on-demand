package ai.starlake.gizmo.ondemand

import com.typesafe.scalalogging.LazyLogging

import scala.collection.concurrent.TrieMap
import scala.jdk.CollectionConverters.*
import scala.util.Random

/** Represents a managed process */
case class ManagedProcess(
    name: String,
    port: Int,
    backendPort: Int,
    process: java.lang.Process,
    pid: Option[Long],
    arguments: Map[String, String]
)

/** Process manager that handles lifecycle of processes */
class ProcessManager extends LazyLogging:

  private val processes = TrieMap.empty[String, ManagedProcess]
  private val usedPorts = TrieMap.empty[Int, Boolean]
  private val random = new Random()
  private val minPort = EnvVars.minPort
  private val maxPort = EnvVars.maxPort

  /** Find and reserve an available port */
  private def claimAvailablePort(requestedPort: Option[Int]): Either[String, Int] =
    val maxRetries = 100
    var retries = 0
    var foundPort: Option[Int] = None
    requestedPort match {
        case Some(port) =>
          // The caller takes responsibility of the port he is requesting
          usedPorts.putIfAbsent(port, true)
          foundPort = Some(port)
        case None => // Proceed to find a random available port
          while foundPort.isEmpty && retries < maxRetries do
            val candidate = minPort + retries
            // Atomically check and set
            if usedPorts.putIfAbsent(candidate, true).isEmpty then
              foundPort = Some(candidate)
            retries = retries + 1

    }

    foundPort.toRight(
      s"Could not find an available port after $maxRetries attempts"
    )

  /** Find and reserve a pair of ports (proxy port, proxy port + 1000) */
  private def claimPairedPorts(requestedPort: Option[Int]): Either[String, (Int, Int)] =
    val maxRetries = 100
    var retries = 0
    var foundPair: Option[(Int, Int)] = None
    while foundPair.isEmpty && retries < maxRetries do
      // 1. Claim a primary port
      claimAvailablePort(requestedPort) match
        case Left(_)          => retries += 1 // Failed to get primary, retry
        case Right(proxyPort) =>
          val backendPort = proxyPort + 1000
          // 2. Try to claim the specific backend port
          if usedPorts.putIfAbsent(backendPort, true).isEmpty then
            foundPair = Some((proxyPort, backendPort))
          else
            // Backend port taken, release proxy port and retry
            usedPorts.remove(proxyPort)
            retries += 1

    foundPair.toRight(
      s"Could not find an available port pair after $maxRetries attempts"
    )

  /** Start a new process */
  def startProcess(
      processName: String,
      connectionName: String,
      port: Option[Int] = None,
      arguments: Map[String, String] = Map.empty
  ): Either[String, StartProcessResponse] =
    if processes.contains(processName) then
      Left(s"Process '$processName' is already running")
    else if processes.size >= EnvVars.maxProcesses then
      Left(
        s"Maximum number of processes (${EnvVars.maxProcesses}) reached. Cannot start new process."
      )
    else
      // Validate required environment variables
      val requiredVars = Seq(
        "GIZMOSQL_USERNAME",
        "GIZMOSQL_PASSWORD",
        "SL_DB_ID",
        "SL_DATA_PATH",
        "PG_USERNAME",
        "PG_PASSWORD",
        "PG_PORT", // Kept for SQL generation, though backend port is now dynamic?
        // Actually INIT_SQL_COMMANDS uses PG_PORT for connection string to Postgres.
        // The proxy backend port is for Flight SQL.
        "PG_HOST"
      )
      val missingVars = requiredVars.filterNot(arguments.contains)

      if missingVars.nonEmpty then
        Left(
          s"Missing required environment variables: ${missingVars.mkString(", ")}"
        )
      else
        // Reserve ports (proxy + 1000)
        claimPairedPorts(port) match
          case Left(error)                     => Left(error)
          case Right((proxyPort, backendPort)) =>
            try
              // Log the arguments
              val argsString = arguments
                .map { case (key, value) =>
                  if key.contains("PASSWORD") then s"$key=***"
                  else s"$key=$value"
                }
                .mkString(", ")
              logger.info(
                s"Starting Proxy Server '$processName' on port $proxyPort (backend: $backendPort) with env vars: $argsString"
              )

              // Construct command to start ProxyServer using the script
              val proxyScript = EnvVars.proxyScript
              val jobCommand = Seq(
                proxyScript
              ).asJava

              val processBuilder = new ProcessBuilder(jobCommand)
              
              val env = processBuilder.environment()

              // Pass original arguments to environment
              arguments.foreach { case (k, v) => env.put(k, v) }

              // Set Proxy Configuration via Env Vars for PureConfig
              // Matches application.conf expectations
              env.put("PROXY_PORT", proxyPort.toString)
              env.put("PROXY_HOST", "0.0.0.0")

              // Backend is local to the proxy process
              // Set Gizmo Server Port for the proxy to start and connect to
              env.put("GIZMO_SERVER_HOST", "127.0.0.1")
              env.put("GIZMO_SERVER_PORT", backendPort.toString)

              // Start the process
              val process = processBuilder.start()

              // Monitor process exit
              process.onExit().thenAccept { _ =>
                // Check if the process is still tracked (meaning it wasn't stopped intentionally)
                if processes.contains(processName) then
                  logger.warn(
                    s"Proxy Server '$processName' (PID: ${process.pid()}) exited unexpectedly with code ${process.exitValue()}"
                  )

                  // Ensure backend is killed if it's still alive (zombie protection)
                  if killProcessOnPort(backendPort) then
                    logger.warn(
                      s"Killed orphaned backend process on port $backendPort"
                    )

                  processes.remove(processName)
                  usedPorts.remove(proxyPort)
                  usedPorts.remove(backendPort)
              }

              // Handle logging in separate threads
              new Thread(() => {
                val scanner = new java.util.Scanner(process.getInputStream)
                while (scanner.hasNextLine) {
                  logger.info(s"[$processName] ${scanner.nextLine()}")
                }
              }).start()

              new Thread(() => {
                val scanner = new java.util.Scanner(process.getErrorStream)
                while (scanner.hasNextLine) {
                  logger.error(
                    s"[$processName] ERROR: ${scanner.nextLine()}"
                  )
                }
              }).start()

              // Get PID directly
              val pid = Some(process.pid())
              logger.info(s"PID: ${process.pid()}")

              val managedProcess =
                ManagedProcess(
                  processName,
                  proxyPort,
                  backendPort,
                  process,
                  pid,
                  arguments
                )

              processes.putIfAbsent(processName, managedProcess) match
                case Some(_) =>
                  // Race condition: process was started by someone else
                  process.destroy()
                  usedPorts.remove(proxyPort)
                  usedPorts.remove(backendPort)
                  Left(
                    s"Process '$processName' is already running (race condition detected)"
                  )
                case None =>
                  Right(
                    StartProcessResponse(
                      processName = processName,
                      port = proxyPort,
                      message =
                        s"Proxy Process started successfully on port $proxyPort"
                    )
                  )

            catch
              case e: Exception =>
                // Cleanup ports on failure
                usedPorts.remove(proxyPort)
                usedPorts.remove(backendPort)
                logger.error(s"Failed to start process '$processName'", e)
                Left(s"Failed to start process: ${e.getMessage}")

  /** Stop a running process */
  def stopProcess(processName: String): Either[String, StopProcessResponse] =
    processes.get(processName) match
      case None =>
        Left(s"Process '$processName' is not running")
      case Some(managedProcess) =>
        logger.info(s"Stopping process '$processName'")
        // Kill processes by port (more reliable than stored PID)
        killProcessOnPort(managedProcess.port)
        killProcessOnPort(managedProcess.backendPort)
        processes.remove(processName)
        usedPorts.remove(managedProcess.port)
        usedPorts.remove(managedProcess.backendPort)
        Right(
          StopProcessResponse(
            processName = processName,
            message = s"Process stopped successfully"
          )
        )


  /** List all running processes */
  def listProcesses: ListProcessesResponse =
    val processInfos = processes.values.map { mp =>
      ProcessInfo(
        processName = mp.name,
        port = mp.port,
        pid = mp.pid,
        status = if mp.process.isAlive() then "running" else "stopped"
      )
    }.toList
    ListProcessesResponse(processInfos)

  /** Find and kill process using a specific port */
  private def killProcessOnPort(port: Int): Boolean =
    try
      // Try to find process using lsof command (works on Unix-like systems)
      val lsofBuilder = new ProcessBuilder(Seq("lsof", "-ti", s":$port").asJava)
      val lsofProcess = lsofBuilder.start()
      val output = new String(lsofProcess.getInputStream.readAllBytes()).trim
      lsofProcess.waitFor()

      if output.nonEmpty then
        val pids = output.split("\n").map(_.trim).filter(_.nonEmpty)
        pids.foreach { pid =>
          logger.info(s"Killing process with PID $pid on port $port")
          new ProcessBuilder(Seq("kill", "-9", pid).asJava).start().waitFor()
        }
        true
      else false
    catch case _: Exception => false

  /** Stop all running processes and scan for any processes on managed ports */
  def stopAll: StopProcessResponse =
    var totalStopped = 0

    // First, stop all tracked processes
    val trackedCount = processes.size
    logger.info(s"Stopping all tracked processes ($trackedCount total)")
    processes.values.foreach { managedProcess =>
      logger.info(s"Stopping process '${managedProcess.name}'")
      // Kill processes by port
      killProcessOnPort(managedProcess.port)
      killProcessOnPort(managedProcess.backendPort)
      totalStopped += 1
    }
    processes.clear()
    usedPorts.clear()

    // Then scan for any processes on ports in our range
    logger.info(s"Scanning for processes on ports $minPort-$maxPort...")
    for port <- minPort to maxPort do
      if killProcessOnPort(port) then
        killProcessOnPort(port+1000)
        logger.info(s"Found and killed process on port $port")
        totalStopped += 1

    StopProcessResponse(
      processName = "all",
      message = s"Stopped $totalStopped process(es)"
    )
