# Product Requirements Document: Gizmo On-Demand

**Version**: 1.0
**Last Updated**: 2026-03-18
**Status**: Living Document

---

## 1. Overview

### 1.1 Product Summary

Gizmo On-Demand is a multi-tenant process management and proxy platform that provides on-demand, per-user GizmoSQL database instances exposed through Apache Arrow Flight SQL. It enables organizations to dynamically provision isolated database proxy instances for individual users or workloads, with support for both local process and Kubernetes-based deployments.

### 1.2 Problem Statement

Organizations using GizmoSQL need to:

- Provide isolated database access to multiple concurrent users without cross-tenant interference.
- Dynamically spin up and tear down database proxy instances based on demand.
- Authenticate and authorize users at the proxy layer before queries reach the backend.
- Validate and restrict SQL statements per-user for security and compliance.
- Manage resource consumption through idle timeouts, port management, and process limits.
- Deploy to both development environments (local processes) and production environments (Kubernetes clusters).

### 1.3 Target Users

| User Persona | Description |
|---|---|
| **Platform Engineers** | Deploy and configure Gizmo On-Demand in local or Kubernetes environments. Manage infrastructure, scaling, and monitoring. |
| **Data Engineers / Analysts** | Connect to provisioned GizmoSQL instances via JDBC/ODBC/Flight SQL clients to query data lakes. |
| **Application Developers** | Integrate with the Process Manager REST API to programmatically provision and manage proxy instances. |
| **Administrators** | Configure security policies, SQL validation rules, and monitor running instances. |

---

## 2. Functional Requirements

### 2.1 Process Manager

The Process Manager is a REST API server responsible for the lifecycle management of GizmoSQL proxy instances.

#### FR-2.1.1 Process Lifecycle Management

| ID | Requirement | Priority |
|---|---|---|
| FR-2.1.1.1 | The system shall allow starting a new proxy instance by name with required environment variables (database credentials, project ID, data path, PostgreSQL connection details). | P0 |
| FR-2.1.1.2 | The system shall allow stopping a running proxy instance by name, cleaning up all associated resources (processes, ports, pods, services). | P0 |
| FR-2.1.1.3 | The system shall allow listing all running proxy instances with their status, port, host, and PID (if applicable). | P0 |
| FR-2.1.1.4 | The system shall allow stopping all running instances in a single operation. | P0 |
| FR-2.1.1.5 | The system shall prevent duplicate process names. Starting a process with an existing name shall return an error. | P0 |
| FR-2.1.1.6 | The system shall enforce a configurable maximum number of concurrent processes. | P1 |
| FR-2.1.1.7 | The system shall detect and handle unexpected process exits, cleaning up internal state and releasing resources. | P0 |
| FR-2.1.1.8 | The system shall validate that all required environment variables are provided before starting a process. | P0 |

#### FR-2.1.2 REST API

| ID | Requirement | Priority |
|---|---|---|
| FR-2.1.2.1 | `POST /api/process/start` - Start a new proxy instance with a JSON body containing process name, connection name, optional port, and environment arguments. | P0 |
| FR-2.1.2.2 | `POST /api/process/stop` - Stop a proxy instance by name. | P0 |
| FR-2.1.2.3 | `GET /api/process/list` - List all running proxy instances. | P0 |
| FR-2.1.2.4 | `POST /api/process/stopAll` - Stop all running proxy instances. | P0 |
| FR-2.1.2.5 | `GET /health` - Health check endpoint (no authentication required). | P0 |
| FR-2.1.2.6 | All API endpoints (except health) shall require an `X-API-Key` header when an API key is configured. | P0 |
| FR-2.1.2.7 | The API shall return structured JSON error responses with meaningful error messages. | P1 |

#### FR-2.1.3 Port Management (Local Backend)

| ID | Requirement | Priority |
|---|---|---|
| FR-2.1.3.1 | The system shall automatically allocate available port pairs (proxy port, backend port) from a configurable range. | P0 |
| FR-2.1.3.2 | Port pairs shall follow the convention: backend port = proxy port + 1000. | P1 |
| FR-2.1.3.3 | The system shall release ports when a process is stopped. | P0 |
| FR-2.1.3.4 | The system shall support requesting a specific port for a process. | P2 |

