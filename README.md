# Gizmo On-Demand

A multi-tenant platform for provisioning on-demand GizmoSQL database instances exposed through Apache Arrow Flight SQL. Each user or workload gets an isolated proxy+backend stack with authentication, SQL validation, table-level ACL, and automatic lifecycle management.

## Architecture

```
                         +---------------------------+
                         |    Process Manager        |
                         |    REST API (:10900)      |
                         +-------------+-------------+
                                       |
                          Spawns N proxy instances
                                       |
                +----------+-----------+-----------+----------+
                |                      |                      |
     +----------v----------+  +-------v---------+  +---------v--------+
     | Proxy (:11900)      |  | Proxy (:11901)  |  | Proxy (:119XX)   |
     | FlightSQL + Auth    |  | FlightSQL + Auth |  | FlightSQL + Auth |
     | SQL Validation      |  | SQL Validation   |  | SQL Validation   |
     +----------+----------+  +--------+---------+  +---------+--------+
                |                      |                      |
     +----------v----------+  +--------v---------+  +---------v--------+
     | GizmoSQL (:12900)   |  | GizmoSQL (:12901)|  | GizmoSQL (:129XX)|
     | DuckLake (PG + S3)  |  | DuckLake (PG+S3) |  | DuckLake (PG+S3) |
     +---------------------+  +------------------+  +------------------+
```

The **Process Manager** is a REST API that provisions and manages proxy instances. Each **FlightSQL Proxy** authenticates users (Basic, Bearer/JWT, ODBC), validates SQL statements, enforces table-level ACLs, and forwards queries to its dedicated **GizmoSQL backend** which connects to a DuckLake catalog (PostgreSQL metadata + S3/local data lake storage).

```
  +---------------------+        +---------------------------+
  |       Client        |        |       ACL Files           |
  | (DBeaver,JDBC,ADBC) |        |     (YAML grants)         |
  +---------+-----------+        +-------------+-------------+
            |                                  |
            | Flight SQL                       |
            | TLS + JWT Auth                   |
            v                                  v
  +---------------------------------------------+
  |             GizmoSQL Proxy                   |
  |  +----------------+  +-------------------+   |
  |  |  Validation     |  |  ACL              |   |
  |  +----------------+  +-------------------+   |
  +---------------------+------------------------+
                        |
                        v
  +---------------------+------------------------+
  |          GizmoSQL Backend (DuckDB)           |
  +----------+------------------+----------------+
             |                  |
             v                  v
  +----------+-------+  +------+-----------------+
  |   PostgreSQL     |  |   Data Storage         |
  | (DuckLake Meta)  |  |   (local / S3)         |
  +------------------+  +------------------------+
```

### Runtime Backends

Two runtime backends are available (set `SL_GIZMO_RUNTIME_TYPE`):

- **Local mode** (`local`, default) -- Spawns OS processes with paired port allocation from a configurable range.
- **Kubernetes mode** (`kubernetes` or `k8s`) -- Creates a Pod + Service per instance. Pods are discoverable via cluster DNS and are automatically recovered if the Process Manager restarts.

### Features

- **Multi-tenant on-demand provisioning** via REST API
- **Multi-backend authentication** -- PostgreSQL database, Keycloak, Google, Azure AD, AWS Cognito, or custom JWT
- **Browser-based OAuth/SSO** for ADBC and CLI clients (`__discover__` flow)
- **Google Workspace groups lookup** via Directory API with per-user TTL cache
- **SQL statement validation** -- blocks DROP, configurable allow/deny
- **Table-level ACL** with hierarchical grants (database -> schema -> table)
- **Multi-tenant ACL** with folder-based isolation and hot-reload via file watcher
- **Role-based access** with configurable JWT claim extraction (role, groups, realm_access)
- **TLS encryption** -- auto-generated self-signed certificates for development
- **DuckLake integration** with PostgreSQL metadata
- **Optional S3 storage** for data files
- **On-demand backend process management** with idle timeout
- **Prepared statement validation**
- **Kubernetes and local runtime** with automatic pod recovery

## Prerequisites

- JDK 11+ (17+ recommended)
- sbt (Scala Build Tool)
- Docker (for containerized deployment)
- PostgreSQL (for DuckLake metadata)

## Quick Start

### Build

```bash
make build
# or
sbt assembly
```

The uber-jar is placed in `distrib/`.

### Run Locally (Process Manager)

```bash
# Start the process manager
./local-start-process-manager.sh

# Create a proxy instance
./create-process.sh my-session

# List running instances
./list-processes.sh

# Stop an instance
./stop-process.sh my-session
```

### Run the Proxy Standalone

