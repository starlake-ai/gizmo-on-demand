package ai.starlake.acl.api

import ai.starlake.acl.AclError
import ai.starlake.acl.model.{DenyReason, TableRef, TenantId, UserIdentity}
import ai.starlake.acl.policy.ResourceLookupResult
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

class AclSqlTest extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  private var tempDir: Path = scala.compiletime.uninitialized
  private var basePath: Path = scala.compiletime.uninitialized

  override def beforeEach(): Unit = {
    tempDir = Files.createTempDirectory("aclsql-test")
    basePath = tempDir
  }

  override def afterEach(): Unit = {
    // Clean up temp directory
    if Files.exists(tempDir) then
      Files.walk(tempDir).toList.asScala.sortBy(-_.toString.length).foreach(Files.deleteIfExists)
  }

  private def createTenantWithGrant(
      tenantName: String,
      target: String,
      principals: List[String]
  ): TenantId = {
    val tenantDir = basePath.resolve(tenantName.toLowerCase)
    Files.createDirectories(tenantDir)

    val yaml =
      s"""grants:
         |  - target: "$target"
         |    principals:
         |${principals.map(p => s"""      - "$p"""").mkString("\n")}
         |""".stripMargin

    Files.writeString(tenantDir.resolve("acl.yaml"), yaml)
    TenantId.parse(tenantName).toOption.get
  }

  private val baseTableLookup: (TenantId, TableRef) => ResourceLookupResult =
    (_, _) => ResourceLookupResult.BaseTable

  private def userOf(name: String): UserIdentity = UserIdentity(name, Set.empty)

  // ---------------------------------------------------------------------------
  // Basic checkAccess
  // ---------------------------------------------------------------------------

  "AclSql.checkAccess" should "return allowed for valid tenant and authorized user" in {
    val tenant = createTenantWithGrant("acme", "db.sch.orders", List("user:alice"))
    val aclSql = AclSql(basePath, baseTableLookup)

    val result = aclSql.checkAccess(tenant, "SELECT * FROM db.sch.orders", userOf("alice"))

    result.isRight shouldBe true
    result.toOption.get.isAllowed shouldBe true
  }

  it should "return denied for valid tenant and unauthorized user" in {
    val tenant = createTenantWithGrant("acme", "db.sch.orders", List("user:alice"))
    val aclSql = AclSql(basePath, baseTableLookup)

    val result = aclSql.checkAccess(tenant, "SELECT * FROM db.sch.orders", userOf("bob"))

    result.isRight shouldBe true
    result.toOption.get.isDenied shouldBe true
    result.toOption.get.result.tableAccesses.head.denyReason shouldBe Some(
      DenyReason.NoMatchingGrant(
        TableRef("db", "sch", "orders"),
        userOf("bob")
      )
    )
  }

  it should "return TenantNotFound error for unknown tenant" in {
    val unknownTenant = TenantId.parse("nonexistent").toOption.get
    val aclSql = AclSql(basePath, baseTableLookup)

    val result = aclSql.checkAccess(unknownTenant, "SELECT * FROM t", userOf("alice"))

    result.isLeft shouldBe true
    result.left.toOption.get match {
      case AclError.TenantNotFound(tid) =>
        tid shouldBe "nonexistent"
      case other =>
        fail(s"Expected AclError.TenantNotFound, got $other")
    }
  }

  // ---------------------------------------------------------------------------
  // String tenant parameter
  // ---------------------------------------------------------------------------

  it should "accept String tenant parameter for valid tenant ID" in {
    val _ = createTenantWithGrant("acme", "db.sch.orders", List("user:alice"))
    val aclSql = AclSql(basePath, baseTableLookup)

    val result = aclSql.checkAccess("acme", "SELECT * FROM db.sch.orders", userOf("alice"), false, SqlContext.default)

    result.isRight shouldBe true
    result.toOption.get.isAllowed shouldBe true
  }

  it should "return ConfigError for invalid tenant ID string" in {
    val aclSql = AclSql(basePath, baseTableLookup)

    val result = aclSql.checkAccess("invalid.tenant", "SELECT * FROM t", userOf("alice"), false, SqlContext.default)

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[AclError.ConfigError]
    result.left.toOption.get.message should include("Invalid tenant ID")
  }

  // ---------------------------------------------------------------------------
  // Sequential multi-tenant access
  // ---------------------------------------------------------------------------

  it should "allow sequential access to different tenants" in {
    val tenant1 = createTenantWithGrant("acme", "db.sch.orders", List("user:alice"))
    val tenant2 = createTenantWithGrant("globex", "db.sch.products", List("user:bob"))
    val aclSql = AclSql(basePath, baseTableLookup)

    // First tenant
    val result1 = aclSql.checkAccess(tenant1, "SELECT * FROM db.sch.orders", userOf("alice"))
    result1.toOption.get.isAllowed shouldBe true

    // Second tenant
    val result2 = aclSql.checkAccess(tenant2, "SELECT * FROM db.sch.products", userOf("bob"))
    result2.toOption.get.isAllowed shouldBe true

    // Cross-tenant access denied
    val result3 = aclSql.checkAccess(tenant1, "SELECT * FROM db.sch.orders", userOf("bob"))
    result3.toOption.get.isDenied shouldBe true
  }

  // ---------------------------------------------------------------------------
  // Cache invalidation
  // ---------------------------------------------------------------------------

  "AclSql.invalidateTenant" should "clear cache for specific tenant" in {
    val tenant = createTenantWithGrant("acme", "db.sch.orders", List("user:alice"))
    val aclSql = AclSql(basePath, baseTableLookup)

    // Warm the cache
    val _ = aclSql.checkAccess(tenant, "SELECT * FROM db.sch.orders", userOf("alice"))
    aclSql.tenantStatus(tenant) shouldBe a[TenantStatus.Fresh]

    // Invalidate
    aclSql.invalidateTenant(tenant)
    aclSql.tenantStatus(tenant) shouldBe TenantStatus.NotLoaded
  }

  "AclSql.invalidateAll" should "clear all cached tenants" in {
    val tenant1 = createTenantWithGrant("acme", "db.sch.orders", List("user:alice"))
    val tenant2 = createTenantWithGrant("globex", "db.sch.products", List("user:bob"))
    val aclSql = AclSql(basePath, baseTableLookup)

    // Warm the cache
    val _ = aclSql.checkAccess(tenant1, "SELECT * FROM db.sch.orders", userOf("alice"))
    val _ = aclSql.checkAccess(tenant2, "SELECT * FROM db.sch.products", userOf("bob"))

    aclSql.tenantStatus(tenant1) shouldBe a[TenantStatus.Fresh]
    aclSql.tenantStatus(tenant2) shouldBe a[TenantStatus.Fresh]

    // Invalidate all
    aclSql.invalidateAll()
    aclSql.tenantStatus(tenant1) shouldBe TenantStatus.NotLoaded
    aclSql.tenantStatus(tenant2) shouldBe TenantStatus.NotLoaded
  }

  // ---------------------------------------------------------------------------
  // Tenant status
  // ---------------------------------------------------------------------------

  "AclSql.tenantStatus" should "return NotLoaded for never-accessed tenant" in {
    val aclSql = AclSql(basePath, baseTableLookup)
    val tenant = TenantId.parse("fresh").toOption.get

    aclSql.tenantStatus(tenant) shouldBe TenantStatus.NotLoaded
  }

  it should "return Fresh after successful load" in {
    val tenant = createTenantWithGrant("acme", "db.sch.orders", List("user:alice"))
    val aclSql = AclSql(basePath, baseTableLookup)

    val _ = aclSql.checkAccess(tenant, "SELECT * FROM db.sch.orders", userOf("alice"))

    aclSql.tenantStatus(tenant) shouldBe a[TenantStatus.Fresh]
  }

  // ---------------------------------------------------------------------------
  // Result includes tenantId
  // ---------------------------------------------------------------------------

  "AccessResult" should "include tenantId field" in {
    val tenant = createTenantWithGrant("acme", "db.sch.orders", List("user:alice"))
    val aclSql = AclSql(basePath, baseTableLookup)

    val result = aclSql.checkAccess(tenant, "SELECT * FROM db.sch.orders", userOf("alice"))

    result.toOption.get.result.tenantId shouldBe Some(tenant)
  }

  // ---------------------------------------------------------------------------
  // View resolver receives tenant context
  // ---------------------------------------------------------------------------

  "View resolver callback" should "receive tenant context" in {
    val tenant = createTenantWithGrant("acme", "db.sch.view1", List("user:alice"))
    var receivedTenant: Option[TenantId] = None

    val tenantAwareLookup: (TenantId, TableRef) => ResourceLookupResult = { (tid, ref) =>
      receivedTenant = Some(tid)
      if ref.table == "view1" then ResourceLookupResult.View("SELECT * FROM db.sch.base")
      else ResourceLookupResult.BaseTable
    }

    // Need grant on base table too
    Files.writeString(
      basePath.resolve("acme").resolve("base.yaml"),
      """grants:
        |  - target: "db.sch.base"
        |    principals:
        |      - "user:alice"
        |""".stripMargin
    )

    val aclSql = new AclSql(basePath, tenantAwareLookup)
    val _ = aclSql.checkAccess(tenant, "SELECT * FROM db.sch.view1", userOf("alice"))

    receivedTenant shouldBe Some(tenant)
  }

  // ---------------------------------------------------------------------------
  // checkAccessAll
  // ---------------------------------------------------------------------------

  "AclSql.checkAccessAll" should "return results for multiple statements" in {
    val tenant = createTenantWithGrant("acme", "db.sch.orders", List("user:alice"))
    val aclSql = AclSql(basePath, baseTableLookup)

    val results = aclSql.checkAccessAll(
      tenant,
      "SELECT * FROM db.sch.orders; SELECT * FROM db.sch.secret",
      userOf("alice")
    )

    results should have size 2
    results.head.toOption.get.isAllowed shouldBe true
    results(1).toOption.get.isDenied shouldBe true
  }

  // ---------------------------------------------------------------------------
  // Config options
  // ---------------------------------------------------------------------------

  it should "use per-call SqlContext for table qualification" in {
    val tenant = createTenantWithGrant("acme", "mydb.public.orders", List("user:alice"))
    val aclSql = AclSql(basePath, baseTableLookup)
    val ctx = SqlContext(defaultDatabase = Some("mydb"), defaultSchema = Some("public"))

    // With SqlContext defaults, unqualified table should resolve correctly
    val result = aclSql.checkAccess(tenant, "SELECT * FROM orders", userOf("alice"), sqlContext = ctx)

    result.toOption.get.isAllowed shouldBe true
  }

  // ---------------------------------------------------------------------------
  // Empty tenant folder
  // ---------------------------------------------------------------------------

  it should "return denied for empty tenant folder (deny-all)" in {
    // Create empty tenant folder
    val tenantDir = basePath.resolve("emptytenant")
    Files.createDirectories(tenantDir)
    val tenant = TenantId.parse("emptytenant").toOption.get

    val aclSql = AclSql(basePath, baseTableLookup)
    val result = aclSql.checkAccess(tenant, "SELECT * FROM db.sch.orders", userOf("alice"))

    result.isRight shouldBe true
    result.toOption.get.isDenied shouldBe true
  }
}
