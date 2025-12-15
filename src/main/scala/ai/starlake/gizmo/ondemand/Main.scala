package ai.starlake.gizmo.ondemand

import com.typesafe.scalalogging.LazyLogging
import sttp.tapir.server.jdkhttp.*

object Main extends LazyLogging:

  /** Validate API key */
  private def validateApiKey(
      providedKey: Option[String]
  ): Either[ErrorResponse, Unit] =
    EnvVars.apiKey match
      case None =>
        // If no API key is configured, allow all requests
        Right(())
      case Some(expectedKey) =>
        providedKey match
          case Some(key) if key == expectedKey =>
            Right(())
          case _ =>
            Left(ErrorResponse("Invalid or missing API key"))

  def main(args: Array[String]): Unit =
    logger.info("Starting Process Manager Server...")
    logger.info(s"Configuration: host=${EnvVars.host}, port=${EnvVars.port}")
    EnvVars.apiKey match
      case Some(_) => logger.info("API key authentication is ENABLED")
      case None    =>
        logger.warn(
          "API key authentication is DISABLED - set SL_GIZMO_API_KEY to enable"
        )

    // Create the process manager
    val processManager = new ProcessManager()

    // Define server logic for each endpoint using .handle extension methods
    val startProcessEndpoint = ProcessEndpoints.startProcess.handle {
      case (apiKey, request) =>
        validateApiKey(apiKey) match
          case Left(error) => Left(error)
          case Right(_)    =>
            processManager.startProcess(
              request.processName,
              request.arguments
            ) match
              case Left(error)     => Left(ErrorResponse(error))
              case Right(response) => Right(response)
    }

    val stopProcessEndpoint = ProcessEndpoints.stopProcess.handle {
      case (apiKey, request) =>
        validateApiKey(apiKey) match
          case Left(error) => Left(error)
          case Right(_)    =>
            processManager.stopProcess(request.processName) match
              case Left(error)     => Left(ErrorResponse(error))
              case Right(response) => Right(response)
    }

    val restartProcessEndpoint = ProcessEndpoints.restartProcess.handle {
      case (apiKey, request) =>
        validateApiKey(apiKey) match
          case Left(error) => Left(error)
          case Right(_)    =>
            processManager.restartProcess(request.processName) match
              case Left(error)     => Left(ErrorResponse(error))
              case Right(response) => Right(response)
    }

    val listProcessesEndpoint = ProcessEndpoints.listProcesses.handle {
      apiKey =>
        validateApiKey(apiKey) match
          case Left(error) => Left(error)
          case Right(_)    => Right(processManager.listProcesses)
    }

    val stopAllEndpoint = ProcessEndpoints.stopAll.handle { apiKey =>
      validateApiKey(apiKey) match
        case Left(error) => Left(error)
        case Right(_)    => Right(processManager.stopAll)
    }

    // Health endpoint (no authentication required)
    val healthEndpoint = ProcessEndpoints.health.handle { _ =>
      Right(HealthResponse(status = "ok", message = "Gizmo On-Demand Process Manager is running"))
    }

    // Create and start the JDK HTTP server with Tapir
    val server = JdkHttpServer()
      .port(EnvVars.port)
      .host(EnvVars.host)
      .addEndpoint(healthEndpoint)
      .addEndpoint(startProcessEndpoint)
      .addEndpoint(stopProcessEndpoint)
      .addEndpoint(restartProcessEndpoint)
      .addEndpoint(listProcessesEndpoint)
      .addEndpoint(stopAllEndpoint)
      .start()

    logger.info(s"Server started at http://${EnvVars.host}:${EnvVars.port}")
    logger.info("API endpoints:")
    logger.info("  GET  /health")
    logger.info("  POST /api/process/start")
    logger.info("  POST /api/process/stop")
    logger.info("  POST /api/process/restart")
    logger.info("  GET  /api/process/list")
    logger.info("  POST /api/process/stopAll")
    logger.info("Press Ctrl+C to stop")
