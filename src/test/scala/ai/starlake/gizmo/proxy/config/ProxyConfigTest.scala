package ai.starlake.gizmo.proxy.config

import com.typesafe.config.ConfigFactory
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import pureconfig.*

class ProxyConfigTest extends AnyFunSuite with Matchers:

  /** Helper: parse a HOCON string into GizmoSqlProxyConfig */
  private def loadFromString(hocon: String): GizmoSqlProxyConfig =
    ConfigSource
      .fromConfig(ConfigFactory.parseString(hocon))
      .at("gizmosql-proxy")
      .loadOrThrow[GizmoSqlProxyConfig]

  private val minimalConfig: String =
    """
      |gizmosql-proxy {
      |  proxy {
      |    host = "0.0.0.0"
      |    port = 31338
      |    tls { enabled = false, cert-chain = "", private-key = "" }
      |  }
      |  backend {
      |    host = "localhost"
      |    port = 31337
      |    tls { enabled = false, trusted-certificates = "" }
      |    default-username = ""
      |    default-password = ""
      |  }
      |  validation {
      |    enabled = true
      |    rules { allow-by-default = true, bypass-users = [], rules-file = "" }
      |  }
      |  logging { level = "INFO", log-statements = false, log-validation = false }
      |  session {
      |    gizmosql-username = "user"
      |    gizmosql-password = "pass"
      |    sl-project-id = "proj"
      |    sl-data-path = "/data"
      |    pg-username = "pg"
      |    pg-password = "pg"
      |    pg-port = 5432
      |    pg-host = "localhost"
      |    jwt-secret-key = "secret"
      |    acl-tenant = "default"
      |  }
      |  acl {
      |    enabled = true
      |    base-path = "/etc/acl"
      |    dialect = "duckdb"
      |    groups-claim = "groups"
      |    max-tenants = 100
      |    watcher { enabled = true, debounce-ms = 500, max-backoff-ms = 60000, poll-interval-ms = 30000 }
      |    s3 {}
      |    gcs {}
      |    azure {}
      |  }
      |}
      |""".stripMargin

  // --- AclConfig s3/gcs/azure field mapping tests ---

  test("AclConfig 's3' field is read correctly (not mangled to 's-3')") {
    val config = loadFromString(minimalConfig)
    config.acl.s3 shouldBe AclS3Config(None, None)
  }

  test("AclConfig 's3' field reads region and credentials-file") {
    val hocon = minimalConfig.replace(
      "s3 {}",
      """s3 { region = "eu-west-1", credentials-file = "/path/to/creds" }"""
    )
    val config = loadFromString(hocon)
    config.acl.s3.region shouldBe Some("eu-west-1")
    config.acl.s3.credentialsFile shouldBe Some("/path/to/creds")
  }

  test("AclConfig 'gcs' field reads project-id and service-account-key-file") {
    val hocon = minimalConfig.replace(
      "gcs {}",
      """gcs { project-id = "my-project", service-account-key-file = "/sa.json" }"""
    )
    val config = loadFromString(hocon)
    config.acl.gcs.projectId shouldBe Some("my-project")
    config.acl.gcs.serviceAccountKeyFile shouldBe Some("/sa.json")
  }

  test("AclConfig 'azure' field reads connection-string") {
    val hocon = minimalConfig.replace(
      "azure {}",
      """azure { connection-string = "DefaultEndpointsProtocol=https;AccountName=test" }"""
    )
    val config = loadFromString(hocon)
    config.acl.azure.connectionString shouldBe Some("DefaultEndpointsProtocol=https;AccountName=test")
  }

  // --- AclConfig other fields ---

  test("AclConfig kebab-case fields are mapped correctly") {
    val config = loadFromString(minimalConfig)
    config.acl.basePath shouldBe "/etc/acl"
    config.acl.groupsClaim shouldBe "groups"
    config.acl.maxTenants shouldBe 100
  }

  test("AclConfig watcher sub-config is parsed") {
    val config = loadFromString(minimalConfig)
    config.acl.watcher.enabled shouldBe true
    config.acl.watcher.debounceMs shouldBe 500L
    config.acl.watcher.maxBackoffMs shouldBe 60000L
    config.acl.watcher.pollIntervalMs shouldBe 30000L
  }

  // --- Full config loading ---

  test("full GizmoSqlProxyConfig loads from minimal HOCON") {
    val config = loadFromString(minimalConfig)

    config.proxy.host shouldBe "0.0.0.0"
    config.proxy.port shouldBe 31338
    config.proxy.tls.enabled shouldBe false

    config.backend.host shouldBe "localhost"
    config.backend.port shouldBe 31337
    config.backend.tls.enabled shouldBe false
    config.backend.defaultUsername shouldBe ""
    config.backend.defaultPassword shouldBe ""

    config.validation.enabled shouldBe true
    config.validation.rules.allowByDefault shouldBe true
    config.validation.rules.bypassUsers shouldBe empty

    config.logging.level shouldBe "INFO"
    config.logging.logStatements shouldBe false

    config.acl.enabled shouldBe true
    config.acl.dialect shouldBe "duckdb"
  }

  test("SessionConfig fields are parsed correctly") {
    val config = loadFromString(minimalConfig)
    config.session.gizmosqlUsername shouldBe "user"
    config.session.gizmosqlPassword shouldBe "pass"
    config.session.slProjectId shouldBe "proj"
    config.session.slDataPath shouldBe "/data"
    config.session.pgUsername shouldBe "pg"
    config.session.pgPassword shouldBe "pg"
    config.session.pgPort shouldBe 5432
    config.session.pgHost shouldBe "localhost"
    config.session.jwtSecretKey shouldBe "secret"
    config.session.aclTenant shouldBe "default"
  }

  // --- TLS configuration ---

  test("proxy TLS config with cert paths") {
    val hocon = minimalConfig.replace(
      """tls { enabled = false, cert-chain = "", private-key = "" }""",
      """tls { enabled = true, cert-chain = "/certs/server.pem", private-key = "/certs/server-key.pem" }"""
    )
    val config = loadFromString(hocon)
    config.proxy.tls.enabled shouldBe true
    config.proxy.tls.certChain shouldBe "/certs/server.pem"
    config.proxy.tls.privateKey shouldBe "/certs/server-key.pem"
  }

  test("backend TLS config with trusted certificates") {
    val hocon = minimalConfig.replace(
      """tls { enabled = false, trusted-certificates = "" }""",
      """tls { enabled = true, trusted-certificates = "/certs/ca.pem" }"""
    )
    val config = loadFromString(hocon)
    config.backend.tls.enabled shouldBe true
    config.backend.tls.trustedCertificates shouldBe "/certs/ca.pem"
  }

  // --- Validation configuration ---

  test("validation bypass-users list is parsed") {
    val hocon = minimalConfig.replace(
      "bypass-users = []",
      """bypass-users = ["admin", "superuser"]"""
    )
    val config = loadFromString(hocon)
    config.validation.rules.bypassUsers shouldBe List("admin", "superuser")
  }

  test("validation disabled") {
    val hocon = minimalConfig.replace("enabled = true\n    rules", "enabled = false\n    rules")
    val config = loadFromString(hocon)
    config.validation.enabled shouldBe false
  }

  // --- Error cases ---

  test("missing required field produces a config error") {
    val broken = minimalConfig.replace("base-path = \"/etc/acl\"", "")
    val result = ConfigSource
      .fromConfig(ConfigFactory.parseString(broken))
      .at("gizmosql-proxy")
      .load[GizmoSqlProxyConfig]
    result.isLeft shouldBe true
  }

  test("ProxyConfig.load reads application.conf without error") {
    // Verifies that the bundled application.conf is well-formed and parseable
    noException should be thrownBy ProxyConfig.load()
  }
