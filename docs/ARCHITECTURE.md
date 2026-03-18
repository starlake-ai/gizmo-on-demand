# Architecture Document: Gizmo On-Demand

**Version**: 1.0
**Last Updated**: 2026-03-18

---

## 1. System Overview

Gizmo On-Demand is composed of two cooperating subsystems:

1. **Process Manager** -- A REST API server that provisions and manages proxy instances.
2. **FlightSQL Proxy** -- A gRPC proxy that authenticates users, validates SQL, and forwards queries to a GizmoSQL backend.

Each proxy instance manages its own GizmoSQL backend subprocess, forming a self-contained stack per tenant/user session.

```
                                    +---------------------------+
                                    |    Process Manager        |
                                    |    (REST API :10900)      |
                                    +---------------------------+
                                    | POST /api/process/start   |
                                    | POST /api/process/stop    |
                                    | GET  /api/process/list    |
                                    | POST /api/process/stopAll |
                                    | GET  /health              |
                                    +-------------+-------------+
                                                  |
                                   Spawns N proxy instances
                                                  |
                       +----------+---------------+----------------+----------+
                       |          |                                 |          |
                       v          v                                 v          v
               +-------+------+  +-------+------+         +-------+------+
               | Proxy :11900 |  | Proxy :11901 |   ...   | Proxy :119XX |
               | (FlightSQL)  |  | (FlightSQL)  |         | (FlightSQL)  |
               +------+-------+  +------+-------+         +------+-------+
                      |                 |                         |
                      v                 v                         v
               +------+-------+  +------+-------+         +------+-------+
               | GizmoSQL     |  | GizmoSQL     |         | GizmoSQL     |
               | :12900       |  | :12901       |         | :129XX       |
               +--------------+  +--------------+         +--------------+
                      |                 |                         |
                      v                 v                         v
                +----------+     +----------+              +----------+
                | DuckLake |     | DuckLake |              | DuckLake |
                | (PG+S3)  |     | (PG+S3)  |              | (PG+S3)  |
                +----------+     +----------+              +----------+
```

---

## 2. Technology Stack

| Layer | Technology | Purpose |
|---|---|---|
| Language | Scala 3.7.4 | Application logic |
| Build | SBT 1.11.7 | Compilation and assembly |
| HTTP Server | JDK HTTP Server (via Tapir) | Process Manager REST API |
| API Framework | Tapir 1.11.10 | Typed endpoint definitions |
| Serialization | Circe 0.14.10 | JSON encoding/decoding |
| gRPC Transport | Apache Arrow Flight 14.0.1 | FlightSQL proxy protocol |
| gRPC Runtime | gRPC-Java 1.59.0 | Flight server/client |
| Kubernetes Client | Fabric8 7.1.0 | Pod/Service lifecycle management |
| Configuration | PureConfig 0.17.8 | Typesafe config loading |
| Authentication | Java-JWT 4.4.0 | JWT token generation/verification |
| SQL Parsing | JSQLParser 5.1 | SQL statement analysis for validation |
| Logging | Logback 1.5.12 + scala-logging | Structured logging |
| Container Runtime | Docker (multi-stage) | Packaging and deployment |
| Process Supervisor | tini | PID 1 init for containers |
| Base Image | gizmodata/gizmosql | Includes GizmoSQL server binary |

---

## 3. Component Architecture

### 3.1 Package Structure

```
ai.starlake.gizmo
├── ondemand                          # Process Manager subsystem
│   ├── Main.scala                    # Entry point, HTTP server setup
│   ├── ProcessManager.scala          # Process lifecycle orchestration
│   ├── ProcessEndpoints.scala        # Tapir REST endpoint definitions
│   ├── Models.scala                  # Request/Response DTOs
│   ├── EnvVars.scala                 # Configuration facade
│   ├── config/
│   │   ├── GizmoOnDemandConfig.scala # Process Manager config model
│   │   └── KubernetesConfig.scala    # K8s-specific config model
│   ├── backend/
│   │   ├── ProcessBackend.scala      # Strategy trait + data types
│   │   ├── LocalProcessBackend.scala # Local OS process spawning
│   │   └── KubernetesProcessBackend.scala  # K8s Pod/Service spawning
│   └── utils/
│       └── SQLUtils.scala            # SQL parsing utilities
│
└── proxy                             # FlightSQL Proxy subsystem
    ├── ProxyServer.scala             # Proxy entry point + init SQL
    ├── config/
    │   └── ProxyConfig.scala         # Proxy configuration models
    ├── flight/
    │   └── FlightSqlProxy.scala      # Flight SQL producer + auth
    ├── gizmoserver/
    │   └── GizmoServerManager.scala  # Backend process lifecycle + idle timeout
    └── validation/
        └── StatementValidator.scala  # SQL statement security validation
```

