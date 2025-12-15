package ai.starlake.gizmo.proxy.gizmoserver

import cats.effect.{IO, Resource}
import com.typesafe.scalalogging.LazyLogging

import java.io.File

class GizmoServerManager(
    port: Int,
    initSqlCommands: String,
    envVars: Map[String, String]
) extends LazyLogging:

  import ai.starlake.gizmo.ondemand.EnvVars
  import scala.jdk.CollectionConverters.*

  @volatile private var process: Option[java.lang.Process] = None

  def start(): IO[Unit] = IO:
    logger.info(
      s"Starting Backend Gizmo server on port $port using script: ${EnvVars.defaultGizmoScript}"
    )

    val command = Seq(EnvVars.defaultGizmoScript).asJava
    val processBuilder = new java.lang.ProcessBuilder(command)
    val env = processBuilder.environment()

    // Explicitly forward provided environment variables
    envVars.foreach { case (k, v) => env.put(k, v) }

    env.put("PORT", port.toString)

    logger.info(s"Injecting INIT_SQL_COMMANDS into backend environment")
    env.put("INIT_SQL_COMMANDS", initSqlCommands)

    // Pass the port to the script as an environment variable (or argument if needed, but sticking to env for now)
    // The script likely expects PORT env var or similar.
    // ProcessManager.startProcess sets PG_PORT etc.
    // We should ensure the process inherits the current environment, which ProcessBuilder does by default.
    // We can also explicitly overwrite the port if the script expects a specific variable.
    // For now, assuming script uses relevant env vars that we will set in ProcessManager when starting the proxy.

    // Redirect output to logger via gobblers (simplified here or reuse)
    // For now, inherit IO to see output in console or simple gobbler
    // processBuilder.inheritIO()

    // Clean up any orphaned process on the target port before starting
    cleanupPort(port)

    val p = processBuilder.start()
    process = Some(p)

    // Monitor process exit
    p.onExit().thenAccept { _ =>
      // Check if this was an intentional stop (process is None if stop() was called)
      if process.isDefined then
        logger.error(
          s"Backend Gizmo server (PID: ${p.pid()}) exited unexpectedly with code ${p.exitValue()}"
        )
        // Fail fast: Stop the proxy server too so ProcessManager detects it
        // We can do this by exiting the JVM, which is monitored by ProcessManager
        logger.error("Initiating Proxy Server shutdown due to backend failure")
        System.exit(1)
    }

    // Gobblers
    new Thread(() => {
      val scanner = new java.util.Scanner(p.getInputStream)
      while (scanner.hasNextLine) {
        logger.debug(s"[Gizmo Backend] ${scanner.nextLine()}")
      }
    }).start()

    new Thread(() => {
      val scanner = new java.util.Scanner(p.getErrorStream)
      while (scanner.hasNextLine) {
        logger.debug(s"[Gizmo Backend Error] ${scanner.nextLine()}")
      }
    }).start()

    val pid = p.pid()
    logger.info(s"Backend Gizmo server started (PID: $pid)")

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

  def stop(): IO[Unit] = IO:
    logger.info("Stopping Backend Gizmo server...")
    process.foreach { p =>
      // Graceful termination
      p.destroy()
      // Wait a bit?
      if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
        p.destroyForcibly()
      }
    }
    process = None
    logger.info("Backend Gizmo server stopped")

object GizmoServerManager:
  def resource(
      port: Int,
      initSqlCommands: String,
      envVars: Map[String, String]
  ): Resource[IO, GizmoServerManager] =
    Resource.make(
      IO:
        val manager = new GizmoServerManager(port, initSqlCommands, envVars)
        manager
    )(manager => manager.stop())
