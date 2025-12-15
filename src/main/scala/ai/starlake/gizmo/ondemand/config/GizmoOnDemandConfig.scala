package ai.starlake.gizmo.ondemand.config

import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

case class GizmoOnDemandConfig(
    host: String,
    port: Int,
    minPort: Int,
    maxPort: Int,
    maxProcesses: Int,
    defaultGizmoScript: String,
    proxyScript: String,
    apiKey: Option[String]
)

object GizmoOnDemandConfig:
  implicit val reader: ConfigReader[GizmoOnDemandConfig] = deriveReader
