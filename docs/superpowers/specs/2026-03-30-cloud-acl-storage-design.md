# Cloud ACL Storage Backends — Design Spec

## Problem

The ACL subsystem only supports local filesystem storage (`LocalAclStore`). When `ACL_BASE_PATH` is set to a cloud URI (`s3://`, `gs://`, `az://`), the proxy fails at startup with `UnsupportedOperationException`. Users need to store ACL files in S3, GCS, or Azure Blob Storage for production deployments.

## Goals

- Support S3, GCS, and Azure Blob Storage as ACL file backends
- Auto-detect credentials (AWS default chain, GCP ADC, Azure DefaultCredential) with optional explicit configuration
- Fail fast at startup if credentials are invalid
- Properly close cloud SDK clients on shutdown
- Maintain full backward compatibility with local filesystem

## Non-Goals

- Cloud-native push-based change detection (SNS/Pub-Sub/EventGrid) — polling via `PollingChangeDetector` is sufficient
- Sub-module separation for cloud SDKs (accepted bloat for now)
- SFTP or Box backends

## Architecture

### Current State

```
AclStoreFactory.create(config)
  ├── StoreBackend.Local  → LocalAclStore + LocalChangeDetector     ✅
  ├── StoreBackend.S3     → UnsupportedOperationException           ❌
  ├── StoreBackend.Gcs    → UnsupportedOperationException           ❌
  └── StoreBackend.Azure  → UnsupportedOperationException           ❌
```

### Target State

```
AclStoreFactory.create(config)
  ├── StoreBackend.Local  → LocalAclStore + LocalChangeDetector     ✅ (unchanged)
  ├── StoreBackend.S3     → CloudAclStore[S3Blob] + PollingChangeDetector
  ├── StoreBackend.Gcs    → CloudAclStore[GcsBlob] + PollingChangeDetector
  └── StoreBackend.Azure  → CloudAclStore[AzureBlob] + PollingChangeDetector
```

### Key Design Decisions

#### 1. New `CloudAclStore[B <: FsObject]` instead of refactoring `BlobAclStore`

**Why:** The existing `BlobAclStore` is tightly coupled to `FileStore[IO]` and `java.nio.file.Path`. It uses NIO directly in `tenantExists` and `listTenants`. Refactoring it to work with both local and cloud would mix concerns. Instead:

- `BlobAclStore` (local fs2-blobstore) stays as-is for backward compatibility
- New `CloudAclStore[B <: FsObject]` accepts `Store[IO, B]` + `Url.Plain` (base URL)
- All operations go through the blobstore `Store` trait — no NIO

#### 2. Type parameter `B <: FsObject` (not wildcard `?`)

