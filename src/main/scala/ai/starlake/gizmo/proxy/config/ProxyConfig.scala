package ai.starlake.gizmo.proxy.config

import pureconfig.*

case class ProxyTlsConfig(
    enabled: Boolean,
    certChain: String,
    privateKey: String
) derives ConfigReader

case class ProxyServerConfig(
    host: String,
    port: Int,
    secretKey: String,
    tls: ProxyTlsConfig
) derives ConfigReader

case class BackendTlsConfig(
    enabled: Boolean,
    trustedCertificates: String
) derives ConfigReader

case class BackendConfig(
    host: String,
    port: Int,
    tls: BackendTlsConfig,
    defaultUsername: String,
    defaultPassword: String
) derives ConfigReader

case class ValidationRulesConfig(
    allowByDefault: Boolean,
    bypassUsers: List[String],
    rulesFile: String
) derives ConfigReader

case class ValidationConfig(
    enabled: Boolean,
    rules: ValidationRulesConfig
) derives ConfigReader

case class LoggingConfig(
    level: String,
    logStatements: Boolean,
    logValidation: Boolean
) derives ConfigReader

case class GizmoSqlProxyConfig(
    proxy: ProxyServerConfig,
    backend: BackendConfig,
    validation: ValidationConfig,
    logging: LoggingConfig
) derives ConfigReader

object ProxyConfig:
  def load(): GizmoSqlProxyConfig =
    ConfigSource.default
      .at("gizmosql-proxy")
      .loadOrThrow[GizmoSqlProxyConfig]
