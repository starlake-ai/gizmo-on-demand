package ai.starlake.gizmo.ondemand.config

import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

case class KubernetesConfig(
    namespace: String,
    imageName: String,
    serviceAccountName: Option[String],
    proxyPort: Int,
    backendPort: Int,
    serviceType: String,
    imagePullPolicy: String,
    imagePullSecrets: List[String],
    labels: Map[String, String],
    startupTimeoutSeconds: Int
)

object KubernetesConfig:
  implicit val reader: ConfigReader[KubernetesConfig] = deriveReader