### 3.2 Process Manager Components

#### 3.2.1 Main (Entry Point)

- Loads configuration and selects the runtime backend (`local` or `kubernetes`).
- Registers a JVM shutdown hook for resource cleanup.
- Creates the `ProcessManager` and wires Tapir endpoints.
- Starts the JDK HTTP server.

#### 3.2.2 ProcessManager

The central orchestrator. Maintains a `TrieMap[String, ManagedProcess]` tracking all active proxy instances.

**Responsibilities:**
- Validates requests (required env vars, max process limit, duplicate names).
- Delegates spawning to the selected `ProcessBackend`.
- Handles race conditions via `putIfAbsent` on the concurrent map.
- Registers `onExit` callbacks for unexpected termination cleanup.
- On startup, calls `backend.discoverExisting()` to recover K8s processes from a previous lifecycle.

**ManagedProcess record:**
```
ManagedProcess(
  name: String,         -- Logical identifier
  port: Int,            -- Proxy port
  backendPort: Int,     -- GizmoSQL backend port
  handle: ProcessHandle,-- Local process or K8s pod/service handle
  host: String,         -- Accessible host (127.0.0.1 or K8s DNS)
  arguments: Map        -- Environment variables passed at creation
)
```

#### 3.2.3 ProcessBackend (Strategy Pattern)

Abstracts the runtime environment:

```
                   ProcessBackend (trait)
                   /                   \
    LocalProcessBackend       KubernetesProcessBackend
```

**Trait contract:**
- `start(name, envVars, proxyPort, backendPort, onExit)` -- Spawn an instance.
- `stop(handle)` -- Terminate an instance.
- `isAlive(handle)` -- Check liveness.
- `stopAll()` -- Stop all managed instances.
- `discoverExisting(onExitFactory)` -- Recover processes from previous lifecycle.
- `cleanup()` -- Release resources (clients, watches).

**ProcessHandle hierarchy:**
- `LocalProcessHandle(process: java.lang.Process)` -- Wraps an OS process. Provides PID.
- `K8sProcessHandle(podName, serviceName, namespace)` -- Wraps K8s resource names. No PID.

### 3.3 FlightSQL Proxy Components

#### 3.3.1 ProxyServer (Entry Point)

Bootstraps the proxy:
1. Parses configuration from `application.conf` and environment variables.
2. Constructs initialization SQL commands:
   - PostgreSQL metadata catalog credentials.
   - S3 secrets (if AWS variables are set, detecting endpoint style and SSL).
   - DuckLake `ATTACH` and `USE` commands.
3. Starts the `GizmoServerManager` (backend subprocess).
4. Creates the `FlightSqlProxy` producer.
5. Configures TLS or insecure mode.
6. Sets up authentication middleware and starts the Flight server.

#### 3.3.2 FlightSqlProxy (Flight Producer)

Implements `NoOpFlightSqlProducer` to proxy all Flight SQL operations to a GizmoSQL backend.

**Per-user connection management:**
- A `ConcurrentHashMap[String, FlightClient]` maps usernames to backend connections.
- Backend clients are created lazily on first request and cached.
- Each client authenticates to the backend using the original user credentials.

**Authentication pipeline:**

```
Client Request
      |
      v
+-----+-----------+
| Auth Middleware   | -- Extracts credentials from:
| (gRPC Interceptor)|   - Basic auth header (base64 username:password)
+-----+-----------+    - Bearer token (JWT verification)
      |                 - ODBC format (username};PWD={password)
      v                 - Handshake negotiation
+-----+-----------+
| JWT Generation   | -- Issues signed JWT with claims:
|                  |    sub, role, auth_method, session_id
+-----+-----------+    Expiry: 1 hour, Algorithm: HMAC-256
      |
      v
+-----+-----------+
| ThreadLocal      | -- Stores currentUsername and currentClaims
| Context          |    for the duration of the request
+------------------+
```

