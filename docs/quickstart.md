# Quickstart Guide

Get the GizmoSQL Proxy up and running in minutes. This guide walks you through building the project, starting the proxy, and connecting a SQL client. For a deeper understanding of the architecture, see the [Usage Guide](guide.md). For all configuration options, see the [Configuration Reference](configuration.md).

## Prerequisites

| Tool | Purpose | Install |
|------|---------|---------|
| **Java 17+** (JDK) | Runtime for the proxy | `java -version` to verify. [Adoptium](https://adoptium.net/) or `brew install openjdk@17` |
| **Docker Desktop** | Run the GizmoSQL backend | [docker.com](https://www.docker.com/products/docker-desktop/) |
| **sbt** | Scala build tool | `brew install sbt` (macOS) or [scala-sbt.org](https://www.scala-sbt.org/) |
| **PostgreSQL** | [DuckLake](https://ducklake.select/) metadata storage | `brew install postgresql` or use Docker (see Step 2) |
| **DuckDB CLI** | Initialize [DuckLake](https://ducklake.select/) metadata catalog | `brew install duckdb` (macOS) or [duckdb.org](https://duckdb.org/docs/installation/) |
| **openssl** | TLS certificate generation | Pre-installed on macOS/Linux |

## Step 1: Clone the Repository

```bash
git clone <repository-url>
cd gizmo-on-demand
```

> **Tip**: You can optionally run `sbt assembly` to build a fat JAR (`distrib/gizmo-on-demand-assembly-0.1.0-SNAPSHOT.jar`) for faster startup. If absent, the startup script falls back to `sbt runMain`.

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
  postgres:18

# Option B: Using an existing PostgreSQL instance
createdb -U ducklake tpch2
```

The database name (`tpch2` in this example) must match the [`SL_DB_ID`](configuration.md#session) environment variable.

## Step 3: Initialize the DuckLake Catalog

The GizmoSQL backend attaches the [DuckLake](https://ducklake.select/) database in **read-only** mode. This means the metadata catalog must already exist in PostgreSQL before starting the proxy. Use the [DuckDB CLI](https://duckdb.org/docs/installation/) to create it:

```bash
duckdb
```

Then run the following SQL commands (adjust credentials and paths to match your setup):

```sql
INSTALL ducklake;
LOAD ducklake;

-- Create a secret for PostgreSQL connectivity
CREATE SECRET pg_tpch2 (
  TYPE postgres,
  HOST 'localhost',
  PORT 5432,
  DATABASE tpch2,
  USER 'ducklake',
  PASSWORD 'ducklake'
);

-- Create the DuckLake secret referencing the PostgreSQL secret
CREATE SECRET tpch2 (
  TYPE ducklake,
  METADATA_PATH '',
  DATA_PATH '/path/to/gizmo_data',
  METADATA_PARAMETERS MAP {
    'TYPE': 'postgres',
    'SECRET': 'pg_tpch2'
  }
);

-- Attach DuckLake — this creates the metadata catalog tables in PostgreSQL
ATTACH 'ducklake:tpch2' AS tpch2;

-- Generate TPC-H sample data into the DuckLake catalog
INSTALL tpch;
LOAD tpch;

-- Generate data in the default in-memory database, then copy each table to DuckLake
CALL dbgen(sf=1);

CREATE TABLE tpch2.customer AS SELECT * FROM customer;
CREATE TABLE tpch2.lineitem AS SELECT * FROM lineitem;
CREATE TABLE tpch2.nation AS SELECT * FROM nation;
CREATE TABLE tpch2.orders AS SELECT * FROM orders;
CREATE TABLE tpch2.part AS SELECT * FROM part;
CREATE TABLE tpch2.partsupp AS SELECT * FROM partsupp;
CREATE TABLE tpch2.region AS SELECT * FROM region;
CREATE TABLE tpch2.supplier AS SELECT * FROM supplier;

-- Create a view with a TPC-H transformation: revenue per nation
CREATE VIEW tpch2.revenue_per_nation AS
SELECT
  n.n_name AS nation,
  EXTRACT(YEAR FROM o.o_orderdate) AS order_year,
  SUM(l.l_extendedprice * (1 - l.l_discount)) AS revenue
FROM
  tpch2.lineitem l
  JOIN tpch2.orders o ON l.l_orderkey = o.o_orderkey
  JOIN tpch2.customer c ON o.o_custkey = c.c_custkey
  JOIN tpch2.nation n ON c.c_nationkey = n.n_nationkey
GROUP BY
  n.n_name,
  EXTRACT(YEAR FROM o.o_orderdate);
```

> **Note**: Replace `/path/to/gizmo_data` with the absolute path to your `gizmo_data` directory (e.g., `$(pwd)/gizmo_data`). The `HOST` should be `localhost` since you're running DuckDB CLI directly on your machine. The `dbgen` call generates 8 TPC-H tables (`customer`, `lineitem`, `nation`, `orders`, `part`, `partsupp`, `region`, `supplier`) directly into the DuckLake catalog. You can adjust the scale factor (`sf`) — use `0.01` for a quick test or `1` for a realistic dataset. Once initialized, you can exit DuckDB with `.exit`.

## Step 4: Create ACL Grant Files

Create a directory structure for your [ACL grants](acl.md#grant-format-yaml) (YAML files that define who can access which tables):

```bash
mkdir -p gizmo_data/acl/default
```

Create a grant file `gizmo_data/acl/default/grants.yaml`:

```yaml
mode: strict

grants:
  # Allow admin group to query the lineitem table
  - target: tpch2.main.lineitem
    principals:
      - group:admin

  # Deny admin group from querying the revenue_per_nation view
  - target: tpch2.main.revenue_per_nation
    principals:
      - group:admin
    authorized: false
```

This configuration grants access to the `lineitem` table but explicitly **denies** access to the `revenue_per_nation` view. In [Step 6](#step-6-connect-and-test), you will see the deny in action, then unlock it.

> **Note**: The [`mode: strict`](acl.md#resolution-modes) setting means that resources the [view resolver](acl.md#resolution-modes) cannot identify (table or view) are denied by default — "unknown" refers to resources whose type the resolver fails to resolve, not simply resources absent from the grant file. [`principals`](acl.md#principals) defines who gets the grant — `group:admin` means any user in the `admin` group. When using Basic Auth (e.g., from DBeaver or JDBC), all users are automatically assigned the `admin` group. For the full grant format and all available options, see the [ACL documentation](acl.md).

## Step 5: Start the Proxy

The startup script launches both the proxy **and** the GizmoSQL backend automatically. When `GIZMO_SERVER_PORT` is set, the proxy starts a Docker container running the [DuckDB](https://duckdb.org/)/GizmoSQL backend as a managed subprocess.

```bash
# Backend configuration
export GIZMO_SERVER_PORT=31337              # Port for the Flight SQL backend (see configuration.md#backend-server)
export GIZMOSQL_USERNAME=gizmosql_username  # Backend auth credentials
export GIZMOSQL_PASSWORD=gizmosql_password

# Proxy configuration (see configuration.md for full reference)
export SL_DB_ID=tpch2                       # DuckLake database identifier (must match PostgreSQL DB name)
export PG_HOST=host.docker.internal                    # PostgreSQL host — use 'localhost' when running outside Docker
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

## Step 6: Connect and Test

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

### Try the ACL in Action

**1. Query the allowed table** — this should succeed:

```sql
SELECT * FROM tpch2.main.lineitem LIMIT 10;
```

**2. Query the denied view** — this should be rejected:

```sql
SELECT * FROM tpch2.main.revenue_per_nation LIMIT 10;
```

The proxy returns an access denied error because `authorized: false` is set for this view in the grant file.

**3. Unlock access** — edit `gizmo_data/acl/default/grants.yaml` and change `authorized: false` to `authorized: true` (or simply remove the `authorized` line, since `true` is the default):

```yaml
  - target: tpch2.main.revenue_per_nation
    principals:
      - group:admin
    authorized: true
```

**4. Retry the query** — the same query now succeeds:

```sql
SELECT * FROM tpch2.main.revenue_per_nation LIMIT 10;
```

> **Note**: ACL grant files are reloaded automatically — no proxy restart needed.

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
