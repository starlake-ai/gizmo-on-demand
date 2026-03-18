package ai.starlake.gizmo.ondemand

import ai.starlake.gizmo.ondemand.backend.*
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
    logger.info(s"Runtime type: ${EnvVars.runtimeType}")
    EnvVars.apiKey match
      case Some(_) => logger.info("API key authentication is ENABLED")
      case None    =>
        logger.warn(
          "API key authentication is DISABLED - set SL_GIZMO_API_KEY to enable"
        )

    // Create the appropriate backend based on runtime type
    val backend: ProcessBackend = EnvVars.runtimeType.toLowerCase match
      case "kubernetes" | "k8s" =>
        EnvVars.kubernetes match
          case Some(k8sConfig) =>
            logger.info(s"Using Kubernetes backend (namespace=${k8sConfig.namespace}, image=${k8sConfig.imageName})")
            new KubernetesProcessBackend(k8sConfig)
          case None =>
            throw new IllegalStateException(
              "Runtime type is 'kubernetes' but no kubernetes configuration found in application.conf"
            )
      case _ =>
        logger.info("Using local process backend")
        new LocalProcessBackend()

    // Register shutdown hook for backend cleanup
    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      logger.info("Shutting down — cleaning up backend resources...")
      backend.cleanup()
    }))

    // Create the process manager
    val processManager = new ProcessManager(backend)

    // Define server logic for each endpoint using .handle extension methods
    val startProcessEndpoint = ProcessEndpoints.startProcess.handle {
      case (apiKey, request) =>
        validateApiKey(apiKey) match
          case Left(error) => Left(error)
          case Right(_)    =>
            processManager.startProcess(
              request.processName,
              request.connectionName,
              request.port,
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
