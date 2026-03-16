# GizmoSQL On-Demand Proxy

A service for managing and proxying on-demand GizmoSQL instances.

## Architecture

The Process Manager spawns proxy instances via a pluggable backend:

- **Local mode** (default) — spawns OS processes using `ProcessBuilder`, allocating paired ports from a configurable range. This is the standard mode when running in Docker or directly on a host.
- **Kubernetes mode** — creates a Pod + Service per proxy instance using the fabric8 Kubernetes client. Each pod runs the same container image and manages the proxy + backend internally as local processes. A pod watch detects failures and triggers cleanup.

Set `SL_GIZMO_RUNTIME_TYPE` to `local` or `kubernetes` to select the backend.

## Building

### Prerequisites
- JDK 11 or higher
- sbt (Scala Build Tool)

### Build Command
Compile and build the assembly (uber-jar):

```bash
make build
# OR
sbt assembly
```

The resulting jar will be placed in the `distrib/` directory.

## Running

Start the server using the provided script:

```bash
./docker-start-process-manager.sh
```

Or run manually with java:

```bash
java --add-opens=java.base/java.nio=ALL-UNNAMED -jar distrib/gizmo-on-demand-assembly-0.1.0-SNAPSHOT.jar
```

## Configuration

### Process Manager Configuration
These variables control the main management service:

| Environment Variable      | Default                                   | Description                                                                                                      |
|---------------------------|-------------------------------------------|------------------------------------------------------------------------------------------------------------------|
| `SL_GIZMO_ON_DEMAND_HOST` | `0.0.0.0`                                 | Host to bind the management API                                                                                  |
| `SL_GIZMO_ON_DEMAND_PORT` | `10900`                                   | Port for the management API                                                                                      |
| `SL_GIZMO_MIN_PORT`       | `11900`                                   | Start of port range for spawned processes                                                                        |
| `SL_GIZMO_MAX_PORT`       | `12000`                                   | End of port range for spawned processes                                                                          |
| `SL_GIZMO_MAX_PROCESSES`  | `10`                                      | Maximum number of concurrent proxy instances                                                                     |
| `SL_GIZMO_DEFAULT_SCRIPT` | `/opt/gizmosql/scripts/docker-start-sl-gizmosql.sh` | Script to start the backend GizmoSQL process                                                                     |
| `SL_GIZMO_API_KEY`        | -                                         | **Required**. API Key for authentication                                                                         |
| `SL_GIZMO_DATA_PATHS`     | -                                         | Space-separated list of absolute paths to mount as Docker volumes (required for DuckLake local file stores only) |
| `SL_GIZMO_IDLE_TIMEOUT`   | `-1`                                      | Idle timeout in seconds for the backend server: `>0` stops after timeout, `<0` never stops (default), `=0` stops immediately after each request |
| `SL_GIZMO_RUNTIME_TYPE`   | `local`                                   | Runtime backend: `local` spawns OS processes, `kubernetes` (or `k8s`) creates Pods + Services |

### Kubernetes Configuration
When `SL_GIZMO_RUNTIME_TYPE=kubernetes`, each proxy instance is created as a Kubernetes Pod with a companion Service. The Pod runs the same Docker image and internally starts the proxy + backend as local processes, exactly as in Docker mode.

| Environment Variable              | Default          | Description                                                    |
|-----------------------------------|------------------|----------------------------------------------------------------|
| `SL_GIZMO_K8S_NAMESPACE`         | `default`        | Kubernetes namespace for pods and services                     |
| `SL_GIZMO_K8S_IMAGE`             | `gizmo-proxy:latest` | Container image to use for proxy pods                      |
| `SL_GIZMO_K8S_SERVICE_ACCOUNT`   | -                | Service account name for the pods                              |
| `SL_GIZMO_K8S_PROXY_PORT`        | `31338`          | Fixed proxy port inside the pod                                |
| `SL_GIZMO_K8S_BACKEND_PORT`      | `31337`          | Fixed backend port inside the pod                              |
| `SL_GIZMO_K8S_SERVICE_TYPE`      | `ClusterIP`      | Kubernetes Service type (`ClusterIP` or `NodePort`)            |
| `SL_GIZMO_K8S_IMAGE_PULL_POLICY` | `IfNotPresent`   | Image pull policy (`Always`, `IfNotPresent`, `Never`)          |
| `SL_GIZMO_K8S_STARTUP_TIMEOUT`   | `120`            | Seconds to wait for a pod to become ready before giving up     |

Each proxy instance is reachable at `gizmo-proxy-{name}.{namespace}.svc.cluster.local` on the configured proxy port. The API responses include the `host` field with this address.

### Database Connection Configuration
These variables configure the connection to the PostgreSQL metadata database:

| Environment Variable | Default | Description         |
|----------------------|---------|---------------------|
| `SL_DB_ID`      | -       | Database identifier |
| `PG_HOST`            | `host.docker.internal` | PostgreSQL host     |
| `PG_PORT`            | `5432`  | PostgreSQL port     |
| `PG_USERNAME`        | `postgres` | PostgreSQL username |
| `PG_PASSWORD`        | -       | PostgreSQL password |

### GizmoSQL Backend Configuration
These variables configure the GizmoSQL backend instances:

| Environment Variable   | Default | Description |
|------------------------|---------|-------------|
| `GIZMOSQL_USERNAME`    | -       | Username for GizmoSQL authentication |
| `GIZMOSQL_PASSWORD`    | -       | Password for GizmoSQL authentication |
| `JWT_SECRET_KEY`       | -       | Secret key for JWT token signing (mapped to `SECRET_KEY` for backend) |
| `TLS_ENABLED`          | `0`     | Enable TLS for backend (`0` = disabled, `1` = enabled) |
| `DATABASE_BACKEND`     | `duckdb` | Database backend type |
| `DATABASE_FILENAME`    | `data/TPC-H-small.duckdb` | Path to database file |
| `PRINT_QUERIES`        | `1`     | Print queries to logs (`0` = disabled, `1` = enabled) |
| `READONLY`             | `0`     | Read-only mode (`0` = disabled, `1` = enabled) |
| `QUERY_TIMEOUT`        | `0`     | Query timeout in seconds (`0` = no timeout) |

### Proxy Configuration
These variables are used by the spawned proxy instances (usually set automatically by ProcessManager):

| Environment Variable | Description |
|----------------------|-------------|
| `PROXY_HOST`         | Host for the proxy to listen on (default `0.0.0.0`) |
| `PROXY_PORT`         | Port for the proxy to listen on |
| `GIZMO_SERVER_HOST`  | Host of the backend GizmoSQL (default `127.0.0.1`) |
| `GIZMO_SERVER_PORT`  | Port of the backend GizmoSQL |
| `PROXY_SECRET_KEY`   | Secret key for JWT generation |

### TLS Configuration

| Environment Variable    | Description |
|-------------------------|-------------|
| `PROXY_TLS_ENABLED`     | Enable TLS for the proxy (`true`/`false`) |
| `PROXY_TLS_CERT_CHAIN`  | Path to certificate chain file |
| `PROXY_TLS_PRIVATE_KEY` | Path to private key file |