```bash
# 1. Start the GizmoSQL backend (requires Docker)
./local-start-gizmo.sh

# 2. Start the proxy (in another terminal)
./local-start-proxy.sh

# 3. Connect with JDBC
# URL: jdbc:arrow-flight-sql://localhost:31338?useEncryption=true&disableCertificateVerification=true
```

### Run with Docker

```bash
make docker-build
make docker-run

# Or use the startup script directly
./docker-start-process-manager.sh
```

### Run with Kubernetes

```bash
export SL_GIZMO_RUNTIME_TYPE=kubernetes
export SL_GIZMO_K8S_NAMESPACE=my-namespace
export SL_GIZMO_K8S_IMAGE=starlakeai/gizmo-on-demand:latest
```

Each proxy instance is reachable at `gizmo-proxy-{name}.{namespace}.svc.cluster.local` on the configured proxy port. With `ClusterIP` (default), instances are only accessible within the cluster. Use `NodePort` or `LoadBalancer` for external access.

## Connecting to a Proxy Instance

Once a proxy is running, connect with any Apache Arrow Flight SQL client (JDBC, ODBC, or native Flight).

**JDBC example:**

```
jdbc:arrow-flight-sql://localhost:11900?useEncryption=true&disableCertificateVerification=true
```

**Authentication:** The proxy supports multiple authentication methods:

- **Username/Password** -- validated against a PostgreSQL database, Keycloak (ROPC), Azure AD (ROPC), or the legacy `GIZMOSQL_USERNAME`/`GIZMOSQL_PASSWORD` env vars.
- **Bearer Token** -- JWT tokens from Keycloak, Google, Azure AD, AWS Cognito, or a custom issuer are validated against the provider's public keys (JWKS).
- **Browser-based SSO** -- ADBC/CLI clients can use `auth_type="external"` for a browser-based OAuth login flow.

See the [Authentication Guide](docs/authentication.md) for detailed setup instructions per provider.

## API Reference

All endpoints except `/health` require the `X-API-Key` header when `SL_GIZMO_API_KEY` is set.

### Health Check

```
GET /health
```

```json
{"status": "ok", "message": "Gizmo On-Demand Process Manager is running"}
```

### Start a Process

```
POST /api/process/start
```

```json
{
  "processName": "my-session",
  "arguments": {
    "GIZMOSQL_USERNAME": "admin",
    "GIZMOSQL_PASSWORD": "secret",
    "SL_DB_ID": "my-project",
    "SL_DATA_PATH": "/data/ducklake_files/my-project",
    "PG_USERNAME": "postgres",
    "PG_PASSWORD": "pgpass",
    "PG_HOST": "localhost",
    "PG_PORT": "5432"
  }
}
```

Response:

```json
{
  "processName": "my-session",
  "port": 11900,
  "message": "Proxy Process started successfully on port 11900",
  "host": "127.0.0.1"
}
```

Optional fields: `connectionName`, `port` (request a specific port).

Additional arguments for S3-backed data lakes: `AWS_KEY_ID`, `AWS_SECRET`, `AWS_REGION`, `AWS_ENDPOINT`.

### Stop a Process

```
POST /api/process/stop
```

```json
{"processName": "my-session"}
```

### List Processes

```
GET /api/process/list
```

```json
{
  "processes": [
    {
      "processName": "my-session",
      "port": 11900,
      "pid": 12345,
      "status": "running",
      "host": "127.0.0.1"
    }
  ]
}
```

### Stop All Processes

```
POST /api/process/stopAll
```

## Configuration

### Process Manager

| Variable | Default | Description |
|---|---|---|
| `SL_GIZMO_ON_DEMAND_HOST` | `0.0.0.0` | Host to bind the management API |
| `SL_GIZMO_ON_DEMAND_PORT` | `10900` | Port for the management API |
| `SL_GIZMO_MIN_PORT` | `11900` | Start of port range for spawned processes |
| `SL_GIZMO_MAX_PORT` | `12000` | End of port range for spawned processes |
| `SL_GIZMO_MAX_PROCESSES` | `10` | Maximum number of concurrent proxy instances |
| `SL_GIZMO_DEFAULT_SCRIPT` | `/opt/gizmosql/scripts/docker-start-sl-gizmosql.sh` | Script to start the backend GizmoSQL process |
| `SL_GIZMO_PROXY_SCRIPT` | `/opt/gizmosql/scripts/docker-start-proxy.sh` | Script to start the proxy process |
| `SL_GIZMO_API_KEY` | - | API key for authentication (recommended in production) |
| `SL_GIZMO_DATA_PATHS` | - | Space-separated absolute paths to mount as Docker volumes (for DuckLake local file stores) |
| `SL_GIZMO_IDLE_TIMEOUT` | `-1` | Idle timeout in seconds: `>0` stops backend after timeout, `<0` never stops (default), `=0` stops immediately after each request |
| `SL_GIZMO_RUNTIME_TYPE` | `local` | Runtime backend: `local` or `kubernetes` (also accepts `k8s`) |

