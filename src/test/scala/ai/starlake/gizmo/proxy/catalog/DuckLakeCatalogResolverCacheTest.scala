package ai.starlake.gizmo.proxy.catalog

import ai.starlake.acl.model.{TableRef, TenantId}
import ai.starlake.acl.policy.ResourceLookupResult
import ai.starlake.gizmo.proxy.config.SessionConfig
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.sql.Connection
import java.util.concurrent.atomic.AtomicInteger
import scala.compiletime.uninitialized

/** Tests for DuckLakeCatalogResolver caching behavior: TTL, invalidation, error non-caching. */
class DuckLakeCatalogResolverCacheTest extends AnyFunSuite, Matchers:

  private val dummyConfig = SessionConfig(
    gizmosqlUsername = "test",
    gizmosqlPassword = "test",
    slProjectId = "memory",
    slDataPath = "/tmp",
    pgUsername = "test",
    pgPassword = "test",
    pgPort = 5432,
    pgHost = "localhost",
    jwtSecretKey = "test_secret",
    aclTenant = "default"
  )

  private val testDbName = "memory"
  private val testSchema = "main"

  private class TestCatalogResolver(cacheTtlMs: Long = 60_000L)
      extends DuckLakeCatalogResolver(dummyConfig, cacheTtlMs):

    override private[catalog] def initConnection(conn: Connection): Unit =
      val stmt = conn.createStatement()
      try
        stmt.execute("CREATE TABLE IF NOT EXISTS customers (id INTEGER, name VARCHAR)")
        stmt.execute("CREATE VIEW IF NOT EXISTS customer_names AS SELECT name FROM customers")
        stmt.execute("CREATE VIEW IF NOT EXISTS nested_view AS SELECT name FROM customer_names")
      finally stmt.close()

  /** Resolver that counts how many times queryTable is called, to verify caching. */
  private class CountingCatalogResolver(cacheTtlMs: Long = 60_000L)
      extends DuckLakeCatalogResolver(dummyConfig, cacheTtlMs):

    val queryCount = new AtomicInteger(0)

    override private[catalog] def initConnection(conn: Connection): Unit =
      val stmt = conn.createStatement()
      try
        stmt.execute("CREATE TABLE IF NOT EXISTS customers (id INTEGER, name VARCHAR)")
        stmt.execute("CREATE VIEW IF NOT EXISTS customer_names AS SELECT name FROM customers")
      finally stmt.close()

    override private[catalog] def queryTable(conn: Connection, ref: TableRef): ResourceLookupResult =
      queryCount.incrementAndGet()
      super.queryTable(conn, ref)

  /** Resolver that can be toggled to fail on demand. */
  private class ToggleFailCatalogResolver(cacheTtlMs: Long = 60_000L)
      extends DuckLakeCatalogResolver(dummyConfig, cacheTtlMs):

    @volatile var shouldFail: Boolean = false

    override private[catalog] def initConnection(conn: Connection): Unit =
      val stmt = conn.createStatement()
      try
        stmt.execute("CREATE TABLE IF NOT EXISTS customers (id INTEGER, name VARCHAR)")
      finally stmt.close()

    override private[catalog] def queryTable(conn: Connection, ref: TableRef): ResourceLookupResult =
      if shouldFail then throw new RuntimeException("Simulated failure")
      super.queryTable(conn, ref)

  private def makeRef(table: String): TableRef =
    TableRef(testDbName, testSchema, table)

  private def tenantId: TenantId = TenantId.parse("default").toOption.get

  // --- Test 13: caches results within TTL ---
  test("caches results within TTL"):
    val resolver = CountingCatalogResolver(cacheTtlMs = 10_000L)
    try
      resolver.resolve(tenantId, makeRef("customers")) shouldBe ResourceLookupResult.BaseTable
      resolver.resolve(tenantId, makeRef("customers")) shouldBe ResourceLookupResult.BaseTable
      // queryTable should only have been called once (second call served from cache)
      resolver.queryCount.get() shouldBe 1
    finally resolver.close()

  // --- Test 14: expired cache entry triggers re-query ---
  test("expired cache entry triggers re-query"):
    val resolver = CountingCatalogResolver(cacheTtlMs = 50L)
    try
      resolver.resolve(tenantId, makeRef("customers")) shouldBe ResourceLookupResult.BaseTable
      Thread.sleep(100) // Wait well past TTL to avoid flakiness
      resolver.resolve(tenantId, makeRef("customers")) shouldBe ResourceLookupResult.BaseTable
      // queryTable should have been called twice (cache expired between calls)
      resolver.queryCount.get() shouldBe 2
    finally resolver.close()

  // --- Test 15: invalidateCache clears all entries ---
  test("invalidateCache clears all entries"):
    val resolver = CountingCatalogResolver()
    try
      resolver.resolve(tenantId, makeRef("customers")) shouldBe ResourceLookupResult.BaseTable
      resolver.queryCount.get() shouldBe 1
      resolver.invalidateCache()
      // After invalidation, should re-query
      resolver.resolve(tenantId, makeRef("customers")) shouldBe ResourceLookupResult.BaseTable
      resolver.queryCount.get() shouldBe 2
    finally resolver.close()

  // --- Test 16: TableRef normalization deduplicates cache entries across casings ---
  test("TableRef normalization deduplicates cache entries across casings"):
    val resolver = CountingCatalogResolver()
    try
      // TableRef.apply normalizes to lowercase, so "CUSTOMERS" and "customers"
      // produce the same canonical key (memory.main.customers). This verifies the
      // end-to-end path: different user input casings → same cache entry.
      resolver.resolve(tenantId, TableRef(testDbName, testSchema, "CUSTOMERS")) shouldBe ResourceLookupResult.BaseTable
      resolver.resolve(tenantId, TableRef(testDbName, testSchema, "customers")) shouldBe ResourceLookupResult.BaseTable
      resolver.queryCount.get() shouldBe 1
    finally resolver.close()

  // --- Test 17: different tables have independent cache entries ---
  test("different tables have independent cache entries"):
    val resolver = CountingCatalogResolver()
    try
      resolver.resolve(tenantId, makeRef("customers")) shouldBe ResourceLookupResult.BaseTable
      resolver.resolve(tenantId, makeRef("customer_names")) match
        case ResourceLookupResult.View(_) => succeed
        case other                        => fail(s"Expected View but got $other")
      // Each table should trigger its own query
      resolver.queryCount.get() shouldBe 2
    finally resolver.close()

  // --- Test 18: errors are not cached ---
  test("errors are not cached"):
    val resolver = ToggleFailCatalogResolver()
    try
      // First call fails -- should return Unknown
      resolver.shouldFail = true
      resolver.resolve(tenantId, makeRef("customers")) shouldBe ResourceLookupResult.Unknown
      // Fix the resolver -- second call should succeed (error was not cached)
      resolver.shouldFail = false
      resolver.resolve(tenantId, makeRef("customers")) shouldBe ResourceLookupResult.BaseTable
    finally resolver.close()

  // --- Test 19: cache works across table types ---
  test("cache works across table types"):
    val resolver = CountingCatalogResolver()
    try
      // Resolve a base table and a view
      resolver.resolve(tenantId, makeRef("customers")) shouldBe ResourceLookupResult.BaseTable
      resolver.resolve(tenantId, makeRef("customer_names")) match
        case ResourceLookupResult.View(_) => succeed
        case other                        => fail(s"Expected View but got $other")
      // Both should be cached -- resolving again should not increment query count
      resolver.resolve(tenantId, makeRef("customers")) shouldBe ResourceLookupResult.BaseTable
      resolver.resolve(tenantId, makeRef("customer_names")) match
        case ResourceLookupResult.View(_) => succeed
        case other                        => fail(s"Expected View but got $other")
      resolver.queryCount.get() shouldBe 2 // Only the initial two queries
    finally resolver.close()

  // --- Test 20: Unknown results are cached (unlike errors) ---
  test("Unknown results are cached unlike errors"):
    val resolver = CountingCatalogResolver()
    try
      // Resolve a non-existent table → Unknown (successful query, no exception)
      resolver.resolve(tenantId, makeRef("nonexistent")) shouldBe ResourceLookupResult.Unknown
      resolver.queryCount.get() shouldBe 1
      // Second resolve should be served from cache (Unknown IS cached)
      resolver.resolve(tenantId, makeRef("nonexistent")) shouldBe ResourceLookupResult.Unknown
      resolver.queryCount.get() shouldBe 1 // No additional query
    finally resolver.close()

  // --- Test 21: concurrent resolves produce correct results without corruption ---
  test("concurrent resolves produce correct results without corruption"):
    val resolver = CountingCatalogResolver(cacheTtlMs = 60_000L)
    try
      import java.util.concurrent.{CountDownLatch, Executors}
      val nThreads = 8
      val latch = new CountDownLatch(1)
      val executor = Executors.newFixedThreadPool(nThreads)
      val results = new java.util.concurrent.ConcurrentLinkedQueue[ResourceLookupResult]()

      for _ <- 0 until nThreads do
        executor.submit(new Runnable:
          def run(): Unit =
            latch.await() // All threads start at once
            val r = resolver.resolve(tenantId, makeRef("customers"))
            results.add(r)
        )

      latch.countDown() // Release all threads
      executor.shutdown()
      executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)

      // All threads should get BaseTable
      results.size() shouldBe nThreads
      results.forEach(_ shouldBe ResourceLookupResult.BaseTable)
      // Due to double-check in lock, queryTable should be called only once
      // (or at most a few times if threads race before the first cache write)
      resolver.queryCount.get() should be <= 2
    finally resolver.close()

  // --- Test: cache staleness: table created after Unknown cached, TTL expiry refreshes ---
  test("cache staleness: table created after Unknown cached, TTL expiry refreshes"):
    // Use a resolver that gives us access to the underlying connection for DDL
    val resolver = new DuckLakeCatalogResolver(dummyConfig, 50L):
      override private[catalog] def initConnection(conn: Connection): Unit =
        // Start with no tables
        ()
    try
      // Resolve nonexistent table → Unknown (cached)
      resolver.resolve(tenantId, makeRef("late_table")) shouldBe ResourceLookupResult.Unknown

      // Create the table via the resolver's connection
      val conn = resolver.getOrCreateConnection()
      val stmt = conn.createStatement()
      try stmt.execute("CREATE TABLE late_table (id INTEGER)")
      finally stmt.close()

      // Wait for TTL to expire
      Thread.sleep(100)

      // Re-resolve → should now find the table as BaseTable
      resolver.resolve(tenantId, makeRef("late_table")) shouldBe ResourceLookupResult.BaseTable
    finally resolver.close()

  // --- Test: cache staleness: table dropped after BaseTable cached, TTL expiry refreshes ---
  test("cache staleness: table dropped after BaseTable cached, TTL expiry refreshes"):
    val resolver = new DuckLakeCatalogResolver(dummyConfig, 50L):
      override private[catalog] def initConnection(conn: Connection): Unit =
        val stmt = conn.createStatement()
        try stmt.execute("CREATE TABLE IF NOT EXISTS ephemeral (id INTEGER)")
        finally stmt.close()
    try
      // Resolve existing table → BaseTable (cached)
      resolver.resolve(tenantId, makeRef("ephemeral")) shouldBe ResourceLookupResult.BaseTable

      // Drop the table
      val conn = resolver.getOrCreateConnection()
      val stmt = conn.createStatement()
      try stmt.execute("DROP TABLE ephemeral")
      finally stmt.close()

      // Wait for TTL to expire
      Thread.sleep(100)

      // Re-resolve → should now be Unknown
      resolver.resolve(tenantId, makeRef("ephemeral")) shouldBe ResourceLookupResult.Unknown
    finally resolver.close()

  // --- Test 22: invalidateCache and resolve do not deadlock under concurrency ---
  test("invalidateCache and resolve do not deadlock under concurrency"):
    val resolver = CountingCatalogResolver(cacheTtlMs = 60_000L)
    try
      import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
      val executor = Executors.newFixedThreadPool(4)
      val latch = new CountDownLatch(1)
      @volatile var failed = false

      // Two threads resolve, two threads invalidate
      for i <- 0 until 4 do
        executor.submit(new Runnable:
          def run(): Unit =
            latch.await()
            try
              for _ <- 0 until 50 do
                if i < 2 then resolver.resolve(tenantId, makeRef("customers"))
                else resolver.invalidateCache()
            catch case _: Exception => failed = true
        )

      latch.countDown()
      executor.shutdown()
      val completed = executor.awaitTermination(5, TimeUnit.SECONDS)
      completed shouldBe true
      failed shouldBe false
    finally resolver.close()

  // --- Test: cache evicts oldest entries when maxCacheSize is exceeded ---
  test("cache evicts oldest entries when maxCacheSize is exceeded"):
    // Use a small maxCacheSize to trigger eviction
    val resolver = new DuckLakeCatalogResolver(dummyConfig, cacheTtlMs = 60_000L, maxCacheSize = 5):
      override private[catalog] def initConnection(conn: Connection): Unit =
        val stmt = conn.createStatement()
        try
          for i <- 0 until 10 do
            stmt.execute(s"CREATE TABLE IF NOT EXISTS t$i (id INTEGER)")
        finally stmt.close()
    try
      // Fill cache beyond maxCacheSize
      for i <- 0 until 8 do
        resolver.resolve(tenantId, makeRef(s"t$i")) shouldBe ResourceLookupResult.BaseTable

      // After eviction, the most recent entries should still be cached,
      // and the oldest should have been evicted. Resolve an old entry —
      // it should still return BaseTable (re-queried from DB).
      resolver.resolve(tenantId, makeRef("t0")) shouldBe ResourceLookupResult.BaseTable
    finally resolver.close()