**Statement validation:**
- Occurs in `getFlightInfo` before forwarding to the backend.
- Metadata queries (catalog listing, type info) bypass validation.
- The `StatementValidator` is consulted for all data queries.

**Activity tracking:**
- Every Flight operation calls `gizmoManager.recordActivity()`.
- The `onRequestComplete` callback supports immediate shutdown mode.

#### 3.3.3 GizmoServerManager (Backend Lifecycle)

Manages the GizmoSQL backend subprocess spawned by the proxy.

**State machine:**

```
                  start()
    STOPPED ────────────────> RUNNING
       ^                         |
       |   checkIdleTimeout()    |
       |   or onRequestComplete()|
       +─────────────────────────+
       |                         |
       |     recordActivity()    |
       +─────────────────────────+
              (auto-restart)

    RUNNING ── unexpected exit ──> HALTED (proxy terminates)
```

**Idle timeout modes:**

| Mode | Config Value | Behavior |
|---|---|---|
| Disabled | `< 0` | Backend runs indefinitely |
| Timed | `> 0` | Backend stopped after N seconds of inactivity, restarted on next activity |
| Immediate | `= 0` | Backend stopped after each request completes, restarted on next request |

**Key behaviors:**
- Backend is started with `ProcessBuilder`, executing the configured `defaultGizmoScript`.
- Environment variables include `INIT_SQL_COMMANDS` for database initialization.
- A 5-second wait after startup confirms the process is alive.
- A periodic scheduler (every `idleTimeout / 2` seconds) checks for idle timeout.
- `recordActivity()` detects a stopped backend and calls `startInternal()` to restart.
- A flag (`intentionalStop`) distinguishes idle shutdown from crashes. Only crashes trigger `Runtime.halt(1)`.

#### 3.3.4 StatementValidator

Validates SQL statements for security.

**Decision tree:**

```
Statement received
      |
      +-- Validation disabled? ──> ALLOW
      |
      +-- User in bypass list? ──> ALLOW
      |
      +-- DROP DATABASE/TABLE? ──> DENY
      |
      +-- SELECT? ──> ALLOW
      |
      +-- INSERT? ──> ALLOW
      |
      +-- UPDATE with WHERE? ──> ALLOW
      |
      +-- allowByDefault? ──> ALLOW / DENY
```

---

## 4. Deployment Architecture

### 4.1 Local Mode

```
+----------------------------------------------------------+
|  Host Machine                                            |
|                                                          |
|  +----------------------------------------------------+  |
|  | Process Manager (:10900)                           |  |
|  |                                                    |  |
|  |  ProcessManager + LocalProcessBackend              |  |
|  +----------------------------------------------------+  |
|           |                        |                      |
|  +--------v-------+      +--------v-------+              |
|  | OS Process      |      | OS Process      |            |
|  | Proxy (:11900)  |      | Proxy (:11901)  |            |
|  | Backend (:12900)|      | Backend (:12901)|            |
|  +-----------------+      +-----------------+            |
+----------------------------------------------------------+
```

**Port allocation:** The `LocalProcessBackend` maintains a `TrieMap[Int, Boolean]` of reserved ports. Ports are allocated sequentially from `minPort` to `maxPort`. Backend ports = proxy port + 1000.

**Process monitoring:** `process.onExit().thenAccept` registers a JVM callback. On unexpected exit, the `onExit` callback removes the process from the registry and uses `lsof` to kill any orphaned backend by port.

### 4.2 Kubernetes Mode

