package ai.starlake.gizmo

import io.circe.generic.auto.*
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.* // Automatic Circe encoder/decoder derivation

/** Tapir endpoint definitions for process management */
object ProcessEndpoints:

  // Base endpoint with common error handling
  private val baseEndpoint = endpoint
    .errorOut(jsonBody[ErrorResponse])

  /** Endpoint to start a process */
  val startProcess: PublicEndpoint[StartProcessRequest, ErrorResponse, StartProcessResponse, Any] =
    baseEndpoint
      .post
      .in("api" / "process" / "start")
      .in(jsonBody[StartProcessRequest])
      .out(jsonBody[StartProcessResponse])
      .name("Start Process")
      .description("Start a new process with the given name")

  /** Endpoint to stop a process */
  val stopProcess: PublicEndpoint[StopProcessRequest, ErrorResponse, StopProcessResponse, Any] =
    baseEndpoint
      .post
      .in("api" / "process" / "stop")
      .in(jsonBody[StopProcessRequest])
      .out(jsonBody[StopProcessResponse])
      .name("Stop Process")
      .description("Stop a running process")

  /** Endpoint to restart a process */
  val restartProcess: PublicEndpoint[RestartProcessRequest, ErrorResponse, RestartProcessResponse, Any] =
    baseEndpoint
      .post
      .in("api" / "process" / "restart")
      .in(jsonBody[RestartProcessRequest])
      .out(jsonBody[RestartProcessResponse])
      .name("Restart Process")
      .description("Restart a running process")

  /** Endpoint to list all processes */
  val listProcesses: PublicEndpoint[Unit, ErrorResponse, ListProcessesResponse, Any] =
    baseEndpoint
      .get
      .in("api" / "process" / "list")
      .out(jsonBody[ListProcessesResponse])
      .name("List Processes")
      .description("List all running processes")

  /** Endpoint to stop all processes */
  val stopAll: PublicEndpoint[Unit, ErrorResponse, StopProcessResponse, Any] =
    baseEndpoint
      .post
      .in("api" / "process" / "stopAll")
      .out(jsonBody[StopProcessResponse])
      .name("Stop All Processes")
      .description("Stop all running processes")

  /** All endpoints combined */
  val all = List(
    startProcess,
    stopProcess,
    restartProcess,
    listProcesses,
    stopAll
  )

