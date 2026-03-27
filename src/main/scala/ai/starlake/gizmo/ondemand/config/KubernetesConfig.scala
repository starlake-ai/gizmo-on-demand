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
    startupTimeoutSeconds: Int,
    healthCheckPort: Int,
    healthCheckPath: String,
    resourceRequestsCpu: String,
    resourceRequestsMemory: String,
    resourceLimitsCpu: String,
    resourceLimitsMemory: String,
    externalHost: Option[String],
    volumeClaimName: Option[String],
    volumeMountPath: String
)

object KubernetesConfig:
  implicit val reader: ConfigReader[KubernetesConfig] = deriveReader
