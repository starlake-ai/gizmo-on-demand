# Quickstart Guide

Get the GizmoSQL Proxy up and running in minutes. This guide walks you through building the project, starting the proxy, and connecting a SQL client. For a deeper understanding of the architecture, see the [Usage Guide](guide.md). For all configuration options, see the [Configuration Reference](configuration.md).

## Prerequisites

| Tool | Purpose | Install |
|------|---------|---------|
| **Java 17+** (JDK) | Runtime for the proxy | `java -version` to verify. [Adoptium](https://adoptium.net/) or `brew install openjdk@17` |
| **Docker Desktop** | Run the GizmoSQL backend | [docker.com](https://www.docker.com/products/docker-desktop/) |
| **sbt** | Scala build tool | `brew install sbt` (macOS) or [scala-sbt.org](https://www.scala-sbt.org/) |
| **PostgreSQL** | [DuckLake](https://ducklake.select/) metadata storage | `brew install postgresql` or use Docker (see Step 2) |
| **openssl** | TLS certificate generation | Pre-installed on macOS/Linux |

## Step 1: Clone & Build

```bash
git clone <repository-url>
cd gizmo-on-demand

# Build the assembly JAR
sbt assembly

# The JAR is created at: distrib/gizmo-on-demand-assembly-0.1.0-SNAPSHOT.jar
```

> **Note**: If you skip building, the startup script will fall back to running via `sbt runMain` (slower startup).

## Step 2: Set Up PostgreSQL

[DuckLake](https://ducklake.select/) uses PostgreSQL to store metadata (table definitions, schema info). You need a running PostgreSQL instance with a database matching your [`SL_DB_ID`](configuration.md#session) (the DuckLake database identifier — it names the PostgreSQL database, the DuckLake secret, and the attached DuckDB database).

```bash
# Option A: Using Docker (recommended for development)
docker run -d \
  --name postgres-ducklake \
  -e POSTGRES_USER=ducklake \
  -e POSTGRES_PASSWORD=ducklake \
  -e POSTGRES_DB=tpch2 \
  -p 5432:5432 \
  postgres:16

# Option B: Using an existing PostgreSQL instance
createdb -U ducklake tpch2
```

The database name (`tpch2` in this example) must match the [`SL_DB_ID`](configuration.md#session) environment variable.

## Step 3: Create ACL Grant Files

Create a directory structure for your [ACL grants](acl.md#grant-format-yaml) (YAML files that define who can access which tables):

```bash
mkdir -p gizmo_data/acl/default
```

Create a grant file `gizmo_data/acl/default/grants.yaml`:

```yaml
mode: strict

grants:
  # Allow all users to query the tpch2 database
  - target: tpch2
    principals:
      - group:admin
```

> **Note**: The [`mode: strict`](acl.md#resolution-modes) setting means unknown tables are denied by default. [`principals`](acl.md#principals) defines who gets the grant — `group:admin` means any user in the `admin` group. When using Basic Auth (e.g., from DBeaver or JDBC), all users are automatically assigned the `admin` group. See the [ACL documentation](acl.md#user-identity--groups) for details on user groups.

## Step 4: Start the Proxy

The startup script launches both the proxy **and** the GizmoSQL backend automatically. When `GIZMO_SERVER_PORT` is set, the proxy starts a Docker container running the [DuckDB](https://duckdb.org/)/GizmoSQL backend as a managed subprocess.

```bash
# Backend configuration
export GIZMO_SERVER_PORT=31337              # Port for the Flight SQL backend (see configuration.md#backend-server)
export GIZMOSQL_USERNAME=gizmosql_username  # Backend auth credentials
export GIZMOSQL_PASSWORD=gizmosql_password

# Proxy configuration (see configuration.md for full reference)
export SL_DB_ID=tpch2                       # DuckLake database identifier (must match PostgreSQL DB name)
export PG_HOST=localhost                    # PostgreSQL host — use 'localhost' when running outside Docker
export PG_USERNAME=ducklake                 # PostgreSQL credentials for DuckLake metadata
export PG_PASSWORD=ducklake
export SL_DATA_PATH=$(pwd)/gizmo_data       # Path where DuckLake stores data files
export ACL_BASE_PATH=$(pwd)/gizmo_data/acl  # Base directory for ACL tenant grant files

# Start the proxy (TLS enabled by default, certificates auto-generated)
./local-start-proxy.sh
```

This will:
1. Generate self-signed TLS certificates (if not already present)
2. Start the GizmoSQL backend in Docker on port 31337, with:
   - An in-memory [DuckDB](https://duckdb.org/) database
   - [DuckLake](https://ducklake.select/) extension loaded (lakehouse storage layer using PostgreSQL for metadata)
   - [Flight SQL](https://arrow.apache.org/docs/format/FlightSql.html) server (Apache Arrow's SQL protocol over gRPC)
3. Start the proxy listening for client connections on port 31338

## Step 5: Connect and Test

### Using DBeaver

See the [DBeaver connection guide](dbeaver.md) for detailed setup.

Quick JDBC URL:
```
jdbc:arrow-flight-sql://localhost:31338?useEncryption=true&disableCertificateVerification=true
```
- Username: `gizmosql_username`
- Password: `gizmosql_password`

### Using a [Flight SQL](https://arrow.apache.org/docs/format/FlightSql.html) client

```bash
# Example with Python (requires pyarrow)
python3 -c "
from pyarrow import flight

client = flight.connect('grpc+tls://localhost:31338',
    disable_server_verification=True)
# Authenticate
token = client.authenticate_basic_token('gizmosql_username', 'gizmosql_password')
options = flight.FlightCallOptions(headers=[token])

# Run a query
info = client.get_flight_info(
    flight.FlightDescriptor.for_command('SELECT 1 AS test'),
    options)
reader = client.do_get(info.endpoints[0].ticket, options)
print(reader.read_all().to_pandas())
"
```

### Without TLS

If you prefer to skip TLS during development:

```bash
./local-start-proxy.sh --with-no-tls
```

Then connect using:
```
jdbc:arrow-flight-sql://localhost:31338?useEncryption=false
```

## Next Steps

- [Usage Guide](guide.md) -- Learn about architecture, deployment modes, and advanced configuration
- [Configuration Reference](configuration.md) -- All environment variables and settings
- [Access Control Lists](acl.md) -- Set up fine-grained table-level permissions
- [Connecting from DBeaver](dbeaver.md) -- Detailed DBeaver setup guide
- [Troubleshooting](troubleshooting.md) -- Common issues and solutions