```
+-------------------------------------------------------------------+
| Kubernetes Cluster                                                |
|                                                                   |
|  +---------------------------------------------------------------+|
|  | Namespace (configurable, default: "default")                  ||
|  |                                                               ||
|  |  +----------------------------+                               ||
|  |  | Pod: process-manager       |                               ||
|  |  | Process Manager (:10900)   |                               ||
|  |  | KubernetesProcessBackend   |                               ||
|  |  +----------------------------+                               ||
|  |              |                                                ||
|  |     Creates Pods + Services                                   ||
|  |              |                                                ||
|  |  +-----------v------------+   +-----------v------------+      ||
|  |  | Pod: gizmo-proxy-foo   |   | Pod: gizmo-proxy-bar   |     ||
|  |  | Labels:                |   | Labels:                |     ||
|  |  |  managed-by: gizmo-...|   |  managed-by: gizmo-...|     ||
|  |  |  gizmo-instance: foo  |   |  gizmo-instance: bar  |     ||
|  |  |                        |   |                        |     ||
|  |  | Container:             |   | Container:             |     ||
|  |  |  Proxy (:31338)       |   |  Proxy (:31338)       |     ||
|  |  |  Backend (:31337)     |   |  Backend (:31337)     |     ||
|  |  +------------------------+   +------------------------+     ||
|  |              |                           |                    ||
|  |  +-----------v------------+   +-----------v------------+      ||
|  |  | Service:               |   | Service:               |     ||
|  |  |  gizmo-proxy-foo      |   |  gizmo-proxy-bar      |     ||
|  |  |  Type: ClusterIP      |   |  Type: ClusterIP      |     ||
|  |  |  Port: 31338          |   |  Port: 31338          |     ||
|  |  +------------------------+   +------------------------+     ||
|  |                                                               ||
|  |  DNS:                                                         ||
|  |    gizmo-proxy-foo.default.svc.cluster.local:31338           ||
|  |    gizmo-proxy-bar.default.svc.cluster.local:31338           ||
|  +---------------------------------------------------------------+|
+-------------------------------------------------------------------+
```

**Pod specification:**
- Single container running the gizmo-proxy image.
- Environment variables injected for credentials and configuration.
- Restart policy: `Never` (ProcessManager handles lifecycle).
- Optional: service account, image pull secrets, custom labels.

**Service specification:**
- Selector: `gizmo-instance=<name>`.
- Configurable type: `ClusterIP` (cluster-internal, default), `NodePort`, or `LoadBalancer`.

**Pod watching:** The `KubernetesProcessBackend` uses the Fabric8 `Watcher` API to monitor each Pod. Events trigger cleanup when a Pod is deleted or enters a terminal phase (`Failed`, `Succeeded`).

**Process recovery on startup:**
1. List all Pods with label `managed-by=gizmo-process-manager`.
2. Filter to Pods in `Running` phase.
3. Verify a matching Service exists for each Pod.
4. Extract environment variables from the Pod spec container.
5. Filter out internal env vars (`PROXY_PORT`, `PROXY_HOST`, `GIZMO_SERVER_HOST`, `GIZMO_SERVER_PORT`).
6. Re-establish a Watcher on each recovered Pod.
7. Register each as a `ManagedProcess` in the ProcessManager.

---

## 5. Authentication and Security Architecture

### 5.1 Two-Layer Authentication

```
Layer 1: Process Manager API
+--------------------------------------------+
| Client ──[X-API-Key header]──> REST API    |
| Shared secret authentication               |
| Protects: start, stop, list, stopAll       |
| Unauthenticated: /health                   |
+--------------------------------------------+

Layer 2: FlightSQL Proxy
+--------------------------------------------+
| Client ──[Handshake / Auth header]──> Proxy|
|                                            |
| Supports:                                  |
|   - Handshake: username:password           |
|   - Handshake: ODBC format                 |
|   - Header: Basic base64(user:pass)        |
|   - Header: Bearer <JWT>                   |
|                                            |
| Issues JWT on successful auth              |
| Per-user backend connection isolation      |
+--------------------------------------------+
```

### 5.2 JWT Token Structure

```json
{
  "iss": "gizmosql",
  "sub": "<username>",
  "role": "admin",
  "auth_method": "basic|handshake",
  "session_id": "<uuid>",
  "iat": <issued_at>,
  "exp": <issued_at + 3600>
}
```

- Algorithm: HMAC-256
- Secret: Configurable via `JWT_SECRET_KEY`
- Expiration: 1 hour from issuance

### 5.3 SQL Validation Pipeline

```
FlightSqlProxy.getFlightInfo()
      |
      v
Is metadata query? ──yes──> Forward directly
      |no
      v
StatementValidator.validate(context)
      |
      +──> Allowed ──> Forward to backend
      |
      +──> Denied(reason) ──> Return CallStatus.PERMISSION_DENIED
```

Validation context includes: username, statement text, peer address, and JWT claims.

---

## 6. Data Architecture

### 6.1 Backend Initialization

Each proxy instance initializes its GizmoSQL backend with SQL commands built at startup:

