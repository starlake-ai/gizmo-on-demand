package ai.starlake.gizmo.ondemand

import io.circe.generic.auto.* // Automatic derivation - no need for manual given instances

/** Request to start a new process */
case class StartProcessRequest(processName: String, connectionName: String, port: Option[Int] = None,  arguments: Map[String, String] = Map.empty)

/** Response when a process is started */
case class StartProcessResponse(processName: String, port: Int, message: String)

/** Request to stop a process */
case class StopProcessRequest(processName: String)

/** Response when a process is stopped */
case class StopProcessResponse(processName: String, message: String)

/** Request to restart a process */
case class RestartProcessRequest(processName: String)

/** Response when a process is restarted */
case class RestartProcessResponse(processName: String, port: Int, message: String)

/** Information about a running process */
case class ProcessInfo(processName: String, port: Int, pid: Option[Long], status: String)

/** Response for list operation */
case class ListProcessesResponse(processes: List[ProcessInfo])

/** Error response */
case class ErrorResponse(error: String)

/** Health check response */
case class HealthResponse(status: String, message: String)