### Kubernetes

When `SL_GIZMO_RUNTIME_TYPE=kubernetes`, each proxy instance is created as a Kubernetes Pod with a companion Service. The Pod runs the same Docker image and internally starts the proxy + backend as local processes.

| Variable | Default | Description |
|---|---|---|
| `SL_GIZMO_K8S_NAMESPACE` | `default` | Kubernetes namespace for pods and services |
| `SL_GIZMO_K8S_IMAGE` | `gizmo-proxy:latest` | Container image for proxy pods |
| `SL_GIZMO_K8S_SERVICE_ACCOUNT` | - | Service account name for the pods |
| `SL_GIZMO_K8S_PROXY_PORT` | `31338` | Fixed proxy port inside the pod |
| `SL_GIZMO_K8S_BACKEND_PORT` | `31337` | Fixed backend port inside the pod |
| `SL_GIZMO_K8S_SERVICE_TYPE` | `ClusterIP` | Kubernetes Service type (`ClusterIP`, `NodePort`, or `LoadBalancer`) |
| `SL_GIZMO_K8S_IMAGE_PULL_POLICY` | `IfNotPresent` | Image pull policy (`Always`, `IfNotPresent`, `Never`) |
| `SL_GIZMO_K8S_STARTUP_TIMEOUT` | `120` | Seconds to wait for a pod to become ready |

**Network visibility:** With `ClusterIP` (default), proxy instances are only reachable from within the cluster. Set to `NodePort` or `LoadBalancer` to expose them externally.

**Process recovery:** On startup, the Process Manager discovers existing pods labeled `managed-by=gizmo-process-manager` and re-registers them. This means a Process Manager restart does not orphan running proxy instances.

### Proxy

These are typically set automatically by the Process Manager:

| Variable | Default | Description |
|---|---|---|
| `PROXY_HOST` | `0.0.0.0` | Proxy listen address |
| `PROXY_PORT` | `31338` | Proxy listen port |
| `PROXY_TLS_ENABLED` | `true` | Enable TLS for client connections |
| `PROXY_TLS_CERT_CHAIN` | `gizmosql-proxy/certs/server-cert.pem` | Path to TLS certificate chain |
| `PROXY_TLS_PRIVATE_KEY` | `gizmosql-proxy/certs/server-key.pem` | Path to TLS private key |
| `GIZMO_SERVER_HOST` | `127.0.0.1` | Backend GizmoSQL host |
| `GIZMO_SERVER_PORT` | `31337` | Backend GizmoSQL port |

### GizmoSQL Backend

| Variable | Default | Description |
|---|---|---|
| `GIZMOSQL_USERNAME` | - | Username for GizmoSQL authentication |
| `GIZMOSQL_PASSWORD` | - | Password for GizmoSQL authentication |
| `JWT_SECRET_KEY` | `a_very_secret_key` | Secret key for JWT token signing |
| `DATABASE_BACKEND` | `duckdb` | Database backend type |
| `DATABASE_FILENAME` | `data/TPC-H-small.duckdb` | Path to database file (`:memory:` for in-memory) |
| `TLS_ENABLED` | `0` | Enable TLS for backend (`0`/`1`) |
| `PRINT_QUERIES` | `1` | Log queries (`0`/`1`) |
| `READONLY` | `0` | Read-only mode (`0`/`1`) |
| `QUERY_TIMEOUT` | `0` | Query timeout in seconds (`0` = no timeout) |

### Database Connection

These are passed as `arguments` when starting a process:

| Variable | Default | Description |
|---|---|---|
| `SL_DB_ID` | - | Starlake project / database identifier |
| `SL_DATA_PATH` | - | Path to DuckLake data files |
| `PG_HOST` | `host.docker.internal` | PostgreSQL metadata host |
| `PG_PORT` | `5432` | PostgreSQL metadata port |
| `PG_USERNAME` | `postgres` | PostgreSQL username |
| `PG_PASSWORD` | - | PostgreSQL password |

### S3 Storage (Optional)

When DuckLake files are stored in S3-compatible storage, pass these as additional `arguments`:

| Variable | Description |
|---|---|
| `AWS_KEY_ID` | S3 access key ID |
| `AWS_SECRET` | S3 secret access key |
| `AWS_REGION` | S3 region |
| `AWS_ENDPOINT` | S3 endpoint URL (for MinIO or compatible services) |

### Authentication

