package ai.starlake.gizmo.proxy.catalog

import ai.starlake.gizmo.proxy.config.SessionConfig
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.sql.Connection

/** Pure string/unit tests for DuckLakeCatalogResolver helper methods:
  * stripCreateViewPrefix and buildInitSql.
  *
  * These methods are private[catalog], so we can access them from within this package.
  */
class DuckLakeCatalogResolverUnitTest extends AnyFunSuite, Matchers:

  private def dummyConfig(
      pgHost: String = "localhost",
      pgUsername: String = "test",
      pgPassword: String = "test"
  ) = SessionConfig(
    gizmosqlUsername = "test",
    gizmosqlPassword = "test",
    slProjectId = "memory",
    slDataPath = "/tmp",
    pgUsername = pgUsername,
    pgPassword = pgPassword,
    pgPort = 5432,
    pgHost = pgHost,
    jwtSecretKey = "test_secret",
    aclTenant = "default"
  )

  /** Minimal resolver for accessing private[catalog] methods without connecting to DuckDB. */
  private class UnitTestResolver(config: SessionConfig = dummyConfig())
      extends DuckLakeCatalogResolver(config, 60_000L):

    override private[catalog] def initConnection(conn: Connection): Unit = ()

  // --- Test 23: stripCreateViewPrefix removes CREATE VIEW prefix ---
  test("stripCreateViewPrefix removes CREATE VIEW prefix"):
    val resolver = UnitTestResolver()
    try
      val input = "CREATE VIEW my_view AS SELECT id, name FROM customers"
      val result = resolver.stripCreateViewPrefix(input)
      result shouldBe "SELECT id, name FROM customers"
    finally resolver.close()

  // --- Test 24: stripCreateViewPrefix handles CREATE OR REPLACE VIEW ---
  test("stripCreateViewPrefix handles CREATE OR REPLACE VIEW"):
    val resolver = UnitTestResolver()
    try
      val input = "CREATE OR REPLACE VIEW my_view AS SELECT id FROM t"
      val result = resolver.stripCreateViewPrefix(input)
      result shouldBe "SELECT id FROM t"
    finally resolver.close()

  // --- Test 25: stripCreateViewPrefix is no-op for plain SELECT ---
  test("stripCreateViewPrefix is no-op for plain SELECT"):
    val resolver = UnitTestResolver()
    try
      val input = "SELECT id, name FROM customers WHERE id > 10"
      val result = resolver.stripCreateViewPrefix(input)
      result shouldBe input
    finally resolver.close()

  // --- Test 26: stripCreateViewPrefix with AS in quoted view name ---
  test("stripCreateViewPrefix with AS in quoted view name is now fixed"):
    val resolver = UnitTestResolver()
    try
      // JSqlParser correctly handles quoted identifiers containing " AS "
      val input = """CREATE VIEW "my AS view" AS SELECT id FROM t"""
      val result = resolver.stripCreateViewPrefix(input)
      result shouldBe "SELECT id FROM t"
    finally resolver.close()

  // --- Test 27: stripCreateViewPrefix handles multiline SQL ---
  test("stripCreateViewPrefix handles multiline SQL with space after AS"):
    val resolver = UnitTestResolver()
    try
      val input = "CREATE VIEW my_view AS SELECT id, name FROM customers WHERE id > 0"
      val result = resolver.stripCreateViewPrefix(input)
      result shouldBe "SELECT id, name FROM customers WHERE id > 0"
    finally resolver.close()

  test("stripCreateViewPrefix handles multiline SQL with AS followed by newline"):
    val resolver = UnitTestResolver()
    try
      // JSqlParser correctly handles newlines after AS
      val input =
        """CREATE VIEW my_view AS
          |SELECT id, name FROM customers""".stripMargin
      val result = resolver.stripCreateViewPrefix(input)
      result should startWith("SELECT")
      result should include("id")
      result should include("customers")
    finally resolver.close()

  test("stripCreateViewPrefix does not match when AS has no leading space"):
    val resolver = UnitTestResolver()
    try
      // "myviewAS " — no space before AS, should not match
      val input = "CREATE VIEW myviewAS SELECT id FROM t"
      val result = resolver.stripCreateViewPrefix(input)
      result shouldBe input
    finally resolver.close()

  test("stripCreateViewPrefix handles schema-qualified view name"):
    val resolver = UnitTestResolver()
    try
      val input = "CREATE VIEW main.customer_names AS SELECT name FROM customers"
      val result = resolver.stripCreateViewPrefix(input)
      result shouldBe "SELECT name FROM customers"
    finally resolver.close()

  // --- Test 28: buildInitSql includes install and load ducklake ---
  test("buildInitSql includes install and load ducklake"):
    val resolver = UnitTestResolver()
    try
      val sql = resolver.buildInitSql()
      sql should include("install ducklake")
      sql should include("load ducklake")
    finally resolver.close()

  // --- Test 29: buildInitSql replaces host.docker.internal with localhost ---
  test("buildInitSql replaces host.docker.internal with localhost"):
    val config = dummyConfig(pgHost = "host.docker.internal")
    val resolver = UnitTestResolver(config)
    try
      val sql = resolver.buildInitSql()
      sql should not include "host.docker.internal"
      sql should include("HOST 'localhost'")
    finally resolver.close()

  // --- Test 30: buildInitSql escapes single quotes in config values ---
  test("buildInitSql escapes single quotes in config values"):
    val config = dummyConfig(pgPassword = "pass'word")
    val resolver = UnitTestResolver(config)
    try
      val sql = resolver.buildInitSql()
      // Single quote should be escaped as two single quotes in SQL
      sql should include("pass''word")
      // Should not contain an unescaped single quote that would break SQL syntax
      // (the escaped version pass''word is valid SQL)
    finally resolver.close()

  // --- stripCreateViewPrefix: CREATE MATERIALIZED VIEW ---
  test("stripCreateViewPrefix handles CREATE MATERIALIZED VIEW"):
    val resolver = UnitTestResolver()
    try
      val input = "CREATE MATERIALIZED VIEW mat_view AS SELECT count(*) AS cnt FROM orders"
      val result = resolver.stripCreateViewPrefix(input)
      // JSqlParser should parse materialized views and extract the SELECT body
      result should startWith("SELECT")
      result should include("cnt")
    finally resolver.close()

  // --- stripCreateViewPrefix: preserves AS keyword in column aliases ---
  test("stripCreateViewPrefix preserves AS keyword in column aliases"):
    val resolver = UnitTestResolver()
    try
      val input = "CREATE VIEW v AS SELECT id AS customer_id, name AS customer_name FROM customers"
      val result = resolver.stripCreateViewPrefix(input)
      result shouldBe "SELECT id AS customer_id, name AS customer_name FROM customers"
    finally resolver.close()

  // --- stripCreateViewPrefix: schema-qualified view name ---
  test("stripCreateViewPrefix with schema-qualified view name"):
    val resolver = UnitTestResolver()
    try
      val input = "CREATE VIEW myschema.my_view AS SELECT id FROM t"
      val result = resolver.stripCreateViewPrefix(input)
      result shouldBe "SELECT id FROM t"
    finally resolver.close()

  // --- buildInitSql: preserves non-docker host ---
  test("buildInitSql preserves non-docker host"):
    val config = dummyConfig(pgHost = "mydb.example.com")
    val resolver = UnitTestResolver(config)
    try
      val sql = resolver.buildInitSql()
      sql should include("HOST 'mydb.example.com'")
    finally resolver.close()

  // --- buildInitSql: username containing single quotes ---
  test("buildInitSql with username containing single quotes"):
    val config = dummyConfig(pgUsername = "user'name")
    val resolver = UnitTestResolver(config)
    try
      val sql = resolver.buildInitSql()
      sql should include("USER 'user''name'")
    finally resolver.close()
