# Access Control Lists (ACL)

## Overview

The GizmoSQL Proxy includes a built-in SQL-level access control system based on table-level ACLs. When enabled, every SQL query is analyzed to extract the referenced tables, and access is checked against YAML-defined grant files before the query is forwarded to the backend.

## How It Works

### Request Lifecycle

The following diagram shows the complete lifecycle of a SQL query through the ACL system:

```
Client                    Proxy                         Backend
  |                         |                              |
  |  1. SQL query           |                              |
  |  (Flight SQL)           |                              |
  |------------------------>|                              |
  |                         |                              |
  |                   2. Deserialize protobuf              |
  |                      Extract SQL statement             |
  |                         |                              |
  |                   3. Default Validator                  |
  |                      - Bypass users skip all           |
  |                      - DROP statements blocked         |
  |                      - allowByDefault rules            |
  |                         |                              |
  |                   4. ACL Validator (if enabled)         |
  |                      a. Extract tenant (ACL_TENANT)    |
  |                      b. Extract username (auth)        |
  |                      c. Extract groups (JWT claims)    |
  |                      d. Parse SQL -> table list        |
  |                      e. Check each table vs grants     |
  |                      f. ALL tables must be allowed     |
  |                         |                              |
  |                   5. ALLOWED?                          |
  |                      Yes ──────────────────────────────>|
  |                         |                              |
  |                   6. DENIED?                           |
  |<── Error (denial reason)|                              |
  |                         |                              |
```

