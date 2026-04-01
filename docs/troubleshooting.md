# Troubleshooting

Solutions to common issues encountered when running the GizmoSQL Proxy. Each entry follows: **Symptom** → **Cause** → **Solution**.

---

## 1. Cannot Connect to PostgreSQL

**Symptom**: Proxy fails to start or queries fail with PostgreSQL connection errors.

**Causes**:
- [`PG_HOST`](configuration.md#session) is set to `host.docker.internal` but the proxy is running on the host (not in Docker)
- PostgreSQL is not running or not accepting connections on the specified port
- Wrong credentials ([`PG_USERNAME`](configuration.md#session) / [`PG_PASSWORD`](configuration.md#session))
- Database specified by [`SL_DB_ID`](configuration.md#session) does not exist

**Solutions**:
1. If running the proxy **on the host** (not in Docker), set `PG_HOST=localhost`
2. If running the proxy **in Docker**, use `PG_HOST=host.docker.internal` (macOS/Windows) or the container network address (Linux)
3. Verify PostgreSQL is running: `pg_isready -h localhost -p 5432`
4. Verify the database exists: `psql -h localhost -U ducklake -l | grep <SL_DB_ID>`
5. Create the database if missing: `createdb -U ducklake <SL_DB_ID>`

---

## 2. TLS Certificate Errors

**Symptom**: Client cannot connect with TLS, or gets certificate-related errors.

**Causes**:
- Certificate files not found at the configured paths
- Self-signed certificate not trusted by the client
- Certificate has expired (default: 365 days validity)
- Mismatch between TLS setting on client and server

**Solutions**:
1. Let the script regenerate certificates by deleting the `certs/` folder and restarting
2. For self-signed certs with JDBC, add `disableCertificateVerification=true` to the URL
3. Start without TLS for development: `./local-start-proxy.sh --with-no-tls`
4. Ensure client and server agree on TLS: if proxy has TLS enabled, client must use `useEncryption=true`

---

## 3. "Tenant not found"

**Symptom**: [ACL](acl.md) validation fails with `TenantNotFound` error.

**Causes**:
- The [tenant](acl.md#tenant-directory-structure) directory does not exist under [`ACL_BASE_PATH`](configuration.md#acl-access-control-lists)
- The tenant name contains invalid characters
- [`ACL_TENANT`](configuration.md#session) is set to a value that doesn't match any folder name

**Solutions**:
1. Verify the tenant directory exists:
   ```bash
   ls -la ${ACL_BASE_PATH}/${ACL_TENANT}/
   ```
2. Create the directory if missing:
   ```bash
   mkdir -p ${ACL_BASE_PATH}/${ACL_TENANT}
   ```
3. Tenant IDs must match `[a-zA-Z0-9_-]+` — no spaces or special characters
4. Remember: an empty directory is valid (deny-all policy)

---

## 4. Access Denied Despite Valid Grant

**Symptom**: Query is denied even though a grant appears to exist for the user/table.

**Causes**:

### Database name mismatch
The [`target`](acl.md#grant-targets) in the grant must match the database name used by the proxy. The ACL uses [`SL_DB_ID`](configuration.md#session) (via `session.slProjectId`) as the `defaultDatabase` in the SQL context.

**Solution**: Ensure the grant target matches `SL_DB_ID`. For example, if `SL_DB_ID=tpch2`, use:
```yaml
- target: tpch2.public.orders  # Correct
```
Not:
```yaml
- target: mydb.public.orders  # Wrong — doesn't match SL_DB_ID
```

### Wrong username or group
**Solution**: Check the proxy logs for the exact username and groups being used:
```
ACL check: tenant=default, user=alice, groups=admin, database=tpch2
```

### Grant has expired
**Solution**: Check for `expires` field in the grant. If the date is in the past, the grant is no longer valid.

### Missing underlying table grants for views
When a view has [`authorized: false`](acl.md#opaque-views-authorized-grants) (default), the user needs access to both the view and all its underlying tables.

**Solution**: Set `authorized: true` on the view grant to make it opaque, or add grants for all underlying tables.

### Unqualified table name
Tables in SQL queries are qualified using `defaultDatabase` (from `SL_DB_ID`) and `defaultSchema` (not set by default). If the query uses an unqualified table name and no default schema is configured, the ACL may not be able to match it.

**Solution**: Use fully qualified table names in queries: `SELECT * FROM mydb.myschema.mytable`

---

## 5. Backend GizmoSQL Not Starting

**Symptom**: `local-start-gizmo.sh` fails or the Docker container exits immediately.

**Causes**:
- Docker is not running
- Port conflict (another process using the same port)
- Docker image not available

**Solutions**:
1. Verify Docker is running: `docker info`
2. Check for port conflicts: `lsof -i :31337`
3. Pull the image: `docker pull starlakeai/gizmo-on-demand:latest`
4. Check Docker logs: `docker logs <container-id>`

---

## 6. Assembly JAR Not Found

**Symptom**: `local-start-proxy.sh` outputs "Assembly JAR not found" and falls back to sbt.

**Cause**: The project has not been built with `sbt assembly`.

**Solutions**:
1. Build the assembly JAR:
   ```bash
   sbt assembly
   ```
2. The JAR will be created at `distrib/gizmo-on-demand-assembly-0.1.0-SNAPSHOT.jar`
3. Note: Running via `sbt runMain` (the fallback) is slower but functionally equivalent

---

## 7. JVM `--add-opens` Error

**Symptom**: `java.lang.reflect.InaccessibleObjectException` or similar reflection errors at startup.

**Cause**: Apache Arrow requires access to `java.base/java.nio` internals, which are restricted in Java 9+.

**Solution**: The startup scripts already include `--add-opens=java.base/java.nio=ALL-UNNAMED`. If running manually, add this JVM option:
```bash
java --add-opens=java.base/java.nio=ALL-UNNAMED -cp gizmo-on-demand.jar ai.starlake.gizmo.proxy.ProxyServer
```

---

## 8. SQL Parse Error

**Symptom**: [ACL](acl.md) validation fails with `SqlParseError`.

**Causes**:
- The SQL query is not a SELECT statement (INSERT, UPDATE, DELETE, DDL are [rejected by ACL](acl.md#not-supported))
- Invalid SQL syntax
- DuckDB-specific syntax not supported by the parser

**Solutions**:
1. ACL only authorizes **SELECT** statements — other statement types are rejected
2. Try changing the SQL dialect: set [`ACL_DIALECT`](configuration.md#acl-access-control-lists)`=ansi` instead of `duckdb`
3. Check if the SQL syntax is valid by running it directly on the backend
4. Note: The [default validator](guide.md#default-validator-rules) (separate from ACL) handles DROP blocking and other rules

---

## 9. INIT_SQL_COMMANDS Failing

**Symptom**: Proxy starts but queries fail with "table not found" or [DuckLake](https://ducklake.select/) errors.

**Causes**:
- PostgreSQL credentials are wrong ([`PG_USERNAME`](configuration.md#session), [`PG_PASSWORD`](configuration.md#session))
- PostgreSQL host is unreachable from the backend container
- DuckLake extension not available
- [`SL_DB_ID`](configuration.md#session) doesn't match an existing PostgreSQL database

**Solutions**:
1. Check the proxy logs for the generated INIT_SQL_COMMANDS to verify correctness
2. Verify PostgreSQL connectivity from the backend:
   ```bash
   docker exec <container> psql -h host.docker.internal -U ducklake -d <SL_DB_ID>
   ```
3. Use [`INIT_SQL_OVERRIDE`](configuration.md#special-environment-variables) to test with custom SQL:
   ```bash
   export INIT_SQL_OVERRIDE="SELECT 1;"
   ```

---

## 10. Docker Networking Issues

**Symptom**: Backend container cannot reach PostgreSQL on the host.

**Causes**:
- Docker Desktop's `host.docker.internal` not available (Linux without Docker Desktop)
- Firewall blocking connections from Docker to host

**Solutions**:
1. On **macOS/Windows** with Docker Desktop: use `PG_HOST=host.docker.internal` (default)
2. On **Linux without Docker Desktop**: use `--network=host` in the Docker run command, or use the Docker bridge IP (usually `172.17.0.1`)
3. Verify with: `docker run --rm alpine ping host.docker.internal`

---

## 11. ACL Watcher Not Detecting Changes

**Symptom**: After modifying a grant YAML file, the ACL cache is not refreshed.

**Causes**:
- [File watcher](acl.md#file-watcher) is disabled ([`ACL_WATCHER_ENABLED`](configuration.md#acl-access-control-lists)`=false`)
- Network file system (NFS, CIFS) doesn't support Java's WatchService
- The modified file doesn't have `.yaml` or `.yml` extension

**Solutions**:
1. Verify the watcher is enabled: check logs for "ACL file watcher started"
2. On network file systems, restart the proxy to reload grants
3. Ensure files use `.yaml` or `.yml` extension
4. Check the watcher status in logs for retry/error messages

---

## 12. JWT/Authentication Issues

**Symptom**: "Authentication failed" or "Invalid Authorization Header" errors.

**Causes**:
- Client not sending Authorization header
- Wrong [JWT](https://datatracker.ietf.org/doc/html/rfc7519) secret key (mismatch between proxy and external JWT issuer)
- JWT token expired

**Solutions**:
1. Ensure the client sends credentials (username/password for [Basic Auth](guide.md#basic-auth), or [Bearer token](guide.md#bearer-auth-jwt))
2. Verify [`JWT_SECRET_KEY`](configuration.md#session) matches between all components
3. For Bearer tokens, ensure the JWT hasn't expired and is issued by `"gizmosql"`
4. Check the proxy logs for detailed authentication error messages

---

## 13. "No authenticated backend client found"

**Symptom**: `UNAUTHENTICATED: No authenticated backend client found for user: <username>`

**Cause**: The proxy couldn't establish a connection to the backend with the user's credentials.

**Solutions**:
1. Verify the backend is running and accessible
2. Check backend credentials ([`GIZMOSQL_USERNAME`](configuration.md#session), [`GIZMOSQL_PASSWORD`](configuration.md#session))
3. If using TLS for the backend connection, verify [`GIZMOSQL_TLS_ENABLED`](configuration.md#backend-server) and [`GIZMOSQL_TLS_CERT`](configuration.md#backend-server)

---

## 14. Grant File Validation Errors

**Symptom**: Errors when loading YAML grant files.

### "Invalid target"
```
must be database, database.schema, or database.schema.table
```
**Solution**: Use 1 to 3 dot-separated parts: `mydb`, `mydb.schema`, or `mydb.schema.table`

### "Invalid principal"
```
must be 'user:name' or 'group:name'
```
**Solution**: Use the format `user:alice` or `group:analysts`

### "Empty principals"
**Solution**: Every grant must have at least one principal

### "Invalid expires"
**Solution**: Use ISO-8601 format: `"2026-12-31T23:59:59Z"`

### "Unresolved variable"
**Solution**: Set the environment variable referenced by `${VAR_NAME}` in the YAML file

---

## Getting More Help

- Enable debug logging: [`LOG_LEVEL`](configuration.md#logging)`=DEBUG`
- Check proxy logs for detailed error messages and ACL decision traces
- Review [Configuration Reference](configuration.md) for all available settings
- Review [ACL Documentation](acl.md) for grant file format and rules
- Review [DBeaver Guide](dbeaver.md) for client-specific issues