**Why (Blocker #1 from review):** `Store[IO, B]` returns `Stream[IO, Url[B]]`. To access `lastModified` on paths, we need `B <: FsObject` (the upper bound that `S3Blob`, `GcsBlob`, `AzureBlob` all extend). With a wildcard `?`, we lose this type information and can't call `path.lastModified`.

#### 3. Resource cleanup via `closeable` callback

**Why (Blocker #2 from review):** Cloud SDK clients (`S3AsyncClient`, `Storage`, `BlobServiceAsyncClient`) must be closed. `CloudAclStore` takes an `onClose: () => Unit` callback that the factory method sets to close the SDK client + IORuntime.

#### 4. Tenant existence = "at least one object with tenant prefix"

**Why (HIGH #4 from review):** Object stores have no directories. A tenant "exists" if `store.list(tenantUrl).take(1)` emits at least one element. This means an empty tenant is treated as non-existent — acceptable since a tenant without ACL files has no effect.

#### 5. Startup health check

**Why (MEDIUM #8 from review):** After creating the store, `AclStoreFactory` calls `store.list(baseUrl).take(1)` to validate credentials before returning. This fails fast with a clear error instead of silently failing at the first polling cycle.

## Components

### `CloudAclStore[B <: FsObject]`

New class in `ai.starlake.acl.store`.

```scala
class CloudAclStore[B <: FsObject](
    store: Store[IO, B],
    baseUrl: Url.Plain,       // e.g. Url("s3", Authority.unsafe("my-bucket"), Path("acl/"))
    runtime: IORuntime,
    onClose: () => Unit       // closes SDK client + runtime
) extends AclStore
```

**Method implementations:**

| Method | Implementation |
|--------|---------------|
| `tenantExists` | `store.list(tenantUrl, recursive=false).take(1).compile.last` — `Some` = exists |
| `listYamlFiles` | `store.list(tenantUrl, recursive=false)` → filter `isYamlFile` on `fileName` |
| `readFile` | `store.get(fileUrl, 8192).through(fs2.text.utf8.decode).compile.string` |
| `listTenants` | `store.list(baseUrl, recursive=false)` → filter `isDir`, extract name, parse TenantId |
| `listYamlFilesWithMetadata` | `store.list(tenantUrl, recursive=false)` → extract `fileName` + `lastModified` from `Path[B]` (available because `B <: FsObject`) |
| `close` | Calls `onClose()` |

**URL construction helpers:**

```scala
private def tenantUrl(tenantId: TenantId): Url.Plain =
  baseUrl.copy(path = baseUrl.path / tenantId.canonical)

private def fileUrl(tenantId: TenantId, fileKey: String): Url.Plain =
  baseUrl.copy(path = baseUrl.path / tenantId.canonical / fileKey)
```

**Note on `listYamlFilesWithMetadata`:** The review (MEDIUM #9) identified that the current `BlobAclStore` incorrectly uses `stat()` in `evalMap`. The new `CloudAclStore` avoids this by using `list()` directly — `list()` returns `Url[B]` where `B <: FsObject`, so `path.lastModified` is available without a separate `stat()` call. This works because S3/GCS/Azure list operations include last-modified metadata.

### `CloudAclStore` companion object — Factory methods

```scala
object CloudAclStore:
  def forS3(bucket: String, prefix: String, config: AclS3Config): CloudAclStore[S3Blob]
  def forGcs(bucket: String, prefix: String, config: AclGcsConfig): CloudAclStore[GcsBlob]
  def forAzure(container: String, prefix: String, config: AclAzureConfig): CloudAclStore[AzureBlob]
```

Each factory method:
1. Creates the SDK client with explicit config or auto-detection
2. Creates a dedicated `IORuntime` (same pattern as `BlobAclStore.forLocalFs`)
3. Creates the `Store[IO, B]` via the builder's `.unsafe` method
4. Constructs the base `Url.Plain` with appropriate scheme (`s3`, `gs`, `https`) and authority (bucket/container)
5. Returns `CloudAclStore` with `onClose` that closes both the SDK client and the runtime

#### S3 Client Creation

```scala
val builder = S3AsyncClient.builder()
config.region.foreach(r => builder.region(Region.of(r)))
config.credentialsFile.foreach { path =>
  builder.credentialsProvider(
    ProfileCredentialsProvider.builder()
      .profileFile(ProfileFile.builder().content(Paths.get(path)).build())
      .build()
  )
}
// If no explicit credentials, uses DefaultCredentialsProvider (env, ~/.aws, instance profile)
val client = builder.build()
```

#### GCS Client Creation

```scala
val optionsBuilder = StorageOptions.newBuilder()
config.projectId.foreach(optionsBuilder.setProjectId)
config.serviceAccountKeyFile.foreach { path =>
  optionsBuilder.setCredentials(
    ServiceAccountCredentials.fromStream(new FileInputStream(path))
  )
}
// If no explicit credentials, uses Application Default Credentials
val storage = optionsBuilder.build().getService
```

#### Azure Client Creation

```scala
val builder = new BlobServiceClientBuilder()
config.connectionString match
  case Some(connStr) => builder.connectionString(connStr)
  case None => builder.credential(new DefaultAzureCredentialBuilder().build())
// Note: Azure requires the endpoint URL when using DefaultAzureCredential
// We derive it from the container name convention or require explicit connection string
val client = builder.buildAsyncClient()
```

### `AclStoreFactory` changes

Replace `createBlobStore` with actual cloud store creation:

```scala
case StoreBackend.S3(bucket, prefix) =>
  val store = CloudAclStore.forS3(bucket, prefix, config.s3)
  val detector = new PollingChangeDetector(store, config.watcher.pollIntervalMs)
  healthCheck(store, s"S3 s3://$bucket/$prefix")
  (store, detector)

case StoreBackend.Gcs(bucket, prefix) =>
  val store = CloudAclStore.forGcs(bucket, prefix, config.gcs)
  val detector = new PollingChangeDetector(store, config.watcher.pollIntervalMs)
  healthCheck(store, s"GCS gs://$bucket/$prefix")
  (store, detector)

case StoreBackend.Azure(container, prefix) =>
  val store = CloudAclStore.forAzure(container, prefix, config.azure)
  val detector = new PollingChangeDetector(store, config.watcher.pollIntervalMs)
  healthCheck(store, s"Azure az://$container/$prefix")
  (store, detector)
```

**Health check:**

```scala
private def healthCheck(store: AclStore, description: String): Unit =
  store.listTenants() match
    case Right(_) =>
      logger.info(s"ACL store health check passed: $description")
    case Left(err) =>
      throw new IllegalStateException(
        s"ACL store health check failed for $description: ${err.message}. " +
        "Check credentials and permissions."
      )
```

### `build.sbt` changes

Remove `% Optional` from blobstore cloud dependencies:

```scala
Dependencies.blobstoreS3,
Dependencies.blobstoreGcs,
Dependencies.blobstoreAzure
```

### `application.conf` changes

Uncomment the environment variable overrides in S3/GCS/Azure sections:

```hocon
s3 {
  region = ${?ACL_S3_REGION}
  credentials-file = ${?ACL_S3_CREDENTIALS_FILE}
}

gcs {
  project-id = ${?ACL_GCS_PROJECT_ID}
  service-account-key-file = ${?ACL_GCS_SERVICE_ACCOUNT_KEY_FILE}
}

azure {
  connection-string = ${?ACL_AZURE_CONNECTION_STRING}
}
```

## Threading Model

Same as existing `BlobAclStore.forLocalFs`:
- Dedicated `IORuntime` per store with `CachedThreadPool` (compute + blocking)
- `IO.unsafeRunSync()` bridges effectful operations to synchronous `AclStore` interface
- Cloud SDK clients use their own internal thread pools (Netty for S3/Azure, gRPC for GCS)
- No deadlock risk: `CachedThreadPool` grows unbounded, so blocking on SDK futures won't starve the pool

## Error Handling

| Scenario | Behavior |
|----------|----------|
| Invalid credentials at startup | `healthCheck` throws `IllegalStateException` — proxy fails to start |
| Credentials expire at runtime | `PollingChangeDetector` catches exception, sets `WatcherStatus.Retrying` |
| Network timeout on read | `readFile` returns `Left(StoreError(...))` |
| Non-existent tenant | `listYamlFiles` returns `Left(TenantNotFound(...))` via empty list check |
| Non-existent file | `readFile` returns `Left(StoreError(...))` — `get()` will throw |

## Test Strategy

1. **Unit tests for `CloudAclStore`**: Mock `Store[IO, B]` to test logic without cloud access
2. **`forLocalFs` unchanged**: Existing `BlobAclStoreTest` continues to pass
3. **URL construction tests**: Verify correct scheme/authority/path for each backend
4. **Factory method tests**: Verify config → SDK client creation (can mock at SDK level)
5. **Integration tests (optional, not in initial scope)**: LocalStack (S3), fake-gcs-server (GCS), Azurite (Azure) via Testcontainers

## Files Changed

| File | Change |
|------|--------|
| `src/main/scala/ai/starlake/acl/store/CloudAclStore.scala` | **NEW** — Cloud backend implementation |
| `src/main/scala/ai/starlake/acl/store/AclStoreFactory.scala` | Wire cloud backends + health check |
| `src/main/resources/application.conf` | Uncomment S3/GCS/Azure env vars |
| `build.sbt` | Remove `% Optional` from cloud deps |
| `src/test/scala/ai/starlake/acl/store/CloudAclStoreTest.scala` | **NEW** — Unit tests with mocked Store |

## Risks Accepted

- **Dependency bloat (~100+ MB)**: Accepted for now. Future work: split into sub-modules.
- **N+1 API calls per polling cycle**: Acceptable for < 100 tenants. Future work: recursive list + client-side partitioning.
- **`az://` non-standard scheme**: Documented in configuration. Could add `wasbs://` alias later.
- **Empty tenant = non-existent**: Documented convention. No impact since tenants without YAML files have no ACL effect.
