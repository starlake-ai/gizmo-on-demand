package ai.starlake.gizmo.ondemand

import ai.starlake.gizmo.ondemand.config.GizmoOnDemandConfig
import pureconfig.ConfigSource

/** Centralized configuration from application.conf via PureConfig */
object EnvVars:

  private val config: GizmoOnDemandConfig = ConfigSource.default
    .at("gizmo-on-demand")
    .loadOrThrow[GizmoOnDemandConfig]

  /** Host address to bind the server to */
  val host: String = config.host

  /** Port number for the server to listen on */
  val port: Int = config.port

  /** Minimum port number for managed processes */
  val minPort: Int = config.minPort

  /** Maximum port number for managed processes */
  val maxPort: Int = config.maxPort

  /** Maximum number of processes that can be spawned */
  val maxProcesses: Int = config.maxProcesses

  /** Default script to execute when starting a process */
  val defaultGizmoScript: String = config.defaultGizmoScript

  /** Script to execute when starting a proxy server */
  val proxyScript: String = config.proxyScript

  /** API key required for authentication */
  val apiKey: Option[String] = config.apiKey
