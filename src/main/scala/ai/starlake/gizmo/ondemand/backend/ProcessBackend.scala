package ai.starlake.gizmo.ondemand.backend

/** Result returned by a backend after spawning a process */
case class SpawnResult(handle: ProcessHandle, host: String, port: Int)

/** A process discovered during startup recovery */
case class DiscoveredProcess(
    name: String,
    handle: ProcessHandle,
    host: String,
    port: Int,
    backendPort: Int,
    arguments: Map[String, String]
)

/** Abstract handle to a spawned process — local or remote */
sealed trait ProcessHandle:
  def pid: Option[Long]

/** Handle wrapping a local java.lang.Process */
case class LocalProcessHandle(process: java.lang.Process) extends ProcessHandle:
  def pid: Option[Long] = Some(process.pid())

/** Handle wrapping a Kubernetes Pod + Service */
case class K8sProcessHandle(podName: String, serviceName: String, namespace: String)
    extends ProcessHandle:
  def pid: Option[Long] = None

/** Strategy trait that abstracts how proxy instances are spawned */
trait ProcessBackend:
  /** Spawn a proxy instance.
    * @param name logical name for the instance
    * @param envVars environment variables to pass
    * @param proxyPort desired proxy port (backends may ignore this, e.g. K8s uses fixed ports)
    * @param backendPort desired backend port
    * @param onExit callback invoked when the process exits unexpectedly
    * @return SpawnResult on success, or error message
    */
  def start(
      name: String,
      envVars: Map[String, String],
      proxyPort: Int,
      backendPort: Int,
      onExit: () => Unit
  ): Either[String, SpawnResult]

  /** Stop a running instance */
  def stop(handle: ProcessHandle): Either[String, Unit]

  /** Check whether an instance is still alive */
  def isAlive(handle: ProcessHandle): Boolean

  /** Stop all instances managed by this backend.
    * @return number of instances stopped
    */
  def stopAll(): Int

  /** Discover processes still running from a previous manager lifecycle.
    * Used on startup to re-register existing pods/processes.
    * @param onExitFactory given a process name, returns the onExit callback
    * @return list of discovered processes
    */
  def discoverExisting(onExitFactory: String => () => Unit): List[DiscoveredProcess]

  /** Release any resources held by this backend (clients, watches, etc.) */
  def cleanup(): Unit