```sql
-- 1. PostgreSQL metadata catalog credentials
SET pg_username = '<PG_USERNAME>';
SET pg_password = '<PG_PASSWORD>';
SET pg_host     = '<PG_HOST>';
SET pg_port     = '<PG_PORT>';

-- 2. S3 secrets (if AWS env vars provided)
CREATE OR REPLACE PERSISTENT SECRET s3_secret (
    TYPE S3,
    KEY_ID '<AWS_KEY_ID>',
    SECRET '<AWS_SECRET>',
    REGION '<AWS_REGION>',
    ENDPOINT '<AWS_ENDPOINT>',
    URL_STYLE '<path|vhost>',
    USE_SSL <true|false>
);

-- 3. DuckLake database attachment
ATTACH '<SL_DB_ID>' AS ducklake (TYPE DUCKLAKE, ...);
USE ducklake;
```

### 6.2 Connection Topology

```
Client (JDBC/ODBC/Flight)
      |
      | Arrow Flight SQL (gRPC, optional TLS)
      v
FlightSQL Proxy
      |
      | Arrow Flight (gRPC, optional TLS)
      v
GizmoSQL Backend (DuckDB-based)
      |
      +──> PostgreSQL (metadata catalog via DuckLake)
      |
      +──> S3-compatible storage (data lake files)
```

---

## 7. Concurrency Model

### 7.1 Thread Safety

| Component | Mechanism | Purpose |
|---|---|---|
| `ProcessManager.processes` | `TrieMap` | Lock-free concurrent access to process registry |
| `KubernetesProcessBackend.watches` | `TrieMap` | Lock-free concurrent watch management |
| `LocalProcessBackend.usedPorts` | `TrieMap` | Lock-free port reservation |
| `FlightSqlProxy.backendClients` | `ConcurrentHashMap` | Per-user client cache |
| `FlightSqlProxy.currentUsername` | `ThreadLocal` | Per-request context |
| `GizmoServerManager` | `synchronized` blocks | Backend start/stop atomicity |
| Process name registration | `putIfAbsent` | Race condition prevention for duplicate names |

### 7.2 Callback Model

Process exit notifications are asynchronous:
- **Local:** `process.onExit().thenAccept` triggers on the JVM's `ForkJoinPool`.
- **Kubernetes:** `Watcher.eventReceived` triggers on the Fabric8 client's I/O thread.

Both callbacks remove the process from the `TrieMap` and perform cleanup.

---

## 8. Configuration Architecture

### 8.1 Configuration Loading

```
application.conf (defaults)
      |
      v
Environment variables (${?VAR} overrides)
      |
      v
PureConfig reader (typesafe case class)
      |
      v
EnvVars singleton (facade)
```

Two top-level config sections:
- `gizmosql-proxy` -- Loaded by `ProxyConfig` for the FlightSQL proxy.
- `gizmo-on-demand` -- Loaded by `GizmoOnDemandConfig` for the Process Manager.

### 8.2 Configuration Model Hierarchy

```
GizmoOnDemandConfig
├── host, port
├── minPort, maxPort, maxProcesses
├── defaultGizmoScript, proxyScript
├── apiKey, idleTimeout, runtimeType
└── kubernetes: KubernetesConfig
    ├── namespace, imageName
    ├── serviceAccountName
    ├── proxyPort, backendPort
    ├── serviceType, imagePullPolicy
    ├── imagePullSecrets, labels
    └── startupTimeoutSeconds

GizmoSqlProxyConfig
├── proxy: ProxyServerConfig
│   ├── host, port
│   └── tls: ProxyTlsConfig
├── backend: BackendConfig
│   ├── host, port
│   ├── tls: BackendTlsConfig
│   └── defaultUsername, defaultPassword
├── validation: ValidationConfig
│   └── rules: ValidationRulesConfig
├── logging: LoggingConfig
└── session: SessionConfig
```

---

## 9. Docker Image Architecture

### 9.1 Multi-Stage Build

```
Stage 1: Builder (eclipse-temurin:17-jdk-jammy)
├── Install sbt
├── Cache dependencies (project/ copied first)
├── Copy source and compile
└── Output: target/scala-*/gizmo-on-demand-assembly-*.jar

Stage 2: Runtime (gizmodata/gizmosql:{VERSION})
├── Install lsof, Java 21 JRE, tini
├── Copy assembled JAR → /opt/gizmosql/manager/
├── Copy proxy startup script → /opt/gizmosql/scripts/
├── Copy backend startup script → /opt/gizmosql/scripts/
└── Entrypoint: tini → java -jar (with Arrow JVM options)
```

