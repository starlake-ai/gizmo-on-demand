# Configuration Reference

The GizmoSQL Proxy uses [HOCON](https://github.com/lightbend/config/blob/main/HOCON.md) format for its configuration file (`application.conf`). Every setting follows the override pattern:

```hocon
key = "default-value"
key = ${?ENV_VAR}
```

The first line sets the default. The second line overrides it with the environment variable `ENV_VAR` if it is defined. This means every setting can be controlled via environment variables without modifying the configuration file.

## Proxy Server

Config path: `gizmosql-proxy.proxy`

| Environment Variable | Config Key | Default | Description |
|---|---|---|---|
| `PROXY_HOST` | `proxy.host` | `0.0.0.0` | Network interface to bind to. Use `0.0.0.0` to listen on all interfaces or `127.0.0.1` for localhost only |
| `PROXY_PORT` | `proxy.port` | `31338` | TCP port for client connections ([Flight SQL](https://arrow.apache.org/docs/format/FlightSql.html) protocol) |
| `PROXY_TLS_ENABLED` | `proxy.tls.enabled` | `true` | Enable TLS encryption for client connections. When `true`, `cert-chain` and `private-key` must be set |
| `PROXY_TLS_CERT_CHAIN` | `proxy.tls.cert-chain` | `gizmosql-proxy/certs/server-cert.pem` | Path to the PEM-encoded TLS certificate chain file |
| `PROXY_TLS_PRIVATE_KEY` | `proxy.tls.private-key` | `gizmosql-proxy/certs/server-key.pem` | Path to the PEM-encoded TLS private key file |

## Backend Server

Config path: `gizmosql-proxy.backend`

| Environment Variable | Config Key | Default | Description |
|---|---|---|---|
| `GIZMO_SERVER_HOST` | `backend.host` | `localhost` | Hostname of the GizmoSQL backend server |
| `GIZMO_SERVER_PORT` | `backend.port` | `31337` | Port of the GizmoSQL backend server. Also used as an environment variable to trigger managed backend startup |
| `GIZMOSQL_TLS_ENABLED` | `backend.tls.enabled` | `false` | Enable TLS for the proxy-to-backend connection |
| `GIZMOSQL_TLS_CERT` | `backend.tls.trusted-certificates` | _(empty)_ | Path to trusted CA certificates for backend TLS verification |
| `GIZMOSQL_DEFAULT_USERNAME` | `backend.default-username` | _(empty)_ | Default username for anonymous connections (clients that don't send an Authorization header) |
| `GIZMOSQL_DEFAULT_PASSWORD` | `backend.default-password` | _(empty)_ | Default password for anonymous connections |

## Statement Validation

Config path: `gizmosql-proxy.validation`

| Environment Variable | Config Key | Default | Description |
|---|---|---|---|
| `VALIDATION_ENABLED` | `validation.enabled` | `true` | Enable or disable SQL statement validation entirely |
| `VALIDATION_ALLOW_BY_DEFAULT` | `validation.rules.allow-by-default` | `true` | When `true`, statements are allowed unless explicitly denied. When `false`, the [default validator](guide.md#default-validator-rules) only allows SELECT, INSERT, and UPDATE (with WHERE). Note: [ACL](acl.md) (if enabled) further restricts to SELECT queries only |
| `VALIDATION_BYPASS_USERS` | `validation.rules.bypass-users` | `["admin"]` | List of usernames that skip all [validation checks](guide.md#validation-pipeline) (both default and ACL). Format: JSON array (e.g., `'["admin","superuser"]'`) |
| `VALIDATION_RULES_FILE` | `validation.rules.rules-file` | _(empty)_ | Path to a database-specific rules file (reserved for future use) |

### Default Validation Rules

When validation is enabled, the following rules are applied in order:

1. If the user is in `bypass-users`, the statement is **allowed** immediately
2. If the statement starts with `DROP DATABASE` or `DROP TABLE`, it is **denied**
3. If `allow-by-default` is `true`, the statement is **allowed**
4. If `allow-by-default` is `false`, only `SELECT`, `INSERT`, and `UPDATE` (with `WHERE` clause) are allowed

## Logging

Config path: `gizmosql-proxy.logging`

| Environment Variable | Config Key | Default | Description |
|---|---|---|---|
| `LOG_LEVEL` | `logging.level` | `INFO` | Application log level. Values: `DEBUG`, `INFO`, `WARN`, `ERROR` |
| `LOG_STATEMENTS` | `logging.log-statements` | `true` | Log all SQL statements received by the proxy |
| `LOG_VALIDATION` | `logging.log-validation` | `true` | Log validation decisions (allowed/denied) with reasons |

## Session

Config path: `gizmosql-proxy.session`

These variables configure the DuckLake connection and authentication.

| Environment Variable | Config Key | Default | Description |
|---|---|---|---|
| `GIZMOSQL_USERNAME` | `session.gizmosql-username` | _(empty)_ | Username for authenticating with the GizmoSQL backend |
| `GIZMOSQL_PASSWORD` | `session.gizmosql-password` | _(empty)_ | Password for authenticating with the GizmoSQL backend |
| `SL_DB_ID` | `session.sl-project-id` | _(empty)_ | [DuckLake](https://ducklake.select/) database identifier. Used in [INIT_SQL_COMMANDS](guide.md#init_sql_commands-generation) to name the PostgreSQL secret, DuckLake secret, and the attached database. Must match the PostgreSQL database name |
| `SL_DATA_PATH` | `session.sl-data-path` | _(empty)_ | Path to the data storage directory. Used as `DATA_PATH` in the [DuckLake](https://ducklake.select/) secret. Can be a local path or S3 URI |
| `PG_HOST` | `session.pg-host` | [`host.docker.internal`](https://docs.docker.com/desktop/features/networking/#i-want-to-connect-from-a-container-to-a-service-on-the-host) | PostgreSQL server hostname. Use `host.docker.internal` when the proxy runs inside Docker, or `localhost` when running directly on the host |
| `PG_PORT` | `session.pg-port` | `5432` | PostgreSQL server port |
| `PG_USERNAME` | `session.pg-username` | _(empty)_ | PostgreSQL username for DuckLake metadata access |
| `PG_PASSWORD` | `session.pg-password` | _(empty)_ | PostgreSQL password for DuckLake metadata access |
| `JWT_SECRET_KEY` | `session.jwt-secret-key` | `a_very_secret_key` | Secret key for signing and verifying [JWT](https://datatracker.ietf.org/doc/html/rfc7519) tokens. **Change this in production!** |
| `ACL_TENANT` | `session.acl-tenant` | `default` | Active [ACL](acl.md) tenant identifier. Determines which [tenant folder](acl.md#tenant-directory-structure) is used for ACL [grant files](acl.md#grant-format-yaml) (`{ACL_BASE_PATH}/{ACL_TENANT}/`) |

## ACL (Access Control Lists)

Config path: `gizmosql-proxy.acl`

| Environment Variable | Config Key | Default | Description |
|---|---|---|---|
| `ACL_ENABLED` | `acl.enabled` | `true` | Enable or disable [ACL](acl.md)-based SQL authorization. When disabled, only [default validation rules](guide.md#default-validator-rules) apply |
| `ACL_BASE_PATH` | `acl.base-path` | `/etc/gizmosql/acl` | Base directory containing [tenant folders](acl.md#tenant-directory-structure). Each subfolder is a tenant with its own YAML [grant files](acl.md#grant-format-yaml) |
| `ACL_DIALECT` | `acl.dialect` | `duckdb` | SQL dialect for parsing queries. Values: `duckdb` ([DuckDB](https://duckdb.org/) extensions), `ansi` (standard SQL) |
| `ACL_GROUPS_CLAIM` | `acl.groups-claim` | `groups` | Name of the [JWT](https://datatracker.ietf.org/doc/html/rfc7519) claim containing user [group memberships](acl.md#principals). Supports JSON arrays (`["g1","g2"]`) and comma-separated strings (`"g1,g2"`) |
| `ACL_MAX_TENANTS` | `acl.max-tenants` | `100` | Maximum number of tenant ACL policies cached in memory (LRU eviction) |
| `ACL_WATCHER_ENABLED` | `acl.watcher.enabled` | `true` | Enable [file system watcher](acl.md#file-watcher) for automatic ACL reload when grant files change |
| `ACL_WATCHER_DEBOUNCE_MS` | `acl.watcher.debounce-ms` | `500` | Debounce delay in milliseconds before processing file changes (prevents rapid reloads) |
| `ACL_WATCHER_MAX_BACKOFF_MS` | `acl.watcher.max-backoff-ms` | `60000` | Maximum backoff delay in milliseconds when the file watcher encounters errors (exponential backoff) |

## On-Demand Process Manager

Config path: `gizmo-on-demand`

The on-demand manager controls GizmoSQL backend process lifecycle.

| Environment Variable | Config Key | Default | Description |
|---|---|---|---|
| `SL_GIZMO_ON_DEMAND_HOST` | `host` | `0.0.0.0` | Manager API listen address |
| `SL_GIZMO_ON_DEMAND_PORT` | `port` | `10900` | Manager API port |
| `SL_GIZMO_MIN_PORT` | `min-port` | `11900` | Minimum port number for managed backend processes |
| `SL_GIZMO_MAX_PORT` | `max-port` | `12000` | Maximum port number for managed backend processes |
| `SL_GIZMO_MAX_PROCESSES` | `max-processes` | `10` | Maximum number of concurrent backend processes |
| `SL_GIZMO_DEFAULT_SCRIPT` | `default-gizmo-script` | `/opt/gizmosql/scripts/docker-start-sl-gizmosql.sh` | Script used to start GizmoSQL backend instances |
| `SL_GIZMO_PROXY_SCRIPT` | `proxy-script` | `/opt/gizmosql/scripts/docker-start-proxy.sh` | Script used to start proxy instances |
| `SL_GIZMO_API_KEY` | `api-key` | `a_secret_api_key` | API key for the on-demand manager REST API. **Change this in production!** |
| `SL_GIZMO_IDLE_TIMEOUT` | `idle-timeout` | `-1` | Idle timeout in seconds for backend processes. `>0`: stop after N seconds of inactivity. `0`: stop immediately after each request. `-1`: never stop (default) |

## Special Environment Variables

These variables are not defined in `application.conf` but are read directly by the application code.

| Environment Variable | Description |
|---|---|
| `INIT_SQL_OVERRIDE` | When set, completely replaces the auto-generated [DuckLake initialization SQL](guide.md#init_sql_commands-generation). Use this to provide custom SQL commands for the backend (e.g., plain [DuckDB](https://duckdb.org/) without DuckLake, or testing scenarios) |
| `AWS_KEY_ID` | S3 access key ID. When all four AWS variables are set, an S3 secret is created in DuckDB for remote data storage |
| `AWS_SECRET` | S3 secret access key |
| `AWS_REGION` | S3 region (e.g., `us-east-1`, `eu-west-1`) |
| `AWS_ENDPOINT` | S3 endpoint URL. Determines SSL usage (prefix `https://` enables SSL) and URL style (contains `s3.amazonaws.com` uses vhost, otherwise uses path) |

## Startup Script Options

### `local-start-proxy.sh`

| Option | Description |
|---|---|
| _(no options)_ | Start with TLS enabled (auto-generates certificates if missing) |
| `--with-no-tls` | Start with TLS disabled |
| `--help` | Show usage information |

The script sets default values for all environment variables if not already set. You can override any variable before running the script:

```bash
export SL_DB_ID=mydb
export PG_HOST=my-postgres-host
./local-start-proxy.sh
```

### JAR Discovery Order

The startup script looks for the application JAR in this order:

1. `/opt/gizmosql/manager/gizmo-on-demand.jar` (Docker container path)
2. `distrib/gizmo-on-demand-assembly-0.1.0-SNAPSHOT.jar` (assembly output)
3. `target/scala-3.7.4/gizmo-on-demand-assembly-0.1.0-SNAPSHOT.jar` (sbt target)
4. Falls back to `sbt runMain` if no JAR is found

## Configuration Examples

### Development (minimal)

```bash
export SL_DB_ID=mydb
export PG_HOST=localhost
export PG_USERNAME=ducklake
export PG_PASSWORD=ducklake
export SL_DATA_PATH=/path/to/data
export ACL_BASE_PATH=/path/to/acl
./local-start-proxy.sh --with-no-tls
```

### Development with ACL disabled

```bash
export ACL_ENABLED=false
export SL_DB_ID=mydb
export PG_HOST=localhost
export PG_USERNAME=ducklake
export PG_PASSWORD=ducklake
export SL_DATA_PATH=/path/to/data
./local-start-proxy.sh --with-no-tls
```

### Production

```bash
export PROXY_PORT=443
export PROXY_TLS_ENABLED=true
export PROXY_TLS_CERT_CHAIN=/etc/ssl/certs/proxy.pem
export PROXY_TLS_PRIVATE_KEY=/etc/ssl/private/proxy-key.pem

export GIZMO_SERVER_HOST=backend.internal
export GIZMO_SERVER_PORT=31337
export GIZMOSQL_TLS_ENABLED=true
export GIZMOSQL_TLS_CERT=/etc/ssl/certs/backend-ca.pem

export SL_DB_ID=production
export PG_HOST=postgres.internal
export PG_USERNAME=prod_user
export PG_PASSWORD=<strong-password>
export SL_DATA_PATH=s3://my-bucket/data

export JWT_SECRET_KEY=<strong-random-secret>
export ACL_BASE_PATH=/etc/gizmosql/acl
export ACL_TENANT=production

export VALIDATION_ALLOW_BY_DEFAULT=false
export LOG_LEVEL=WARN
export LOG_STATEMENTS=false
```

### With S3 Storage

```bash
export AWS_KEY_ID=AKIAIOSFODNN7EXAMPLE
export AWS_SECRET=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY
export AWS_REGION=us-east-1
export AWS_ENDPOINT=https://s3.amazonaws.com
export SL_DATA_PATH=s3://my-bucket/warehouse/data
```

### With Custom Init SQL (no DuckLake)

```bash
export INIT_SQL_OVERRIDE="CREATE TABLE test AS SELECT 1 AS id, 'hello' AS name;"
./local-start-proxy.sh --with-no-tls
```

## See Also

- [Usage Guide](guide.md) -- Architecture and deployment walkthrough
- [Access Control Lists](acl.md) -- ACL grant configuration
- [Troubleshooting](troubleshooting.md) -- Common configuration issues