#### FR-2.1.4 Process Recovery (Kubernetes)

| ID | Requirement | Priority |
|---|---|---|
| FR-2.1.4.1 | On startup, the system shall discover existing proxy Pods labeled `managed-by=gizmo-process-manager` in the configured namespace. | P0 |
| FR-2.1.4.2 | Only Pods in `Running` phase with a matching Service shall be recovered. | P0 |
| FR-2.1.4.3 | Recovered processes shall be re-registered in the internal process map with their original environment variables reconstructed from the Pod spec. | P0 |
| FR-2.1.4.4 | Watches shall be re-established on recovered Pods to detect unexpected exits. | P0 |

### 2.2 FlightSQL Proxy

The proxy sits between clients and the GizmoSQL backend, providing authentication, authorization, SQL validation, and connection management.

#### FR-2.2.1 Protocol Support

| ID | Requirement | Priority |
|---|---|---|
| FR-2.2.1.1 | The proxy shall implement the Apache Arrow Flight SQL protocol. | P0 |
| FR-2.2.1.2 | The proxy shall support all standard Flight SQL operations: `getStream`, `getFlightInfo`, `listFlights`, `acceptPut`, `listActions`, `doAction`, `getSchema`. | P0 |
| FR-2.2.1.3 | The proxy shall support TLS termination with configurable certificate and key paths. | P0 |
| FR-2.2.1.4 | The proxy shall support insecure (non-TLS) mode for development. | P1 |

#### FR-2.2.2 Authentication