### 9.2 Exposed Ports

| Port | Service |
|---|---|
| 10900 | Process Manager REST API |
| 11900-12000 | Dynamic range for proxy instances (local mode) |

### 9.3 JVM Options

```
--add-opens=java.base/java.nio=ALL-UNNAMED    # Arrow memory access
-Dio.netty.tryReflectionSetAccessible=true     # Netty optimization
```

---

## 10. CI/CD Pipeline

```
Push to main/develop or tag v*
      |
      v
GitHub Actions: docker-publish.yml
      |
      +── Checkout code
      +── Setup Docker Buildx (multi-platform)
      +── Login to Docker Hub
      +── Read version from version.txt
      +── Determine image tags
      |     - Release tag (v*): {VERSION}, latest
      |     - Main/develop: {VERSION}, snapshot, latest-snapshot
      +── Build multi-platform image (amd64 + arm64)
      +── Push to starlakeai/gizmo-on-demand
      +── Generate release summary
```

---

## 11. Error Handling and Resilience

### 11.1 Failure Modes

| Failure | Detection | Response |
|---|---|---|
| Backend crash (unexpected) | `onExit` callback / Pod watcher | Remove from registry, release ports. Proxy calls `Runtime.halt(1)`. |
| Backend idle timeout | Periodic scheduler | Intentional stop. Restart on next activity. |
| Pod deletion (external) | K8s Watcher `DELETED` event | Remove from registry. |
| Pod failure | K8s Watcher `MODIFIED` + phase check | Remove from registry. |
| Process Manager restart | Startup discovery | Recover running K8s pods. Local processes are lost. |
| Port exhaustion | `claimPairedPorts` returns Left | Error returned to API caller. |
| Max processes reached | `processes.size >= maxProcesses` | Error returned to API caller. |
| Duplicate process name | `processes.contains(name)` | Error returned to API caller. |
| Race condition (concurrent start) | `putIfAbsent` returns `Some` | Second start is rejected; spawned process is stopped. |
| API key mismatch | Header validation | `ErrorResponse` returned. |
| SQL validation denial | `StatementValidator` | `CallStatus.PERMISSION_DENIED` via gRPC. |

### 11.2 Graceful Shutdown

```
SIGTERM / Ctrl+C
      |
      v
JVM Shutdown Hook
      |
      +── backend.cleanup()
      |     ├── Local: clear port registry
      |     └── K8s: close all watches, close K8s client
      |
      +── (Proxy) GizmoServerManager.stop()
      |     ├── Mark intentional stop
      |     ├── Destroy backend process
      |     └── Kill by port (lsof fallback)
      |
      +── (Proxy) Close backend FlightClients
      +── (Proxy) Shutdown Flight server
```

---

## 12. Key Design Decisions

| Decision | Rationale |
|---|---|
| **Strategy pattern for backends** | Allows the same ProcessManager code to work with local processes and Kubernetes, selectable at runtime via configuration. |
| **Fixed ports inside K8s Pods** | Each Pod is isolated; there is no port conflict. The Service provides external routing. Dynamic allocation is unnecessary. |
| **Paired ports (proxy + 1000)** | Simple convention avoids a second allocation pass and makes it easy to identify related processes. |
| **`TrieMap` over `synchronized HashMap`** | Lock-free reads with atomic `putIfAbsent` for safe concurrent access without contention. |
| **`Runtime.halt(1)` on unexpected backend exit** | A proxy without its backend cannot serve queries. Fast termination prevents clients from receiving misleading errors. The Process Manager detects this and cleans up. |
| **JWT over session cookies** | Flight SQL operates over gRPC, not HTTP. JWT tokens are self-contained and work naturally with Bearer token auth in gRPC metadata. |
| **Recovery via K8s labels** | Labels are the idiomatic Kubernetes mechanism for resource discovery. No external state store needed. |
| **Init SQL via environment variable** | Avoids file mounting complexity. The GizmoSQL backend reads `INIT_SQL_COMMANDS` at startup to configure catalogs and secrets. |
| **tini as PID 1** | Proper signal forwarding and zombie process reaping in containers. The JVM does not handle orphan processes well as PID 1. |
| **Restart policy `Never`** | The Process Manager owns the lifecycle. Kubernetes auto-restart would conflict with explicit start/stop semantics. |
