package ai.starlake.gizmo.ondemand

import ai.starlake.gizmo.ondemand.backend.*
import com.typesafe.scalalogging.LazyLogging

import scala.collection.concurrent.TrieMap

/** Represents a managed process */
case class ManagedProcess(
    name: String,
    port: Int,
    backendPort: Int,
    handle: ProcessHandle,
    host: String,
    arguments: Map[String, String],
    externalHost: Option[String] = None,
    externalPort: Option[Int] = None
)

/** Process manager that handles lifecycle of processes */
class ProcessManager(backend: ProcessBackend) extends LazyLogging:

  private val processes = TrieMap.empty[String, ManagedProcess]

  // Recover existing processes on startup (e.g. K8s pods surviving a manager restart)
  recoverExistingProcesses()

  private def recoverExistingProcesses(): Unit =
    val onExitFactory: String => () => Unit = processName =>
      () => {
        if processes.contains(processName) then
          logger.warn(s"Recovered Proxy Server '$processName' exited unexpectedly")
          processes.remove(processName)
      }

    val discovered = backend.discoverExisting(onExitFactory)
    discovered.foreach { dp =>
      val managedProcess = ManagedProcess(
        name = dp.name,
        port = dp.port,
        backendPort = dp.backendPort,
        handle = dp.handle,
        host = dp.host,
        arguments = dp.arguments,
        externalHost = dp.externalHost,
        externalPort = dp.externalPort
      )
      processes.putIfAbsent(dp.name, managedProcess) match
        case Some(_) =>
          logger.warn(s"Duplicate discovered process '${dp.name}', skipping")
        case None =>
          logger.info(s"Recovered process '${dp.name}' (host=${dp.host}, port=${dp.port})")
    }
    if discovered.nonEmpty then
      logger.info(s"Recovered ${discovered.size} existing process(es) from previous lifecycle")

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
        "PG_PORT",
        "PG_HOST"
      )
      val missingVars = requiredVars.filterNot(arguments.contains)

      if missingVars.nonEmpty then
        Left(
          s"Missing required environment variables: ${missingVars.mkString(", ")}"
        )
      else
        // Reserve ports via LocalProcessBackend, or use fixed ports for K8s
        val portsResult = backend match
          case local: LocalProcessBackend => local.claimPairedPorts(port)
          case _                          =>
            // K8s backend uses fixed ports inside pod; we still need logical ports for tracking
            Right((port.getOrElse(31338), port.getOrElse(31338) + 1000))

        portsResult match
          case Left(error) => Left(error)
          case Right((proxyPort, backendPort)) =>
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

            val onExit = () => {
              if processes.contains(processName) then
                logger.warn(s"Proxy Server '$processName' exited unexpectedly")
                // For local backend, kill orphaned backend
                backend match
                  case local: LocalProcessBackend =>
                    if local.killProcessOnPort(backendPort) then
                      logger.warn(s"Killed orphaned backend process on port $backendPort")
                  case _ => ()
                processes.remove(processName)
                backend match
                  case local: LocalProcessBackend => local.releasePorts(proxyPort, backendPort)
                  case _                          => ()
            }

            backend.start(processName, arguments, proxyPort, backendPort, onExit) match
              case Left(error) =>
                backend match
                  case local: LocalProcessBackend => local.releasePorts(proxyPort, backendPort)
                  case _                          => ()
                Left(error)
              case Right(spawnResult) =>
                val managedProcess = ManagedProcess(
                  processName,
                  spawnResult.port,
                  backendPort,
                  spawnResult.handle,
                  spawnResult.host,
                  arguments,
                  externalHost = spawnResult.externalHost,
                  externalPort = spawnResult.externalPort
                )

                processes.putIfAbsent(processName, managedProcess) match
                  case Some(_) =>
                    // Race condition: process was started by someone else
                    backend.stop(spawnResult.handle)
                    backend match
                      case local: LocalProcessBackend =>
                        local.releasePorts(proxyPort, backendPort)
                      case _ => ()
                    Left(
                      s"Process '$processName' is already running (race condition detected)"
                    )
                  case None =>
                    Right(
                      StartProcessResponse(
                        processName = processName,
                        port = spawnResult.port,
                        message =
                          s"Proxy Process started successfully on port ${spawnResult.port}",
                        host = Some(spawnResult.host),
                        externalHost = spawnResult.externalHost,
                        externalPort = spawnResult.externalPort
                      )
                    )

  /** Stop a running process */
  def stopProcess(processName: String): Either[String, StopProcessResponse] =
    processes.get(processName) match
      case None =>
        Left(s"Process '$processName' is not running")
      case Some(managedProcess) =>
        logger.info(s"Stopping process '$processName'")
        // For local backend, also kill by port for reliability
        backend match
          case local: LocalProcessBackend =>
            local.killProcessOnPort(managedProcess.port)
            local.killProcessOnPort(managedProcess.backendPort)
          case _ =>
            backend.stop(managedProcess.handle)
        processes.remove(processName)
        backend match
          case local: LocalProcessBackend =>
            local.releasePorts(managedProcess.port, managedProcess.backendPort)
          case _ => ()
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
        pid = mp.handle.pid,
        status = if backend.isAlive(mp.handle) then "running" else "stopped",
        host = Some(mp.host),
        externalHost = mp.externalHost,
        externalPort = mp.externalPort
      )
    }.toList
    ListProcessesResponse(processInfos)

  /** Stop all running processes and scan for any processes on managed ports */
  def stopAll: StopProcessResponse =
    val trackedCount = processes.size
    logger.info(s"Stopping all tracked processes ($trackedCount total)")

    // Stop all tracked processes
    processes.values.foreach { managedProcess =>
      logger.info(s"Stopping process '${managedProcess.name}'")
      backend match
        case local: LocalProcessBackend =>
          local.killProcessOnPort(managedProcess.port)
          local.killProcessOnPort(managedProcess.backendPort)
        case _ =>
          backend.stop(managedProcess.handle)
    }
    processes.clear()

    // Backend-level cleanup (local scans port range, K8s deletes by label)
    val additionalStopped = backend.stopAll()

    StopProcessResponse(
      processName = "all",
      message = s"Stopped ${trackedCount + additionalStopped} process(es)"
    )