1. **Client sends SQL query** via the [Flight SQL](https://arrow.apache.org/docs/format/FlightSql.html) protocol ([`getFlightInfo`](https://arrow.apache.org/docs/format/FlightSql.html#query-execution) or [`doAction/CreatePreparedStatement`](https://arrow.apache.org/docs/format/FlightSql.html#prepared-statements)).
2. **Proxy deserializes** the [protobuf](https://protobuf.dev/) command (`CommandStatementQuery`) to extract the raw SQL statement.
3. **Default Validator** runs first:
   - Bypass users skip all validation entirely.
   - DROP statements are blocked.
   - [`allowByDefault`](configuration.md#statement-validation) rules apply based on configuration.
4. **ACL Validator** runs second (if enabled):
   - Extracts the tenant ID from configuration ([`ACL_TENANT`](configuration.md#session)).
   - Extracts user identity from [authentication](guide.md#authentication) (username from Basic or Bearer auth).
   - Extracts user groups from [JWT](https://datatracker.ietf.org/doc/html/rfc7519) claims.
   - Parses the SQL to find all referenced tables.
   - Checks each table against the tenant's grant files.
   - Decision: **ALLOWED** if the user has access to **ALL** tables, **DENIED** if **ANY** table is denied.
5. If allowed: the query is forwarded to the GizmoSQL backend.
6. If denied: an error is returned to the client with the denial reason (CallStatus.UNAUTHENTICATED).

## ACL Storage

### Storage Backends

ACL grant files can be stored on different backends. The backend is **automatically inferred** from the `ACL_BASE_PATH` URI prefix:

| `ACL_BASE_PATH` prefix | Backend | Change detection |
|---|---|---|
| `/local/path` | Local filesystem | Java WatchService (instant) |
| `s3://bucket/path` | AWS S3 | Polling (configurable interval) |
| `gs://bucket/path` | Google Cloud Storage | Polling (configurable interval) |
| `az://container/path` | Azure Blob Storage | Polling (configurable interval) |

No additional configuration field is required — just change `ACL_BASE_PATH` to switch backends.

#### Cloud Backend Authentication

For cloud backends, credentials are resolved in this order:

1. **Explicit** — provider-specific configuration (if set)
2. **Automatic** — default credential chain of each provider

| Backend | Explicit config | Automatic fallback |
|---|---|---|
| S3 | `ACL_S3_REGION`, `ACL_S3_CREDENTIALS_FILE` | [Default Credential Chain](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/credentials-chain.html) (env vars, instance profile, etc.) |
| GCS | `ACL_GCS_PROJECT_ID`, `ACL_GCS_SERVICE_ACCOUNT_KEY_FILE` | [Application Default Credentials](https://cloud.google.com/docs/authentication/application-default-credentials) (ADC) |
| Azure | `ACL_AZURE_CONNECTION_STRING` | [DefaultAzureCredential](https://learn.microsoft.com/en-us/java/api/overview/azure/identity-readme#defaultazurecredential) (managed identity, env vars) |

All explicit fields are optional (`Option[String]`). When absent, the automatic fallback is used.

#### Examples

```bash
# Local filesystem (default)
export ACL_BASE_PATH=/etc/gizmosql/acl

# AWS S3
export ACL_BASE_PATH=s3://my-acl-bucket/tenants
export ACL_S3_REGION=eu-west-1  # optional

# Google Cloud Storage
export ACL_BASE_PATH=gs://my-acl-bucket/tenants
export ACL_GCS_PROJECT_ID=my-project  # optional

# Azure Blob Storage
export ACL_BASE_PATH=az://my-acl-container/tenants
export ACL_AZURE_CONNECTION_STRING="DefaultEndpointsProtocol=..."  # optional
```

## Tenant Directory Structure

```
{ACL_BASE_PATH}/
├── default/              # Default tenant
│   └── grants.yaml
├── tenant-a/             # Tenant A
│   ├── grants.yaml
│   └── extra-grants.yaml
├── tenant-b/             # Tenant B
│   └── grants.yaml
└── tenant-c/             # Empty folder = deny-all
```

- The active tenant is set via [`ACL_TENANT`](configuration.md#session) environment variable (default: `default`).
- Tenant IDs must match `[a-zA-Z0-9_-]+`.
- An unknown tenant (missing folder) returns a `TenantNotFound` error.
- An empty folder is a valid tenant where all queries are denied.
- The same directory structure applies whether using local filesystem or cloud storage.

## Grant File Organization & Merge Strategy

### How Files Are Merged

When a tenant folder contains multiple YAML files, they are processed as follows:

1. All `.yaml` and `.yml` files are discovered in the tenant folder.
2. Files are sorted **alphabetically** by filename.
3. Each file is parsed independently.
4. **Grants are concatenated** — all grants from all files are combined into a single list (union/append).
5. The **resolution mode** (`strict` or `permissive`) is taken from the **first file alphabetically** — modes in subsequent files are ignored.

> **Important**: Grants are additive. A grant in `extra-grants.yaml` does not override or remove a grant in `grants.yaml`. All grants from all files are accumulated.

### Organization Strategies

#### Single File (simplest)

```
default/
└── grants.yaml       # All grants in one file
```

Best for: Small deployments with few grants.

#### By Domain (recommended for medium deployments)

```
production/
├── 00-mode.yaml        # mode: strict (processed first)
├── 01-sales.yaml       # Sales team grants
├── 02-hr.yaml          # HR team grants
└── 03-finance.yaml     # Finance team grants
```

Example `00-mode.yaml`:
```yaml
mode: strict
grants:
  # Global admin access
  - target: production
    principals:
      - group:admin
```

Example `01-sales.yaml`:
```yaml
mode: permissive  # IGNORED — mode from 00-mode.yaml is used
grants:
  - target: production.sales
    principals:
      - group:sales-team
  - target: production.public.customers
    principals:
      - user:sales-manager
```

#### By Role

```
default/
├── 00-mode.yaml       # mode: strict
├── admins.yaml         # Admin grants
├── analysts.yaml       # Analyst grants
└── developers.yaml     # Developer grants
```

#### By Access Level

```
default/
├── 00-base.yaml        # mode: strict + common grants
├── read-only.yaml      # Read-only grants for viewers
└── read-write.yaml     # Extended grants for editors
```

### Best Practices

- **Always prefix the mode file with `00-`** to guarantee it is processed first.
- Use **numbered prefixes** (`01-`, `02-`, etc.) when you want deterministic ordering.
- Keep grant files **focused** — each file should serve a clear purpose.
- Use **descriptive filenames** that indicate the content (`sales-team.yaml` not `grants2.yaml`).

## Grant Format (YAML)

### Basic Structure

```yaml
mode: strict  # Optional. "strict" (default) or "permissive"

grants:
  - target: <target>
    principals:
      - <principal>
      - <principal>
    authorized: false  # Optional, default: false
    expires: "2026-12-31T23:59:59Z"  # Optional
```

### Fields Reference

| Field | Required | Default | Description |
|---|---|---|---|
| `mode` | No | `strict` | Resolution mode: `strict` (unknown tables denied) or `permissive` (unknown tables allowed) |
| `grants` | Yes | — | List of grant rules |
| `grants[].target` | Yes | — | Dot-separated target: `database`, `database.schema`, or `database.schema.table` |
| `grants[].principals` | Yes | — | List of principals: `user:name` or `group:name` (at least one required) |
| `grants[].authorized` | No | `false` | If `true`, makes a view "opaque" (user doesn't need access to underlying tables) |
| `grants[].expires` | No | _(none)_ | ISO-8601 expiration date (e.g., `"2026-12-31T23:59:59Z"`). Grant is invalid after this date |

## Grant Targets

Targets use dot-notation with 1 to 3 levels:

### Database Level

```yaml
- target: analytics
  principals: [group:data-team]
```

Grants access to **all schemas and all tables** in the `analytics` database.

### Schema Level

```yaml
- target: analytics.public
  principals: [user:alice]
```

Grants access to **all tables** in the `analytics.public` schema.

### Table Level

```yaml
- target: analytics.public.orders
  principals: [user:bob]
```

Grants access to **only** the `analytics.public.orders` table.

### Evaluation Hierarchy

Evaluation follows a short-circuit OR logic:

1. **Table match** — if a grant matches the exact table, result is returned immediately.
2. **Schema match** — if a grant matches the schema, access is allowed.
3. **Database match** — if a grant matches the database, access is allowed.
4. **No match** — access is denied.

A query touching multiple tables requires the user to have access to **ALL** referenced tables. If any single table is denied, the entire query is denied.

### DuckDB Name Resolution

When the ACL dialect is `duckdb`, two-part table names in SQL queries are resolved differently from standard SQL:

| SQL name | DuckDB resolution | Standard SQL resolution |
|----------|-------------------|------------------------|
| `orders` | `{defaultDatabase}.main.orders` | `{defaultDatabase}.{defaultSchema}.orders` |
| `tpch2.orders` | `tpch2.main.orders` (catalog-first) | `{defaultDatabase}.tpch2.orders` (schema-first) |
| `tpch2.main.orders` | `tpch2.main.orders` | `tpch2.main.orders` |

DuckDB interprets two-part names as `catalog.table` (not `schema.table`), using `main` as the default schema. This matches DuckDB's own behavior: *"When providing partial qualifications, DuckDB attempts to resolve the reference as either a catalog or a schema."*

**Recommendation:** Always use fully qualified three-part names (`database.schema.table`) in both SQL queries and grant targets to avoid ambiguity.

## Principals

Principals identify who receives a grant:

```yaml
principals:
  - user:alice       # Individual user
  - group:analysts   # Group membership
```

**Rules:**
- The principals list cannot be empty.
- Matching is **case-insensitive** (`user:Alice` = `user:alice`).
- Permissions are **additive**: a user receives the union of all their direct grants plus grants from all their groups.

### Example

```yaml
grants:
  - target: db.schema.table1
    principals: [user:alice]          # Direct user grant

  - target: db.schema.table2
    principals: [group:analysts]      # Group grant

  - target: db.schema.table3
    principals:
      - user:bob
      - group:managers
      - group:analysts                # Multiple principals
```

If Alice belongs to the `analysts` group, she can access `table1` (direct grant) and `table2` (group grant).

## Resolution Modes

The resolution mode controls behavior when a table or view cannot be identified by the view resolver.

| Situation | Strict Mode | Permissive Mode |
|---|---|---|
| Known table with matching grant | Allowed | Allowed |
| Known table without matching grant | Denied | Denied |
| Unknown table/view | **Denied** | **Allowed** (with warning) |

```yaml
mode: strict      # Default — unknown tables are denied
```

```yaml
mode: permissive  # Unknown tables are allowed (with a warning logged)
```

**Recommendation**: Use `strict` mode in production to prevent accidental access to unrecognized resources.

## Opaque Views (authorized grants)

The `authorized: true` flag creates an **opaque** grant for a view. The user can access the view without needing access to its underlying tables.

```yaml
grants:
  # Bob sees the view without needing access to underlying tables
  - target: analytics.reports.revenue_view
    principals: [user:bob]
    authorized: true

  # Charlie must have access to both the view AND underlying tables
  - target: analytics.reports.revenue_view
    principals: [user:charlie]
    authorized: false  # default
  - target: analytics.reports.transactions
    principals: [user:charlie]
```

**Behavior:**
- `authorized: false` (default): **transparent** — user must have access to the view AND all underlying tables.
- `authorized: true`: **opaque** — user accesses the view without checking underlying tables.
- The `authorized` flag only applies to table-level grants.

## Grant Expiration

The optional `expires` field sets a time limit on a grant. After the expiration date, the grant is invalid and access is denied with an `ExpiredGrant` reason.

```yaml
grants:
  # Temporary access for external audit
  - target: analytics.public.financial_data
    principals: [user:external-auditor]
    expires: "2026-06-30T23:59:59Z"

  # Permanent access for the data team
  - target: analytics.public
    principals: [group:data-team]
    # No expires = permanent
```

**Format**: ISO-8601 Instant with `Z` (UTC) suffix. Examples:
- `"2026-12-31T23:59:59Z"`
- `"2026-01-15T08:00:00Z"`

**Important**: A malformed date produces an `InvalidExpires` error when loading the YAML file.

## Environment Variables in Grants

Use `${VAR_NAME}` syntax for environment variable substitution in grant files:

```yaml
grants:
  - target: ${DATABASE}.${SCHEMA}.sensitive_data
    principals:
      - group:${ADMIN_GROUP}
```

**Rules:**
- Variables are resolved **before** YAML parsing.
- An undefined variable produces an `UnresolvedVariable` error.
- The expected format is `${NAME}` (curly braces required).

## User Identity & Groups

### How User Identity Is Constructed

When a client connects to the proxy, the user identity is determined as follows:

| Auth Method | Username Source | Groups Source |
|---|---|---|
| **Basic Auth** | Decoded from `username:password` | JWT `role` claim (fallback) — always `"admin"` |
| **Bearer Auth** | JWT `sub` claim | JWT `groups` claim (primary) or `role` claim (fallback) |

### Group Extraction Logic

Groups are extracted from JWT claims in this order:

1. **Primary**: The claim named by `ACL_GROUPS_CLAIM` (default: `"groups"`) is read.
   - If the claim contains a JSON array (e.g., `["analysts", "data-team"]`), it is parsed as a list.
   - If the claim contains a string (e.g., `"analysts,data-team"`), it is split by comma.
2. **Fallback**: If the groups claim is not present, the `"role"` claim is used as a single group.

### Basic Auth and Groups

**Important**: When a user connects with Basic Auth (username + password), the proxy mints a JWT token with the following hardcoded claims:

```json
{
  "sub": "<username>",
  "role": "admin",
  "auth_method": "Basic",
  "session_id": "<random-uuid>"
}
```

There is **no `groups` claim** in this JWT. Therefore, the group extraction falls back to the `role` claim, which is always `"admin"`.

**Consequence**: All Basic Auth users (including DBeaver, JDBC clients) are assigned the single group `admin`.

### Writing Grants for Basic Auth Users

To work with Basic Auth users, use one of these approaches:

**By username** (recommended for fine-grained control):

```yaml
grants:
  - target: mydb.public.orders
    principals:
      - user:alice
      - user:bob

  - target: mydb.public.customers
    principals:
      - user:alice
```

**By the admin group** (grants access to all Basic Auth users):

```yaml
grants:
  - target: mydb.public.orders
    principals:
      - group:admin    # All Basic Auth users get this group
```

**Mixed approach**:

```yaml
grants:
  # All Basic Auth users can read common tables
  - target: mydb.public.reference_data
    principals:
      - group:admin

  # Only specific users can read sensitive tables
  - target: mydb.public.salary_data
    principals:
      - user:hr-manager
```

### Bearer Auth with Custom Groups

To use custom groups with the ACL system, generate a JWT externally with the proper claims:

```json
{
  "sub": "alice",
  "groups": ["analysts", "data-team", "read-only"],
  "iss": "gizmosql",
  "exp": 1735689600
}
```

The JWT must be:
- Signed with the same algorithm ([HMAC256](https://datatracker.ietf.org/doc/html/rfc7518#section-3.2)) and secret key ([`JWT_SECRET_KEY`](configuration.md#session)).
- Issued by `"gizmosql"`.

Then connect using Bearer authentication with this token.

### Case-Insensitive Matching

All identifiers (usernames, group names, database names, schema names, table names) are automatically normalized to **lowercase**. This means:

- `user:Alice` in a grant file matches username `alice`, `ALICE`, or `Alice`.
- `group:Data-Team` matches a group `data-team`.
- `target: MyDB.Public.Orders` matches a query on `mydb.public.orders`.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `ACL_ENABLED` | `true` | Enable/disable ACL validation |
| `ACL_BASE_PATH` | `/etc/gizmosql/acl` | Base path for tenant grant folders. Supports `s3://`, `gs://`, `az://` prefixes for cloud storage |
| `ACL_TENANT` | `default` | Active tenant identifier |
| `ACL_DIALECT` | `duckdb` | SQL dialect for parsing (`duckdb` or `ansi`) |
| `ACL_GROUPS_CLAIM` | `groups` | JWT claim name for user groups |
| `ACL_MAX_TENANTS` | `100` | Max cached tenant policies |
| `ACL_WATCHER_ENABLED` | `true` | Enable change detection for auto-reload |
| `ACL_WATCHER_DEBOUNCE_MS` | `500` | Debounce delay before reload — local filesystem only (ms) |
| `ACL_WATCHER_MAX_BACKOFF_MS` | `60000` | Max retry backoff — local filesystem only (ms) |
| `ACL_WATCHER_POLL_INTERVAL_MS` | `30000` | Polling interval — cloud backends only (ms) |
| `ACL_S3_REGION` | _(none)_ | AWS S3 region (optional, auto-detected if absent) |
| `ACL_S3_CREDENTIALS_FILE` | _(none)_ | Path to AWS credentials file (optional, uses default chain if absent) |
| `ACL_GCS_PROJECT_ID` | _(none)_ | GCP project ID (optional, auto-detected if absent) |
| `ACL_GCS_SERVICE_ACCOUNT_KEY_FILE` | _(none)_ | Path to GCP service account JSON key (optional, uses ADC if absent) |
| `ACL_AZURE_CONNECTION_STRING` | _(none)_ | Azure connection string (optional, uses DefaultAzureCredential if absent) |

See [Configuration Reference](configuration.md) for complete details.

## Change Detection

When [`ACL_WATCHER_ENABLED`](configuration.md#acl-access-control-lists)`=true` (default), the proxy monitors the ACL storage for changes. The detection mechanism adapts to the storage backend:

### Local Filesystem

Uses Java's `WatchService` for near-instant detection:

1. The watcher detects file creation, modification, or deletion.
2. After the debounce delay ([`ACL_WATCHER_DEBOUNCE_MS`](configuration.md#acl-access-control-lists)), the affected tenant's cache is invalidated.
3. The next query triggers a reload of the tenant's grants.

### Cloud Storage (S3, GCS, Azure)

Uses periodic polling since cloud providers don't support push-based file watching:

1. Every [`ACL_WATCHER_POLL_INTERVAL_MS`](configuration.md#acl-access-control-lists) (default: 30 seconds), the detector lists tenants and their files.
2. Changes are detected by comparing file names and last-modified timestamps with the previous state.
3. Detected changes invalidate the affected tenant's cache.

This allows you to update ACL policies without restarting the proxy, regardless of the storage backend.

**Limitations:**
- **Local filesystem**: Network file systems (NFS, CIFS) may not support `WatchService` — consider using cloud storage or restarting the proxy.
- **Cloud storage**: Changes are detected with a latency of up to `ACL_WATCHER_POLL_INTERVAL_MS`. Reduce the interval for faster detection (at the cost of more API calls).
- Very rapid changes may be batched into a single reload due to debouncing (local) or polling interval (cloud).

## Complete Examples

### Minimal Setup (Development)

```
acl/default/grants.yaml
```

```yaml
mode: permissive

grants:
  - target: mydb
    principals:
      - group:admin
```

### Multi-Level Access Control

```
acl/production/grants.yaml
```

```yaml
mode: strict

grants:
  # Database admins can access everything
  - target: warehouse
    principals:
      - group:admin

  # Analysts can access the analytics schema
  - target: warehouse.analytics
    principals:
      - group:analysts

  # Sales team can only access specific tables
  - target: warehouse.public.orders
    principals:
      - group:sales
  - target: warehouse.public.customers
    principals:
      - group:sales

  # Executives see a summary view (opaque - no underlying table access needed)
  - target: warehouse.reports.executive_dashboard
    principals:
      - group:executives
    authorized: true

  # Temporary access for external auditor
  - target: warehouse.public.financial_data
    principals:
      - user:external-auditor
    expires: "2026-06-30T23:59:59Z"
```

### Multi-File Organization

```
acl/production/
├── 00-mode.yaml
├── 01-admins.yaml
├── 02-analysts.yaml
└── 03-sales.yaml
```

`00-mode.yaml`:
```yaml
mode: strict
grants:
  - target: warehouse
    principals:
      - user:superadmin
```

`01-admins.yaml`:
```yaml
grants:
  - target: warehouse
    principals:
      - group:admin
```

`02-analysts.yaml`:
```yaml
grants:
  - target: warehouse.analytics
    principals:
      - group:analysts
  - target: warehouse.public.customers
    principals:
      - group:analysts
```

`03-sales.yaml`:
```yaml
grants:
  - target: warehouse.public.orders
    principals:
      - group:sales
  - target: warehouse.public.products
    principals:
      - group:sales
```

### With Environment Variables

```yaml
mode: ${ACL_MODE}

grants:
  - target: ${ENV}_db.public.orders
    principals:
      - group:${TEAM_GROUP}
```

Run with: `ACL_MODE=strict ENV=production TEAM_GROUP=ops`

## SQL Support

### Supported Constructs

| SQL Construct | Supported |
|---|---|
| Simple SELECT with FROM | Yes |
| JOIN (INNER, LEFT, RIGHT, FULL, CROSS) | Yes |
| Subqueries (FROM, WHERE, HAVING, SELECT) | Yes |
| CTEs (WITH clauses) | Yes (CTE names excluded from table list) |
| UNION, INTERSECT, EXCEPT | Yes |
| Derived tables | Yes |
| Nested subqueries | Yes |

### Not Supported

| SQL Construct | Behavior |
|---|---|
| INSERT, UPDATE, DELETE | Rejected by ACL (only SELECT is authorized) |
| DDL (CREATE, ALTER, DROP) | Rejected by ACL |
| TABLESAMPLE | Not supported by the SQL parser |
| Some dialect-specific syntax | May cause parse errors |

> **Note**: The [default validator](guide.md#default-validator-rules) (which runs before ACL) independently blocks DROP statements and can restrict other statement types via [`VALIDATION_ALLOW_BY_DEFAULT`](configuration.md#statement-validation).

## See Also

- [Configuration Reference](configuration.md) — All environment variables
- [Connecting from DBeaver](dbeaver.md) — DBeaver setup with ACL considerations
- [Troubleshooting](troubleshooting.md) — ACL-related issues
- [Usage Guide](guide.md) — Architecture and deployment overview