The proxy supports configurable authentication backends. When no provider is enabled, credentials are validated against `GIZMOSQL_USERNAME`/`GIZMOSQL_PASSWORD`.

| Variable | Default | Description |
|---|---|---|
| `JWT_SECRET_KEY` | `a_very_secret_key` | Shared secret between proxy and backend (must match) |
| `AUTH_DB_ENABLED` | `false` | Enable PostgreSQL database authentication |
| `AUTH_DB_JDBC_URL` | `jdbc:postgresql://localhost:5432/gizmosql_auth` | JDBC connection URL |
| `AUTH_DB_QUERY` | `SELECT password, role FROM users WHERE username = ?` | SQL query returning (bcrypt_hash, role) |
| `AUTH_KEYCLOAK_ENABLED` | `false` | Enable Keycloak authentication |
| `AUTH_GOOGLE_ENABLED` | `false` | Enable Google OAuth authentication |
| `AUTH_AZURE_ENABLED` | `false` | Enable Azure AD authentication |
| `AUTH_AWS_ENABLED` | `false` | Enable AWS Cognito authentication |
| `AUTH_ROLE_CLAIM` | `role` | JWT claim name for role extraction |
| `AUTH_OAUTH_ENABLED` | `false` | Enable browser-based OAuth/SSO for ADBC clients |

For complete configuration options per provider, see the [Authentication Guide](docs/authentication.md).

### SQL Validation

| Variable | Default | Description |
|---|---|---|
| `VALIDATION_ENABLED` | `true` | Enable SQL statement validation |
| `VALIDATION_ALLOW_BY_DEFAULT` | `true` | Allow unrecognized statements (set `false` for deny-by-default) |
| `VALIDATION_BYPASS_USERS` | `admin` | Comma-separated list of users who bypass validation |

Default rules: `SELECT`, `INSERT`, and `UPDATE` (with `WHERE`) are allowed. `DROP DATABASE` and `DROP TABLE` are always denied.

### Access Control Lists (ACL)

| Variable | Default | Description |
|---|---|---|
| `ACL_ENABLED` | `false` | Enable/disable ACL validation |
| `ACL_BASE_PATH` | `/etc/gizmosql/acl` | Directory containing tenant ACL grants |
| `ACL_TENANT` | `default` | Active ACL tenant |

ACL grants are defined in YAML files and support hierarchical permissions (database -> schema -> table) with multi-tenant folder-based isolation. Grant files are hot-reloaded via a file watcher. ACL is disabled by default -- set `ACL_ENABLED=true` to enable. If the base path does not exist, all queries are allowed through.

## Idle Timeout

The backend GizmoSQL server supports three idle timeout modes:

| Mode | Config Value | Behavior |
|---|---|---|
| Disabled | `< 0` (default) | Backend runs indefinitely |
| Timed | `> 0` | Backend stops after N seconds of inactivity, automatically restarts on next query |
| Immediate | `= 0` | Backend stops after each request completes, restarts on next query |

The proxy remains running in all modes. Only the backend is stopped and restarted. This is useful for reducing resource consumption when instances are intermittently used.

## Docker Image

Published as `starlakeai/gizmo-on-demand` on Docker Hub.

Multi-platform: `linux/amd64`, `linux/arm64`.

```bash
# Publish snapshot
make docker-push-snapshot

# Publish release
make docker-push-release
```

The image is based on `gizmodata/gizmosql` and includes the GizmoSQL server binary, the Process Manager, proxy scripts, Java 21 JRE, and tini for proper signal handling.

## Development

```bash
make build          # Compile (sbt assembly)
make run            # Run locally (sbt run)
make test           # Run API tests
make clean          # Clean build artifacts

make docker-build   # Build Docker image
make docker-run     # Run container
make docker-stop    # Stop container
make docker-logs    # Tail container logs
```

## Documentation

- [Authentication Guide](docs/authentication.md) -- Multi-backend auth setup (DB, Keycloak, Google, Azure, AWS, OAuth/SSO)
- [Getting Started](docs/quickstart.md) -- Set up and run in 5 minutes
- [Usage Guide](docs/guide.md) -- Architecture, deployment, and configuration walkthrough
- [Configuration Reference](docs/configuration.md) -- All environment variables and settings
- [Access Control Lists](docs/acl.md) -- ACL grants, tenants, and permissions
- [Connecting from DBeaver](docs/dbeaver.md) -- Step-by-step DBeaver setup
- [Troubleshooting](docs/troubleshooting.md) -- Common issues and solutions
- [Architecture Document](docs/ARCHITECTURE.md) -- Detailed system architecture
- [Product Requirements](docs/PRD.md) -- Product requirements document

## License

Apache 2.0