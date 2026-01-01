package ai.starlake.gizmo.proxy.gizmoserver

import com.typesafe.scalalogging.LazyLogging

import java.util.concurrent.{ScheduledExecutorService, ScheduledFuture, Executors, TimeUnit}
import java.util.concurrent.atomic.AtomicLong


class GizmoServerManager(
    port: Int,
    initSqlCommands: String,
    envVars: Map[String, String],
    idleTimeout: Int
) extends LazyLogging:

  import ai.starlake.gizmo.ondemand.EnvVars
  import scala.jdk.CollectionConverters.*

  @volatile private var process: Option[java.lang.Process] = None
  private val lastActivityTime = new AtomicLong(System.currentTimeMillis())
  private var idleCheckScheduler: ScheduledExecutorService | Null = null
  private var idleCheckFuture: ScheduledFuture[?] | Null = null
  @volatile private var stoppedByIdleTimeout = false

  /** Record activity to reset the idle timer */
  def recordActivity(): Unit =
    lastActivityTime.set(System.currentTimeMillis())
    // If server was stopped due to idle timeout, restart it
    if stoppedByIdleTimeout && process.isEmpty then
      logger.info("Activity detected after idle shutdown, restarting backend server")
      stoppedByIdleTimeout = false
      startInternal()

  /** Check if the server is currently running */
  def isRunning: Boolean = process.isDefined

  def start(): Unit =
    startInternal()
    startIdleTimeoutChecker()

  private def startInternal(): Unit =
    logger.info(
      s"Starting Backend Gizmo server on port $port using script: ${EnvVars.defaultGizmoScript}"
    )

    val command = Seq(EnvVars.defaultGizmoScript).asJava
    val processBuilder = new java.lang.ProcessBuilder(command)

    val env = processBuilder.environment()

    // Explicitly forward provided environment variables
    envVars.foreach { case (k, v) =>
      logger.debug(s"Setting env var: $k=$v")
      env.put(k, v)
    }

    // Set both PORT and GIZMO_SERVER_PORT for the backend script
    env.put("PORT", port.toString)
    env.put("GIZMO_SERVER_PORT", port.toString)
    logger.info(s"Set PORT and GIZMO_SERVER_PORT to $port")

    logger.info(s"Injecting INIT_SQL_COMMANDS into backend environment")
    env.put("INIT_SQL_COMMANDS", initSqlCommands)

    // Log key variables for debugging
    logger.info(s"GIZMO_SERVER_PORT=${env.get("GIZMO_SERVER_PORT")}")
    logger.info(s"GIZMOSQL_USERNAME=${env.get("GIZMOSQL_USERNAME")}")
    logger.info(s"JWT_SECRET_KEY=${env.get("JWT_SECRET_KEY")}")

    // Clean up any orphaned process on the target port before starting
    cleanupPort(port)

    val p = processBuilder.start()
    process = Some(p)

    // Monitor process exit to detect unexpected backend failures.
    //
    // IMPORTANT: The proxy server is killed (halt) ONLY when the backend exits UNEXPECTEDLY.
    // This happens when the backend crashes or fails - we detect this by checking if
    // process.isDefined (meaning we didn't intentionally stop it).
    //
    // The proxy server is NOT killed when the backend is intentionally stopped via:
    // - Idle timeout (checkIdleTimeout -> stopInternal)
    // - Immediate shutdown mode (onRequestComplete -> stopInternal)
    // - Normal shutdown (stop -> stopInternal)
    // In these cases, stopInternal() sets process = None BEFORE destroying the process,
    // so this callback sees process.isEmpty and does NOT call halt().
    p.onExit().thenAccept { _ =>
      if process.isDefined then
        // Backend exited unexpectedly (crashed) - kill the proxy too
        logger.error(
          s"Backend Gizmo server (PID: ${p.pid()}) exited unexpectedly with code ${p.exitValue()}"
        )
        logger.error("Initiating Proxy Server shutdown due to backend failure")
        try
          System.out.println("Attempting to exit...")
          Runtime.getRuntime().halt(1)
        catch
          case se: SecurityException =>
            System.err.println("SecurityManager prevented exit: " + se.getMessage)
      // else: Intentional stop (idle timeout, request complete, or shutdown) - proxy keeps running
    }

    // Gobblers
    new Thread(() => {
      val scanner = new java.util.Scanner(p.getInputStream)
      while (scanner.hasNextLine) {
        val line = scanner.nextLine()
        logger.info(s"[Gizmo Backend] $line")
      }
    }).start()

    new Thread(() => {
      val scanner = new java.util.Scanner(p.getErrorStream)
      while (scanner.hasNextLine) {
        val line = scanner.nextLine()
        logger.error(s"[Gizmo Backend Error] $line")
      }
    }).start()

    val pid = p.pid()
    logger.info(s"Backend Gizmo server started (PID: $pid)")

    // Give the container a moment to start
    Thread.sleep(5000)

    // Check if it's still alive
    if (!p.isAlive) {
      logger.error(
        s"Backend Gizmo server died immediately with exit code: ${p.exitValue()}"
      )
      throw new RuntimeException(
        s"Backend Gizmo server failed to start (exit code: ${p.exitValue()})"
      )
    }

    logger.info(s"Backend Gizmo server confirmed running (PID: $pid)")

  private def cleanupPort(port: Int): Unit =
    try
      val lsofBuilder =
        new java.lang.ProcessBuilder(Seq("lsof", "-ti", s":$port").asJava)
      val lsofProcess = lsofBuilder.start()
      val output = new String(lsofProcess.getInputStream.readAllBytes()).trim
      lsofProcess.waitFor()

      if output.nonEmpty then
        val pids = output.split("\n").map(_.trim).filter(_.nonEmpty)
        pids.foreach { pid =>
          logger.warn(
            s"Found orphaned process with PID $pid on port $port. Killing it."
          )
          new java.lang.ProcessBuilder(Seq("kill", "-9", pid).asJava)
            .start()
            .waitFor()
        }
    catch
      case e: Exception =>
        logger.warn(s"Failed to cleanup port $port: ${e.getMessage}")

  private def startIdleTimeoutChecker(): Unit =
    if idleTimeout < 0 then
      logger.info("Idle timeout disabled (SL_GIZMO_IDLE_TIMEOUT < 0)")
      return

    if idleTimeout == 0 then
      logger.info("Immediate shutdown mode enabled (SL_GIZMO_IDLE_TIMEOUT = 0)")
      return

    logger.info(s"Idle timeout enabled: $idleTimeout seconds")
    idleCheckScheduler = Executors.newSingleThreadScheduledExecutor()
    idleCheckFuture = idleCheckScheduler.nn.scheduleAtFixedRate(
      () => checkIdleTimeout(),
      idleTimeout.toLong,
      1L, // Check every second
      TimeUnit.SECONDS
    )

  private def checkIdleTimeout(): Unit =
    if process.isEmpty then return

    val idleMs = System.currentTimeMillis() - lastActivityTime.get()
    val timeoutMs = idleTimeout * 1000L

    if idleMs >= timeoutMs then
      logger.info(s"Idle timeout reached (${idleMs / 1000}s >= ${idleTimeout}s), stopping backend server")
      stopInternal()
      stoppedByIdleTimeout = true

  /** Called after a request completes when idleTimeout = 0 */
  def onRequestComplete(): Unit =
    if idleTimeout == 0 && process.isDefined then
      logger.info("Request complete, stopping backend server (SL_GIZMO_IDLE_TIMEOUT = 0)")
      stopInternal()
      stoppedByIdleTimeout = true

  private def stopInternal(): Unit =
    val p = process
    // Set process to None BEFORE destroying to prevent the onExit callback
    // from thinking this was an unexpected exit and calling halt(1)
    process = None
    p.foreach { proc =>
      // Graceful termination
      proc.destroy()
      if (!proc.waitFor(5, TimeUnit.SECONDS)) then
        proc.destroyForcibly()
    }

  def stop(): Unit =
    logger.info("Stopping Backend Gizmo server...")
    // Stop the idle checker
    if idleCheckFuture != null then
      idleCheckFuture.nn.cancel(false)
    if idleCheckScheduler != null then
      idleCheckScheduler.nn.shutdown()
    stopInternal()
    logger.info("Backend Gizmo server stopped")

object GizmoServerManager:
  def apply(
      port: Int,
      initSqlCommands: String,
      envVars: Map[String, String],
      idleTimeout: Int
  ): GizmoServerManager =
    new GizmoServerManager(port, initSqlCommands, envVars, idleTimeout)