| ID | Requirement | Priority |
|---|---|---|
| FR-2.2.2.1 | The proxy shall support handshake-based authentication using `username:password` format. | P0 |
| FR-2.2.2.2 | The proxy shall support ODBC-style authentication (`username};PWD={password`). | P0 |
| FR-2.2.2.3 | The proxy shall support Basic authentication via `Authorization` header. | P0 |
| FR-2.2.2.4 | The proxy shall support Bearer token authentication using JWT tokens. | P0 |
| FR-2.2.2.5 | The proxy shall issue JWT tokens upon successful authentication with configurable expiration (default 1 hour). | P0 |
| FR-2.2.2.6 | JWT tokens shall contain claims: subject (username), role, authentication method, and session ID. | P1 |
| FR-2.2.2.7 | The proxy shall support default credentials for anonymous connections (e.g., JDBC clients that don't send auth headers). | P2 |

#### FR-2.2.3 SQL Validation

| ID | Requirement | Priority |
|---|---|---|
| FR-2.2.3.1 | The proxy shall validate SQL statements before forwarding to the backend. | P0 |
| FR-2.2.3.2 | The validation engine shall deny `DROP DATABASE` and `DROP TABLE` statements by default. | P0 |
| FR-2.2.3.3 | The validation engine shall allow `SELECT` statements by default. | P0 |
| FR-2.2.3.4 | The validation engine shall allow configuring bypass users who skip validation. | P1 |
| FR-2.2.3.5 | The validation engine shall support a configurable allow-by-default vs deny-by-default policy. | P1 |
| FR-2.2.3.6 | Metadata queries (catalog, table type, SQL info) shall bypass validation. | P0 |
| FR-2.2.3.7 | Validation shall be toggleable via configuration. | P1 |

#### FR-2.2.4 Per-User Connection Management

| ID | Requirement | Priority |
|---|---|---|
| FR-2.2.4.1 | The proxy shall maintain separate backend FlightClient connections per authenticated user. | P0 |
| FR-2.2.4.2 | Backend connections shall use the authenticated user's credentials. | P0 |
| FR-2.2.4.3 | Backend connections shall be cached and reused for subsequent requests from the same user. | P1 |

### 2.3 GizmoSQL Backend Management

#### FR-2.3.1 Backend Lifecycle

| ID | Requirement | Priority |
|---|---|---|
| FR-2.3.1.1 | The proxy shall automatically start a GizmoSQL backend server using a configurable startup script. | P0 |
| FR-2.3.1.2 | The proxy shall monitor the backend process and halt if the backend exits unexpectedly. | P0 |
| FR-2.3.1.3 | The proxy shall inject initialization SQL commands at backend startup (PostgreSQL metadata credentials, S3 secrets, DuckLake database configuration). | P0 |

#### FR-2.3.2 Idle Timeout

| ID | Requirement | Priority |
|---|---|---|
| FR-2.3.2.1 | The system shall support three idle timeout modes: disabled (< 0), timed (> 0 seconds), immediate (= 0). | P0 |
| FR-2.3.2.2 | In timed mode, the backend shall be stopped after no activity for the configured duration. | P0 |
| FR-2.3.2.3 | In immediate mode, the backend shall be stopped after each request completes. | P1 |
| FR-2.3.2.4 | When a backend has been stopped due to idle timeout, the next incoming request shall trigger an automatic restart. | P0 |
| FR-2.3.2.5 | Intentional stops (idle timeout, explicit stop) shall not cause the proxy to halt. | P0 |

### 2.4 Deployment Backends

#### FR-2.4.1 Local Process Backend

| ID | Requirement | Priority |
|---|---|---|
| FR-2.4.1.1 | The system shall spawn proxy instances as local OS processes via `ProcessBuilder`. | P0 |
| FR-2.4.1.2 | The system shall capture stdout/stderr of spawned processes and log them. | P1 |
| FR-2.4.1.3 | The system shall use `lsof` to detect and kill orphaned processes by port. | P1 |
| FR-2.4.1.4 | On stop-all, the system shall scan the entire managed port range for orphaned processes. | P1 |

#### FR-2.4.2 Kubernetes Backend

| ID | Requirement | Priority |
|---|---|---|
| FR-2.4.2.1 | The system shall create a Kubernetes Pod and Service for each proxy instance. | P0 |
| FR-2.4.2.2 | Pods shall be labeled with `managed-by=gizmo-process-manager` and `gizmo-instance=<name>` for discovery. | P0 |
| FR-2.4.2.3 | Services shall expose the proxy port and be reachable via internal DNS (`<name>.<namespace>.svc.cluster.local`). | P0 |
| FR-2.4.2.4 | The system shall support configurable Service types: `ClusterIP`, `NodePort`, `LoadBalancer`. | P0 |
| FR-2.4.2.5 | The system shall wait for Pod readiness with a configurable timeout before returning success. | P0 |
| FR-2.4.2.6 | Pods shall have restart policy `Never` to ensure clean lifecycle management. | P1 |
| FR-2.4.2.7 | The system shall support configurable image pull policies and image pull secrets. | P1 |
| FR-2.4.2.8 | The system shall support configurable service accounts for Pods. | P2 |
| FR-2.4.2.9 | The system shall support custom labels on Pods and Services. | P2 |
| FR-2.4.2.10 | On stop-all, the system shall delete all Pods and Services matching the `managed-by` label. | P0 |

---

## 3. Non-Functional Requirements

### 3.1 Performance

| ID | Requirement | Priority |
|---|---|---|
| NFR-3.1.1 | The Process Manager API shall respond to start/stop/list requests within 5 seconds (excluding Kubernetes Pod startup time). | P1 |
| NFR-3.1.2 | The FlightSQL proxy shall add minimal latency (< 10ms) to query forwarding. | P0 |
| NFR-3.1.3 | The system shall support up to the configured `max-processes` concurrent proxy instances without degradation. | P0 |

### 3.2 Reliability

| ID | Requirement | Priority |
|---|---|---|
| NFR-3.2.1 | The Process Manager shall recover its process registry from surviving Kubernetes Pods after a restart. | P0 |
| NFR-3.2.2 | Unexpected process exits shall be detected and cleaned up within seconds. | P0 |
| NFR-3.2.3 | Race conditions in concurrent start requests for the same process name shall be handled safely using atomic operations. | P0 |
| NFR-3.2.4 | The system shall perform graceful shutdown, cleaning up all backend resources (processes, K8s watches, client connections). | P0 |

### 3.3 Security

| ID | Requirement | Priority |
|---|---|---|
| NFR-3.3.1 | API key authentication shall be required for all management operations when configured. | P0 |
| NFR-3.3.2 | Passwords shall never be logged; they shall be masked as `***` in log output. | P0 |
| NFR-3.3.3 | TLS shall be enabled by default for proxy-client connections. | P0 |
| NFR-3.3.4 | JWT tokens shall be signed with HMAC-256 using a configurable secret key. | P0 |
| NFR-3.3.5 | SQL validation shall prevent destructive operations (DROP DATABASE, DROP TABLE) by default. | P0 |
| NFR-3.3.6 | Per-user isolation shall prevent one user's queries from being routed through another user's backend connection. | P0 |

### 3.4 Configurability

| ID | Requirement | Priority |
|---|---|---|
| NFR-3.4.1 | All configuration shall support environment variable overrides using the `${?VAR}` pattern. | P0 |
| NFR-3.4.2 | The system shall provide sensible defaults for all configuration values. | P0 |
| NFR-3.4.3 | Runtime backend selection (local vs Kubernetes) shall be configurable without code changes. | P0 |

### 3.5 Observability

| ID | Requirement | Priority |
|---|---|---|
| NFR-3.5.1 | The system shall log all process start/stop events with process name, port, and host. | P0 |
| NFR-3.5.2 | The system shall optionally log all SQL statements passing through the proxy. | P1 |
| NFR-3.5.3 | The system shall log validation decisions (allowed/denied) for auditing. | P1 |
| NFR-3.5.4 | The health endpoint shall be available without authentication for monitoring integration. | P0 |

### 3.6 Deployment

| ID | Requirement | Priority |
|---|---|---|
| NFR-3.6.1 | The system shall be packaged as a single Docker image containing both the Process Manager and the proxy scripts. | P0 |
| NFR-3.6.2 | The Docker image shall support multi-platform builds (linux/amd64, linux/arm64). | P1 |
| NFR-3.6.3 | The system shall support local development without Docker or Kubernetes. | P1 |
| NFR-3.6.4 | CI/CD shall automatically build and publish Docker images on push to main/develop and on version tags. | P1 |

---

## 4. Data Flow

### 4.1 Process Start Flow

1. Client sends `POST /api/process/start` with process name and environment arguments.
2. Process Manager validates the API key and required environment variables.
3. Backend allocates resources (ports for local, Pod+Service for K8s).
4. Proxy instance starts with injected environment variables.
5. Proxy starts its GizmoSQL backend subprocess with initialization SQL.
6. Process Manager registers the instance and returns host + port to the client.

### 4.2 Query Flow

1. Client authenticates via Flight SQL handshake or HTTP header (Basic/Bearer).
2. Proxy validates credentials and issues a JWT token.
3. Client sends query via `getFlightInfo`.
4. Proxy validates the SQL statement against the configured rules.
5. Proxy forwards the query to the GizmoSQL backend using the user's FlightClient connection.
6. Backend executes the query and returns results through the proxy to the client.

### 4.3 Idle Timeout Flow

1. Each Flight SQL operation records a timestamp in the GizmoServerManager.
2. A periodic scheduler checks elapsed time since last activity.
3. If elapsed time exceeds the threshold, the backend is intentionally stopped.
4. The next incoming request triggers `recordActivity()`, which detects the backend is stopped and restarts it.
5. The query is then processed normally.

---

## 5. Configuration Reference

### 5.1 Process Manager

| Variable | Default | Description |
|---|---|---|
| `SL_GIZMO_ON_DEMAND_HOST` | `0.0.0.0` | Host address to bind the server |
| `SL_GIZMO_ON_DEMAND_PORT` | `10900` | Port for the REST API |
| `SL_GIZMO_MIN_PORT` | `11900` | Minimum port for managed proxies |
| `SL_GIZMO_MAX_PORT` | `12000` | Maximum port for managed proxies |
| `SL_GIZMO_MAX_PROCESSES` | `10` | Maximum concurrent proxy instances |
| `SL_GIZMO_DEFAULT_SCRIPT` | `/opt/gizmosql/scripts/docker-start-sl-gizmosql.sh` | Backend startup script |
| `SL_GIZMO_PROXY_SCRIPT` | `/opt/gizmosql/scripts/docker-start-proxy.sh` | Proxy startup script |
| `SL_GIZMO_API_KEY` | `a_secret_api_key` | API key for authentication |
| `SL_GIZMO_IDLE_TIMEOUT` | `-1` | Idle timeout in seconds (-1 = disabled, 0 = immediate, >0 = seconds) |
| `SL_GIZMO_RUNTIME_TYPE` | `local` | Backend type: `local` or `kubernetes` |

### 5.2 Kubernetes

| Variable | Default | Description |
|---|---|---|
| `SL_GIZMO_K8S_NAMESPACE` | `default` | Kubernetes namespace |
| `SL_GIZMO_K8S_IMAGE` | `gizmo-proxy:latest` | Container image |
| `SL_GIZMO_K8S_SERVICE_ACCOUNT` | - | Service account name |
| `SL_GIZMO_K8S_PROXY_PORT` | `31338` | Fixed proxy port inside Pod |
| `SL_GIZMO_K8S_BACKEND_PORT` | `31337` | Fixed backend port inside Pod |
| `SL_GIZMO_K8S_SERVICE_TYPE` | `ClusterIP` | Service type: `ClusterIP`, `NodePort`, `LoadBalancer` |
| `SL_GIZMO_K8S_IMAGE_PULL_POLICY` | `IfNotPresent` | Image pull policy |
| `SL_GIZMO_K8S_STARTUP_TIMEOUT` | `120` | Pod startup timeout in seconds |

### 5.3 Proxy

| Variable | Default | Description |
|---|---|---|
| `PROXY_HOST` | `0.0.0.0` | Proxy bind address |
| `PROXY_PORT` | `31338` | Proxy listen port |
| `PROXY_TLS_ENABLED` | `true` | Enable TLS |
| `PROXY_TLS_CERT_CHAIN` | `gizmosql-proxy/certs/server-cert.pem` | TLS certificate path |
| `PROXY_TLS_PRIVATE_KEY` | `gizmosql-proxy/certs/server-key.pem` | TLS private key path |
| `GIZMO_SERVER_HOST` | `localhost` | Backend GizmoSQL host |
| `GIZMO_SERVER_PORT` | `31337` | Backend GizmoSQL port |
| `JWT_SECRET_KEY` | `a_very_secret_key` | JWT signing secret |
| `VALIDATION_ENABLED` | `true` | Enable SQL validation |
| `VALIDATION_ALLOW_BY_DEFAULT` | `true` | Allow unrecognized SQL by default |

### 5.4 Database / Session

| Variable | Default | Description |
|---|---|---|
| `GIZMOSQL_USERNAME` | - | GizmoSQL authentication username |
| `GIZMOSQL_PASSWORD` | - | GizmoSQL authentication password |
| `SL_DB_ID` | - | Starlake project / database identifier |
| `SL_DATA_PATH` | - | Data lake storage path |
| `PG_USERNAME` | - | PostgreSQL metadata username |
| `PG_PASSWORD` | - | PostgreSQL metadata password |
| `PG_HOST` | `host.docker.internal` | PostgreSQL host |
| `PG_PORT` | `5432` | PostgreSQL port |

---

## 6. Constraints and Assumptions

### 6.1 Constraints

- Local backend process handles cannot survive JVM restarts; only Kubernetes Pods support recovery.
- The local backend relies on `lsof` for orphaned process detection, limiting it to Unix-like systems.
- Port range is finite; the maximum number of concurrent local processes is bounded by `(maxPort - minPort)`.
- Kubernetes backend requires RBAC permissions to create/delete Pods and Services in the target namespace.

### 6.2 Assumptions

- The GizmoSQL backend binary (`gizmosql_server`) is available in the container image.
- PostgreSQL is available for metadata storage (DuckLake catalog).
- S3-compatible storage is available when AWS environment variables are configured.
- The Kubernetes API server is reachable when running in Kubernetes mode.
- Clients use Apache Arrow Flight SQL-compatible drivers (JDBC, ODBC, or native Flight).
