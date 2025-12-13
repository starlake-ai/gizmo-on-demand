package ai.starlake.gizmo

import sttp.tapir.server.jdkhttp.*
import com.typesafe.scalalogging.LazyLogging

object Main extends LazyLogging:

  def main(args: Array[String]): Unit =
    logger.info("Starting Process Manager Server...")
    logger.info(s"Configuration: host=${EnvVars.host}, port=${EnvVars.port}")

    // Create the process manager
    val processManager = new ProcessManager()

    // Stop all processes on startup to ensure clean state
    logger.info("Cleaning up any existing processes...")
    val stopAllResult = processManager.stopAll
    logger.info(stopAllResult.message)

    // Define server logic for each endpoint using .handle extension methods
    val startProcessEndpoint = ProcessEndpoints.startProcess.handle { request =>
      processManager.startProcess(request.processName, request.arguments) match
        case Left(error) => Left(ErrorResponse(error))
        case Right(response) => Right(response)
    }

    val stopProcessEndpoint = ProcessEndpoints.stopProcess.handle { request =>
      processManager.stopProcess(request.processName) match
        case Left(error) => Left(ErrorResponse(error))
        case Right(response) => Right(response)
    }

    val restartProcessEndpoint = ProcessEndpoints.restartProcess.handle { request =>
      processManager.restartProcess(request.processName) match
        case Left(error) => Left(ErrorResponse(error))
        case Right(response) => Right(response)
    }

    val listProcessesEndpoint = ProcessEndpoints.listProcesses.handle { _ =>
      Right(processManager.listProcesses)
    }

    val stopAllEndpoint = ProcessEndpoints.stopAll.handle { _ =>
      Right(processManager.stopAll)
    }

    // Create and start the JDK HTTP server with Tapir
    val server = JdkHttpServer()
      .port(EnvVars.port)
      .host(EnvVars.host)
      .addEndpoint(startProcessEndpoint)
      .addEndpoint(stopProcessEndpoint)
      .addEndpoint(restartProcessEndpoint)
      .addEndpoint(listProcessesEndpoint)
      .addEndpoint(stopAllEndpoint)
      .start()

    logger.info(s"Server started at http://${EnvVars.host}:${EnvVars.port}")
    logger.info("API endpoints:")
    logger.info("  POST /api/process/start")
    logger.info("  POST /api/process/stop")
    logger.info("  POST /api/process/restart")
    logger.info("  GET  /api/process/list")
    logger.info("  POST /api/process/stopAll")
    logger.info("Press Ctrl+C to stop")

