package ai.starlake.gizmo

import scala.collection.concurrent.TrieMap
import scala.sys.process.{ProcessLogger, Process as SysProcess}
import scala.util.Random
import com.typesafe.scalalogging.LazyLogging

/** Represents a managed process */
case class ManagedProcess(
  name: String,
  port: Int,
  process: SysProcess,
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

  /** Find an available port */
  private def findAvailablePort: Int =
    var port = 0
    var found = false
    while !found do
      val candidate = minPort + random.nextInt(maxPort - minPort)
      if !usedPorts.contains(candidate) then
        port = candidate
        found = true
    port

  /** Start a new process */
  def startProcess(processName: String, arguments: Map[String, String] = Map.empty): Either[String, StartProcessResponse] =
    processes.get(processName) match
      case Some(_) =>
        Left(s"Process '$processName' is already running")
      case None =>
        // Check if we've reached the maximum number of processes
        if processes.size >= EnvVars.maxProcesses then
          Left(s"Maximum number of processes (${EnvVars.maxProcesses}) reached. Cannot start new process.")
        else
          // Validate required environment variables
          val requiredVars = Seq(
            "GIZMOSQL_USERNAME",
            "GIZMOSQL_PASSWORD",
            "SL_PROJECT_ID",
            "SL_DATA_PATH",
            "PG_USERNAME",
            "PG_PASSWORD",
            "PG_PORT",
            "PG_HOST"
          )
          val missingVars = requiredVars.filterNot(arguments.contains)

          if missingVars.nonEmpty then
            Left(s"Missing required environment variables: ${missingVars.mkString(", ")}")
          else
            val port = findAvailablePort

            // Build INIT_SQL_COMMANDS from template
            val initSqlTemplate = """CREATE OR REPLACE PERSISTENT SECRET pg_{{SL_PROJECT_ID}} (TYPE postgres, HOST '{{PG_HOST}}',PORT {{PG_PORT}}, DATABASE {{SL_PROJECT_ID}}, USER '{{PG_USERNAME}}',PASSWORD '{{PG_PASSWORD}}');CREATE OR REPLACE PERSISTENT SECRET {{SL_PROJECT_ID}} (TYPE ducklake, METADATA_PATH '',DATA_PATH '{{SL_DATA_PATH}}', METADATA_PARAMETERS MAP {'TYPE': 'postgres', 'SECRET': 'pg_{{SL_PROJECT_ID}}'});ATTACH IF NOT EXISTS 'ducklake:{{SL_PROJECT_ID}}' AS {{SL_PROJECT_ID}} (READ_ONLY); USE {{SL_PROJECT_ID}};"""

            val initSqlCommands = initSqlTemplate
              .replace("{{SL_PROJECT_ID}}", arguments("SL_PROJECT_ID"))
              .replace("{{PG_HOST}}", arguments("PG_HOST"))
              .replace("{{PG_PORT}}", arguments("PG_PORT"))
              .replace("{{PG_USERNAME}}", arguments("PG_USERNAME"))
              .replace("{{PG_PASSWORD}}", arguments("PG_PASSWORD"))
              .replace("{{SL_DATA_PATH}}", arguments("SL_DATA_PATH"))

            // Add INIT_SQL_COMMANDS to the arguments
            val allArguments = arguments + ("INIT_SQL_COMMANDS" -> initSqlCommands)

            // Log the arguments
            val argsString = allArguments.map { case (key, value) =>
              if key == "INIT_SQL_COMMANDS" then s"$key=<SQL_COMMANDS>"
              else if key.contains("PASSWORD") then s"$key=***"
              else s"$key=$value"
            }.mkString(", ")
            logger.info(s"Starting process '$processName' on port $port with env vars: $argsString")

            // Build the command - use default script
            val command = Seq(EnvVars.defaultScript)

            // Build environment variables from arguments map (including INIT_SQL_COMMANDS)
            val envVars = allArguments.toSeq

            val processBuilder = SysProcess(
              command,
              None, // working directory
              envVars* // environment variables as varargs
            )

            val processLogger = ProcessLogger(
              out => logger.info(s"[$processName] $out"),
              err => logger.error(s"[$processName] ERROR: $err")
            )
            val process = processBuilder.run(processLogger)

            // Try to get PID using reflection (Java 9+)
            val pid = try
              val pidMethod = process.getClass.getMethod("pid")
              Some(pidMethod.invoke(process).asInstanceOf[Long])
            catch
              case _: Exception => None

            val managedProcess = ManagedProcess(processName, port, process, pid, arguments)

            processes.put(processName, managedProcess)
            usedPorts.put(port, true)

            Right(StartProcessResponse(
              processName = processName,
              port = port,
              message = s"Process started successfully on port $port"
            ))

  /** Stop a running process */
  def stopProcess(processName: String): Either[String, StopProcessResponse] =
    processes.get(processName) match
      case None =>
        Left(s"Process '$processName' is not running")
      case Some(managedProcess) =>
        logger.info(s"Stopping process '$processName'")
        managedProcess.process.destroy()
        processes.remove(processName)
        usedPorts.remove(managedProcess.port)
        Right(StopProcessResponse(
          processName = processName,
          message = s"Process stopped successfully"
        ))

  /** Restart a process */
  def restartProcess(processName: String): Either[String, RestartProcessResponse] =
    // Get the original arguments before stopping
    val originalArguments = processes.get(processName).map(_.arguments).getOrElse(Map.empty)

    stopProcess(processName) match
      case Left(error) => Left(error)
      case Right(_) =>
        startProcess(processName, originalArguments) match
          case Left(error) => Left(error)
          case Right(startResponse) =>
            Right(RestartProcessResponse(
              processName = processName,
              port = startResponse.port,
              message = s"Process restarted successfully on port ${startResponse.port}"
            ))

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
      val lsofResult = SysProcess(Seq("lsof", "-ti", s":$port")).!!.trim
      if lsofResult.nonEmpty then
        val pids = lsofResult.split("\n").map(_.trim).filter(_.nonEmpty)
        pids.foreach { pid =>
          logger.info(s"Killing process with PID $pid on port $port")
          SysProcess(Seq("kill", "-9", pid)).!
        }
        true
      else
        false
    catch
      case _: Exception => false

  /** Stop all running processes and scan for any processes on managed ports */
  def stopAll: StopProcessResponse =
    var totalStopped = 0

    // First, stop all tracked processes
    val trackedCount = processes.size
    logger.info(s"Stopping all tracked processes ($trackedCount total)")
    processes.values.foreach { managedProcess =>
      logger.info(s"Stopping process '${managedProcess.name}'")
      managedProcess.process.destroy()
      totalStopped += 1
    }
    processes.clear()
    usedPorts.clear()

    // Then scan for any processes on ports in our range
    logger.info(s"Scanning for processes on ports $minPort-$maxPort...")
    for port <- minPort to maxPort do
      if killProcessOnPort(port) then
        logger.info(s"Found and killed process on port $port")
        totalStopped += 1

    StopProcessResponse(
      processName = "all",
      message = s"Stopped $totalStopped process(es)"
    )

