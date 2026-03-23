# Connecting from DBeaver

This guide walks you through connecting DBeaver to the GizmoSQL Proxy using the Apache Arrow Flight SQL JDBC driver.

## Prerequisites

- **DBeaver** — Community Edition 23.x or later (or any edition)
- **GizmoSQL Proxy running** — See the [Getting Started](quickstart.md) guide
- **[Arrow Flight SQL](https://arrow.apache.org/docs/format/FlightSql.html) JDBC Driver** — Downloaded from Apache (see below)

## Step 1: Download the Flight SQL JDBC Driver

Download the Apache Arrow Flight SQL JDBC driver:

1. Go to the [Apache Arrow releases page](https://arrow.apache.org/install/) or Maven Central
2. Download `flight-sql-jdbc-driver-<version>-shaded.jar` (e.g., version 14.0.1 to match the project)
3. Save it to a known location (e.g., `~/drivers/`)

Alternatively, download directly from Maven Central:
```
https://repo1.maven.org/maven2/org/apache/arrow/flight-sql-jdbc-driver/14.0.1/flight-sql-jdbc-driver-14.0.1.jar
```

## Step 2: Add the Driver to DBeaver

1. Open DBeaver
2. Go to **Database** → **Driver Manager**
3. Click **New**
4. Fill in the driver details:
   - **Driver Name**: `Arrow Flight SQL`
   - **Driver Type**: `Generic`
   - **Class Name**: `org.apache.arrow.driver.jdbc.ArrowFlightJdbcDriver`
   - **URL Template**: `jdbc:arrow-flight-sql://{host}:{port}?useEncryption={useEncryption}`
5. Go to the **Libraries** tab
6. Click **Add File** and select the downloaded `flight-sql-jdbc-driver-*.jar`
7. Click **Find Class** to verify the driver class is found
8. Click **OK** to save

## Step 3: Create a New Connection

1. Click **New Database Connection** (or **Database** → **New Database Connection**)
2. Select the **Arrow Flight SQL** driver you just created
3. Configure the connection:

### With TLS (default proxy configuration)

| Setting | Value |
|---|---|
| **Host** | `localhost` |
| **Port** | `31338` |
| **Username** | Your username (e.g., `gizmosql_username`) |
| **Password** | Your password (e.g., `gizmosql_password`) |

**JDBC URL**:
```
jdbc:arrow-flight-sql://localhost:31338?useEncryption=true&disableCertificateVerification=true
```

> **Note**: `disableCertificateVerification=true` is required when using self-signed certificates (default for local development). For production with proper certificates, remove this parameter.

### Without TLS

If the proxy was started with `--with-no-tls`:

| Setting | Value |
|---|---|
| **Host** | `localhost` |
| **Port** | `31338` |
| **Username** | Your username |
| **Password** | Your password |

**JDBC URL**:
```
jdbc:arrow-flight-sql://localhost:31338?useEncryption=false
```

## Step 4: Configure Connection Properties

In the connection dialog, go to the **Driver Properties** tab and configure:

| Property | Value | Description |
|---|---|---|
| `useEncryption` | `true` or `false` | Enable/disable TLS |
| `disableCertificateVerification` | `true` | Skip certificate verification (for self-signed certs) |
| `user` | your username | Authentication username |
| `password` | your password | Authentication password |

### Additional JDBC URL Parameters

| Parameter | Default | Description |
|---|---|---|
| `useEncryption` | `true` | Enable TLS encryption |
| `disableCertificateVerification` | `false` | Skip TLS certificate verification |
| `tlsRootCerts` | — | Path to trusted CA certificates (PEM file) |
| `token` | — | Bearer token for JWT authentication |
| `useSystemTrustStore` | `false` | Use the system's trust store for TLS |

## Step 5: Test the Connection

1. Click **Test Connection** to verify connectivity
2. If successful, click **Finish**
3. Open a new SQL editor and run a test query:

```sql
SELECT 1 AS test;
```

Or, if you have tables set up:

```sql
SELECT * FROM information_schema.tables;
```

## ACL Considerations with DBeaver

### How Authentication Works

When DBeaver connects with username/password ([Basic Auth](guide.md#basic-auth)), the proxy:

1. Decodes the username and password from the Authorization header
2. Creates a [JWT](https://datatracker.ietf.org/doc/html/rfc7519) token with the following claims:
   - `sub` = your username
   - `role` = `"admin"` (hardcoded)
   - `auth_method` = `"Basic"`
3. Uses this JWT for ACL group extraction

### Group Assignment

Since Basic Auth does not carry group information, **all DBeaver users are assigned the single group `admin`** (via the JWT `role` claim fallback).

This means:
- Grants targeting `group:admin` apply to **all** DBeaver users
- For **per-user access control**, use `user:<username>` principals in your grants
- For **group-based access control**, use [Bearer Auth](guide.md#bearer-auth-jwt) with a JWT containing custom groups (advanced)

### Example: ACL Grants for DBeaver Users

```yaml
mode: strict

grants:
  # All DBeaver users (group:admin) can access common tables
  - target: mydb.public.products
    principals:
      - group:admin

  # Only Alice can access sensitive HR data
  - target: mydb.hr.salaries
    principals:
      - user:alice

  # Only Bob can access financial reports
  - target: mydb.finance.reports
    principals:
      - user:bob

  # Both Alice and Bob can access shared analytics
  - target: mydb.analytics
    principals:
      - user:alice
      - user:bob
```

With this configuration:
- When Alice connects from DBeaver (username: `alice`), she can query `products`, `salaries`, and `analytics`
- When Bob connects (username: `bob`), he can query `products`, `reports`, and `analytics`
- When Carol connects (username: `carol`), she can only query `products` (via `group:admin`)

### Important Notes

- The **username you enter in DBeaver** becomes the `user:` identity in the ACL system
- Usernames are **case-insensitive** — `Alice` and `alice` are treated the same
- If ACL is disabled ([`ACL_ENABLED`](configuration.md#acl-access-control-lists)`=false`), only [default validation rules](guide.md#default-validator-rules) apply (no table-level checks)
- The `admin` user (if listed in [`VALIDATION_BYPASS_USERS`](configuration.md#statement-validation)) bypasses **all** validation including ACL

## Troubleshooting

### "No Authorization header found"

**Cause**: DBeaver is not sending credentials.

**Solution**: Ensure username and password are filled in the connection settings. Check that the JDBC URL doesn't override authentication.

### TLS Certificate Error

**Cause**: Self-signed certificate not trusted.

**Solutions**:
1. Add `disableCertificateVerification=true` to the JDBC URL
2. Or import the certificate into Java's truststore:
   ```bash
   keytool -import -alias gizmosql -file certs/server-cert.pem -keystore $JAVA_HOME/lib/security/cacerts
   ```
3. Or start the proxy without TLS: `./local-start-proxy.sh --with-no-tls`

### "Statement execution denied"

**Cause**: ACL is blocking your query.

**Solutions**:
1. Check the proxy logs for the denial reason
2. Verify your grants file includes a grant for the table you're querying
3. Ensure the [grant target](acl.md#grant-targets) matches your database name (check [`SL_DB_ID`](configuration.md#session))
4. Verify the username in DBeaver matches the `user:` principal in grants

### Connection Refused

**Cause**: Proxy is not running or wrong port.

**Solutions**:
1. Verify the proxy is running (`ps aux | grep ProxyServer`)
2. Check the port matches ([`PROXY_PORT`](configuration.md#proxy-server), default 31338)
3. Check if TLS setting matches between client and server

### "Driver class not found"

**Cause**: Flight SQL JDBC driver JAR not properly loaded.

**Solutions**:
1. Re-add the JAR in DBeaver's Driver Manager
2. Click **Find Class** to verify `org.apache.arrow.driver.jdbc.ArrowFlightJdbcDriver` is found
3. Restart DBeaver after adding the driver

## See Also

- [Getting Started](quickstart.md) — Setting up the proxy
- [Access Control Lists](acl.md) — Detailed ACL documentation
- [Configuration Reference](configuration.md) — All proxy settings
- [Troubleshooting](troubleshooting.md) — More troubleshooting topics
