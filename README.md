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
./start-proxy.sh
```

Or run manually with java:

```bash
java --add-opens=java.base/java.nio=ALL-UNNAMED -jar distrib/gizmo-on-demand-assembly-0.1.0-SNAPSHOT.jar
```

## Configuration

### Process Manager configuration
These variables control the main management service:

| Environment Variable      | Default | Description |
|---------------------------|---------|-------------|
| `SL_GIZMO_ON_DEMAND_HOST` | `0.0.0.0` | Host to bind the management API |
| `SL_GIZMO_ON_DEMAND_PORT`           | `10900` | Port for the management API |
| `SL_GIZMO_MIN_PORT`       | `8000` | Start of port range for spawned processes |
| `SL_GIZMO_MAX_PORT`       | `9000` | End of port range for spawned processes |
| `SL_GIZMO_MAX_PROCESSES`  | `10` | Maximum number of concurrent proxy instances |
| `SL_GIZMO_DEFAULT_SCRIPT` | `/opt/gizmo/scripts/start_gizmosql.sh` | Script to start the backend GizmoSQL process |
| `SL_GIZMO_API_KEY`        | - | **Required**. API Key for authentication |

### Proxy Configuration
These variables are used by the spawned proxy instances (usually set automatically by ProcessManager):

| Environment Variable | Description |
|---------------------|-------------|
| `PROXY_HOST` | Host for the proxy to listen on (default 0.0.0.0) |
| `PROXY_PORT` | Port for the proxy to listen on |
| `GIZMOSQL_HOST` | Host of the backend GizmoSQL (default 127.0.0.1) |
| `GIZMOSQL_PORT` | Port of the backend GizmoSQL |
| `PROXY_SECRET_KEY` | Secret key for JWT generation |

### TLS Configuration

| Environment Variable | Description |
|---------------------|-------------|
| `PROXY_TLS_ENABLED` | Enable TLS for the proxy (true/false) |
| `PROXY_TLS_CERT_CHAIN` | Path to certificate chain file |
| `PROXY_TLS_PRIVATE_KEY` | Path to private key file |
