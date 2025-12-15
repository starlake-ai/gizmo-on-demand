# GizmoSQL On-Demand Proxy

A service for managing and proxying on-demand GizmoSQL instances.

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

### Database Connection Configuration
These variables configure the connection to the PostgreSQL metadata database:

| Environment Variable | Default | Description |
|----------------------|---------|-------------|
| `SL_PROJECT_ID`      | -       | Project identifier |
| `PG_HOST`            | `host.docker.internal` | PostgreSQL host |
| `PG_PORT`            | `5432`  | PostgreSQL port |
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
