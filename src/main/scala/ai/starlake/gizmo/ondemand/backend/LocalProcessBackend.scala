package ai.starlake.gizmo.ondemand.backend

import ai.starlake.gizmo.ondemand.EnvVars
import com.typesafe.scalalogging.LazyLogging

import scala.collection.concurrent.TrieMap
import scala.jdk.CollectionConverters.*

/** Backend that spawns proxy instances as local OS processes via ProcessBuilder */
class LocalProcessBackend extends ProcessBackend with LazyLogging:

  private val usedPorts = TrieMap.empty[Int, Boolean]
  private val minPort = EnvVars.minPort
  private val maxPort = EnvVars.maxPort

  /** Find and reserve an available port */
  def claimAvailablePort(requestedPort: Option[Int]): Either[String, Int] =
    val maxRetries = 100
    var retries = 0
    var foundPort: Option[Int] = None
    requestedPort match
      case Some(port) =>
        usedPorts.putIfAbsent(port, true)
        foundPort = Some(port)
      case None =>
        while foundPort.isEmpty && retries < maxRetries do
          val candidate = minPort + retries
          if usedPorts.putIfAbsent(candidate, true).isEmpty then foundPort = Some(candidate)
          retries = retries + 1

    foundPort.toRight(s"Could not find an available port after $maxRetries attempts")

  /** Find and reserve a pair of ports (proxy port, proxy port + 1000) */
  def claimPairedPorts(requestedPort: Option[Int]): Either[String, (Int, Int)] =
    val maxRetries = 100
    var retries = 0
    var foundPair: Option[(Int, Int)] = None
    while foundPair.isEmpty && retries < maxRetries do
      claimAvailablePort(requestedPort) match
        case Left(_) => retries += 1
        case Right(proxyPort) =>
          val backendPort = proxyPort + 1000
          if usedPorts.putIfAbsent(backendPort, true).isEmpty then
            foundPair = Some((proxyPort, backendPort))
          else
            usedPorts.remove(proxyPort)
            retries += 1

    foundPair.toRight(s"Could not find an available port pair after $maxRetries attempts")

  /** Release a pair of ports */
  def releasePorts(proxyPort: Int, backendPort: Int): Unit =
    usedPorts.remove(proxyPort)
    usedPorts.remove(backendPort)

  override def start(
      name: String,
      envVars: Map[String, String],
      proxyPort: Int,
      backendPort: Int,
      onExit: () => Unit
  ): Either[String, SpawnResult] =
    try
      val proxyScript = EnvVars.proxyScript
      val jobCommand = Seq(proxyScript).asJava

      val processBuilder = new ProcessBuilder(jobCommand)
      val env = processBuilder.environment()

      // Pass caller-supplied arguments
      envVars.foreach { case (k, v) => env.put(k, v) }

      // Set proxy/backend configuration
      env.put("PROXY_PORT", proxyPort.toString)
      env.put("PROXY_HOST", "0.0.0.0")
      env.put("GIZMO_SERVER_HOST", "127.0.0.1")
      env.put("GIZMO_SERVER_PORT", backendPort.toString)

      val process = processBuilder.start()

      // Monitor process exit
      process.onExit().thenAccept { _ =>
        onExit()
      }

      // Stream stdout/stderr
      new Thread(() => {
        val scanner = new java.util.Scanner(process.getInputStream)
        while scanner.hasNextLine do logger.info(s"[$name] ${scanner.nextLine()}")
      }).start()

      new Thread(() => {
        val scanner = new java.util.Scanner(process.getErrorStream)
        while scanner.hasNextLine do logger.error(s"[$name] ERROR: ${scanner.nextLine()}")
      }).start()

      logger.info(s"Local process started: PID=${process.pid()}")
      Right(SpawnResult(LocalProcessHandle(process), "127.0.0.1", proxyPort))
    catch
      case e: Exception =>
        logger.error(s"Failed to start local process '$name'", e)
        Left(s"Failed to start process: ${e.getMessage}")

  override def stop(handle: ProcessHandle): Either[String, Unit] =
    handle match
      case LocalProcessHandle(process) =>
        // Kill using the process handle directly
        process.destroyForcibly()
        Right(())
      case _ =>
        Left("LocalProcessBackend can only stop LocalProcessHandle instances")

  /** Find and kill process using a specific port */
  def killProcessOnPort(port: Int): Boolean =
    try
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

  override def isAlive(handle: ProcessHandle): Boolean =
    handle match
      case LocalProcessHandle(process) => process.isAlive
      case _                           => false

  override def stopAll(): Int =
    var totalStopped = 0
    logger.info(s"Scanning for processes on ports $minPort-$maxPort...")
    for port <- minPort to maxPort do
      if killProcessOnPort(port) then
        killProcessOnPort(port + 1000)
        logger.info(s"Found and killed process on port $port")
        totalStopped += 1
    usedPorts.clear()
    totalStopped

  override def discoverExisting(onExitFactory: String => () => Unit): List[DiscoveredProcess] =
    List.empty // Local OS processes cannot be re-adopted after JVM restart

  override def cleanup(): Unit =
    usedPorts.clear()
