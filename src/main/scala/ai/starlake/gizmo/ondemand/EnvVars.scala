package ai.starlake.gizmo.ondemand

/** Centralized configuration from environment variables */
object EnvVars:

  /** Host address to bind the server to (default: 0.0.0.0) */
  val host: String = sys.env.getOrElse("SL_GIZMO_HOST", "0.0.0.0")

  /** Port number for the server to listen on (default: 10900) */
  val port: Int = sys.env.getOrElse("SL_GIZMO_PORT", "10900").toInt

  /** Minimum port number for managed processes (default: 8000) */
  val minPort: Int = sys.env.getOrElse("SL_GIZMO_MIN_PORT", "8000").toInt

  /** Maximum port number for managed processes (default: 9000) */
  val maxPort: Int = sys.env.getOrElse("SL_GIZMO_MAX_PORT", "9000").toInt

  /** Maximum number of processes that can be spawned (default: 10) */
  val maxProcesses: Int =
    sys.env.getOrElse("SL_GIZMO_MAX_PROCESSES", "10").toInt

  /** Default script to execute when starting a process (default:
    * /opt/gizmo/scripts/start_gizmosql.sh)
    */
  val defaultGizmoScript: String = sys.env.getOrElse(
    "SL_GIZMO_DEFAULT_SCRIPT",
    "/opt/gizmo/scripts/start_gizmosql.sh"
  )

  /** Script to execute when starting a proxy server (default:
    * /opt/gizmo/scripts/start-proxy.sh)
    */
  val proxyScript: String = sys.env.getOrElse(
    "SL_GIZMO_PROXY_SCRIPT",
    "/opt/gizmo/scripts/start-proxy.sh"
  )

  /** API key required for authentication (no default - must be set) */
  val apiKey: Option[String] = sys.env.get("SL_GIZMO_API_KEY")
