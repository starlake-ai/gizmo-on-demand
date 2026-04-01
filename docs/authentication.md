# Authentication Guide

Gizmo On-Demand supports multiple authentication backends. You can authenticate users against a PostgreSQL database, an identity provider like Keycloak, Google, Azure AD, or AWS Cognito, or use the built-in environment variable credentials.

This guide covers each option with step-by-step setup instructions.

---

## Table of Contents

- [How Authentication Works](#how-authentication-works)
- [Default Authentication (No Provider)](#default-authentication-no-provider)
- [PostgreSQL Database Authentication](#postgresql-database-authentication)
- [Keycloak Authentication](#keycloak-authentication)
- [Google OAuth Authentication](#google-oauth-authentication)
- [Azure AD Authentication](#azure-ad-authentication)
- [AWS Cognito Authentication](#aws-cognito-authentication)
- [Custom JWT Authentication](#custom-jwt-authentication)
- [Browser-Based OAuth/SSO (ADBC Clients)](#browser-based-oauthsso-adbc-clients)
- [Combining Multiple Providers](#combining-multiple-providers)
- [Role Extraction](#role-extraction)
- [Environment Variable Reference](#environment-variable-reference)

---

## How Authentication Works

When a client connects to the proxy, it sends credentials in one of two ways:

- **Username and password** (Basic auth or Flight handshake) -- the proxy validates them against configured providers in order, and assigns a role.
- **Bearer token** (JWT from an identity provider) -- the proxy verifies the token signature against the provider's public keys, and extracts the username and role from the token claims.

After successful authentication, the proxy mints an internal JWT and forwards it to the GizmoSQL backend. The backend trusts this JWT via a shared signing key (`JWT_SECRET_KEY`). The backend never sees the original password.

```
Client                       Proxy                        Backend
  |                            |                             |
  |-- username:password ------>|                             |
  |   or Bearer <token>        |                             |
  |                            |-- validate credentials ---->|
  |                            |   (DB, Keycloak, etc.)      |
  |                            |                             |
  |                            |-- Bearer <internal JWT> --->|
  |                            |   (signed with shared key)  |
  |<-- connection established -|<--- queries forwarded ----->|
```

**Important:** The proxy and backend must share the same `JWT_SECRET_KEY` for authentication to work.

---

## Default Authentication (No Provider)

When no authentication provider is configured, the proxy behaves as follows:

- Any username and password is accepted at the proxy level.
- Every user is assigned the `admin` role.
- The GizmoSQL backend validates the credentials using its own `GIZMOSQL_USERNAME` and `GIZMOSQL_PASSWORD`.

This is the default behavior and requires no additional configuration beyond the standard environment variables:

```bash
export GIZMOSQL_USERNAME=admin
export GIZMOSQL_PASSWORD=secret
export JWT_SECRET_KEY=my-shared-secret
```

**When to use:** Development, testing, or single-user deployments where the backend handles authentication.

---

## PostgreSQL Database Authentication

Store users, passwords, and roles in a PostgreSQL table. Passwords are hashed with BCrypt.

### Step 1: Create the database and users table

```sql
CREATE DATABASE gizmosql_auth;

\c gizmosql_auth

CREATE TABLE users (
    username VARCHAR(255) PRIMARY KEY,
    password VARCHAR(255) NOT NULL,   -- BCrypt hash
    role     VARCHAR(100) NOT NULL DEFAULT 'user'
);
```

### Step 2: Add users with BCrypt-hashed passwords

Generate a BCrypt hash:

```bash
python3 -c "import bcrypt; print(bcrypt.hashpw(b'mypassword', bcrypt.gensalt()).decode())"
```

Insert users:

```sql
INSERT INTO users (username, password, role) VALUES
    ('admin',   '$2b$12$...hash...', 'admin'),
    ('analyst', '$2b$12$...hash...', 'read'),
    ('viewer',  '$2b$12$...hash...', 'read');
```

A convenience script is provided at `scripts/setup-auth-db.sh` to automate this setup.

### Step 3: Configure the proxy

```bash
export AUTH_DB_ENABLED=true
export AUTH_DB_JDBC_URL="jdbc:postgresql://localhost:5432/gizmosql_auth"
export AUTH_DB_USERNAME=postgres
export AUTH_DB_PASSWORD=pgpass
export JWT_SECRET_KEY=my-shared-secret
```

### Optional: Customize table and column names

If your existing user table has different column names:

```bash
export AUTH_DB_USERS_TABLE=my_users
export AUTH_DB_USERNAME_COLUMN=email
export AUTH_DB_PASSWORD_COLUMN=password_hash
export AUTH_DB_ROLE_COLUMN=access_level
```

### How it works

1. Client connects with `username` and `password`.
2. Proxy queries: `SELECT password, role FROM users WHERE username = ?`
3. Proxy verifies the password against the BCrypt hash.
4. On success, the user is assigned the role from the database.
5. Proxy mints an internal JWT with the real role and forwards it to the backend.

---

## Keycloak Authentication

Authenticate users against a Keycloak realm. Supports both Bearer tokens (recommended) and username/password via the Resource Owner Password Credentials (ROPC) flow.

### Step 1: Configure Keycloak

1. Create a realm (e.g., `gizmosql`).
2. Create a client:
   - **Client ID:** `gizmosql-proxy`
   - **Client authentication:** ON (confidential client)
   - **Authentication flow:** enable **Direct access grants** (required for username/password flow)
3. Copy the **Client Secret** from the Credentials tab.
4. Create users and assign roles.

### Step 2: Configure the proxy

```bash
export AUTH_KEYCLOAK_ENABLED=true
export AUTH_KEYCLOAK_BASE_URL=https://keycloak.example.com
export AUTH_KEYCLOAK_REALM=gizmosql
export AUTH_KEYCLOAK_CLIENT_ID=gizmosql-proxy
export AUTH_KEYCLOAK_CLIENT_SECRET=your-client-secret
export JWT_SECRET_KEY=my-shared-secret
```

### Connecting with a Bearer token (recommended)

The client obtains a token from Keycloak and sends it to the proxy:

```bash
# Get a token from Keycloak
TOKEN=$(curl -s -X POST \
  "https://keycloak.example.com/realms/gizmosql/protocol/openid-connect/token" \
  -d "grant_type=client_credentials" \
  -d "client_id=gizmosql-proxy" \
  -d "client_secret=your-client-secret" \
  | jq -r .access_token)

# Use the token in your JDBC connection
# Set the "token" property to the value of $TOKEN
```

The proxy validates the token signature against Keycloak's public keys (JWKS endpoint) and extracts the username and role from the token claims.

### Connecting with username and password

```
JDBC URL: jdbc:arrow-flight-sql://proxy-host:11900?useEncryption=true
Username: alice
Password: alice-password
```

The proxy sends the credentials to Keycloak's token endpoint using the ROPC grant, receives a token, and extracts the role.

### Role extraction

Keycloak stores realm roles under `realm_access.roles` in the JWT. The proxy handles this automatically. To emit a flat `role` claim instead, add a client mapper in Keycloak:

1. Go to **Client > gizmosql-proxy > Client scopes > Dedicated scope**.
2. Add mapper: **User Realm Role**, Token Claim Name: `role`.

---

## Google OAuth Authentication

Authenticate users with their Google accounts. Clients must obtain a token externally (Google does not support the password grant).

### Step 1: Create OAuth credentials in Google Cloud

1. Go to [APIs & Services > Credentials](https://console.cloud.google.com/apis/credentials).
2. Create an **OAuth 2.0 Client ID** (Web application).
3. Note the **Client ID** and **Client Secret**.

### Step 2: Configure the proxy

```bash
export AUTH_GOOGLE_ENABLED=true
export AUTH_GOOGLE_CLIENT_ID=123456789.apps.googleusercontent.com
export AUTH_GOOGLE_CLIENT_SECRET=GOCSPX-xxxxx
export JWT_SECRET_KEY=my-shared-secret
```

### Connecting

Clients must obtain a Google identity token externally, then send it as a Bearer token:

```bash
# Using gcloud CLI
TOKEN=$(gcloud auth print-identity-token --audiences=123456789.apps.googleusercontent.com)

# Use the token in your JDBC connection (set the "token" property)
```

### Username and role

- **Username:** extracted from the `email` claim (e.g., `alice@company.com`).
- **Role:** Google tokens do not include a `role` claim, so the default role is `user`. To assign roles, combine with [database authentication](#combining-multiple-providers).

---

## Azure AD Authentication

Authenticate users with Microsoft Entra ID (Azure AD). Supports both Bearer tokens and username/password (ROPC).

### Step 1: Register an application in Azure

1. Go to [Azure Portal > App registrations](https://portal.azure.com/#view/Microsoft_AAD_RegisteredApps).
2. Register a new application.
3. Note the **Application (client) ID** and **Directory (tenant) ID**.
4. Create a client secret under **Certificates & secrets**.
5. Under **API permissions**, ensure `openid`, `profile`, and `email` are granted.

### Step 2: Configure the proxy

```bash
export AUTH_AZURE_ENABLED=true
export AUTH_AZURE_TENANT_ID=your-tenant-id
export AUTH_AZURE_CLIENT_ID=your-client-id
export AUTH_AZURE_CLIENT_SECRET=your-client-secret
export JWT_SECRET_KEY=my-shared-secret
```

### Connecting with a Bearer token

```bash
# Get a token from Azure AD
TOKEN=$(curl -s -X POST \
  "https://login.microsoftonline.com/YOUR_TENANT/oauth2/v2.0/token" \
  -d "grant_type=client_credentials" \
  -d "client_id=YOUR_CLIENT_ID" \
  -d "client_secret=YOUR_SECRET" \
  -d "scope=api://YOUR_CLIENT_ID/.default" \
  | jq -r .access_token)
```

### Connecting with username and password

```
Username: alice@company.onmicrosoft.com
Password: user-password
```

The proxy sends the credentials to Azure's token endpoint using the ROPC grant.

**Note:** ROPC must be enabled in your Azure AD tenant settings. Microsoft recommends using interactive flows instead.

---

## AWS Cognito Authentication

Authenticate users against an AWS Cognito User Pool. Supports Bearer token validation.

### Step 1: Configure a Cognito User Pool

1. Create a User Pool in the [AWS Console](https://console.aws.amazon.com/cognito/).
2. Create an **App Client** (note the Client ID).
3. Note your **User Pool ID** and **Region**.

### Step 2: Configure the proxy

```bash
export AUTH_AWS_ENABLED=true
export AUTH_AWS_REGION=us-east-1
export AUTH_AWS_USER_POOL_ID=us-east-1_aBcDeFgH
export AUTH_AWS_CLIENT_ID=your-app-client-id
export JWT_SECRET_KEY=my-shared-secret
```

### Connecting

Clients must obtain a Cognito token externally:

```bash
# Using AWS CLI
TOKEN=$(aws cognito-idp initiate-auth \
  --auth-flow USER_PASSWORD_AUTH \
  --client-id YOUR_CLIENT_ID \
  --auth-parameters USERNAME=alice,PASSWORD=secret \
  --query 'AuthenticationResult.IdToken' --output text)
```

### Username and role

- **Username:** extracted from `preferred_username`, `email`, or `sub` claim.
- **Role:** extracted from the `cognito:groups` claim if present, otherwise defaults to `user`.

---

## Custom JWT Authentication

Validate JWT tokens signed with a shared secret (HMAC) or an RSA public key. Use this when your tokens come from a custom identity system rather than a standard OIDC provider.

### HMAC (shared secret)

```bash
export AUTH_JWT_SECRET_KEY=my-signing-secret
export AUTH_JWT_ISSUER=my-auth-server      # optional: verify issuer claim
export AUTH_JWT_AUDIENCE=gizmosql-proxy     # optional: verify audience claim
export JWT_SECRET_KEY=my-shared-secret
```

### RSA (public key)

```bash
export AUTH_JWT_PUBLIC_KEY_PATH=/path/to/public-key.pem
export AUTH_JWT_ISSUER=my-auth-server
export AUTH_JWT_AUDIENCE=gizmosql-proxy
export JWT_SECRET_KEY=my-shared-secret
```

The public key file must be in PEM format:

```
-----BEGIN PUBLIC KEY-----
MIIBIjANBgkqhk...
-----END PUBLIC KEY-----
```

### Token requirements

The JWT must contain:
- `sub` claim (username), or `email`, or `preferred_username`
- `role` claim (optional, defaults to `user`)

---

## Browser-Based OAuth/SSO (ADBC Clients)

The proxy can run a built-in OAuth HTTP server that enables browser-based login for ADBC and CLI clients. This mimics the GizmoSQL server's native SSO flow.

### Prerequisites

At least one OIDC provider must be enabled (Keycloak, Google, or Azure AD).

### Configuration

```bash
# Enable one of: Keycloak, Google, or Azure (see sections above)
export AUTH_KEYCLOAK_ENABLED=true
export AUTH_KEYCLOAK_BASE_URL=https://keycloak.example.com
export AUTH_KEYCLOAK_REALM=gizmosql
export AUTH_KEYCLOAK_CLIENT_ID=gizmosql-proxy
export AUTH_KEYCLOAK_CLIENT_SECRET=your-secret

# Enable the OAuth HTTP server
export AUTH_OAUTH_ENABLED=true
export AUTH_OAUTH_PORT=31339
```

### How it works

1. The client sends a discovery request (`username="__discover__"`).
2. The proxy returns the OAuth HTTP server URL.
3. The client calls `/oauth/initiate` to start a session.
4. A browser window opens for the user to log in with their identity provider.
5. After login, the identity provider redirects back to `/oauth/callback`.
6. The client polls `/oauth/token/{uuid}` until the token is ready.
7. The client authenticates with `username="token"` and the received token as the password.

### ADBC Python driver example

```python
import adbc_driver_gizmosql.dbapi as gizmo

conn = gizmo.connect(
    host="proxy-host",
    port=11900,
    auth_type="external",       # triggers browser-based OAuth
    tls_skip_verify=True
)
```

### OAuth HTTP server options

| Variable | Default | Description |
|---|---|---|
| `AUTH_OAUTH_ENABLED` | `false` | Enable the OAuth HTTP server |
| `AUTH_OAUTH_PORT` | `31339` | HTTP port for the OAuth server |
| `AUTH_OAUTH_BASE_URL` | auto | Public URL for redirects (auto-derived from port if empty) |
| `AUTH_OAUTH_SCOPES` | `openid profile email` | OAuth scopes to request |
| `AUTH_OAUTH_SESSION_TIMEOUT` | `900` | Pending session timeout in seconds |
| `AUTH_OAUTH_DISABLE_TLS` | `true` | Disable TLS on the OAuth HTTP server |

---

## Combining Multiple Providers

You can enable multiple authentication providers at the same time. The proxy tries them in order:

**For username/password (Basic auth):**

1. PostgreSQL database
2. Keycloak (ROPC)
3. Google (ROPC -- not supported by Google, will be skipped)
4. Azure AD (ROPC)

The first provider that accepts the credentials wins.

**For Bearer tokens:**

1. Custom JWT (HMAC or RSA)
2. Keycloak (JWKS)
3. Google (JWKS)
4. Azure AD (JWKS)
5. AWS Cognito (JWKS)

### Example: Database + Keycloak

Use the database for local service accounts and Keycloak for human users:

```bash
# Database for service accounts
export AUTH_DB_ENABLED=true
export AUTH_DB_JDBC_URL="jdbc:postgresql://localhost:5432/gizmosql_auth"
export AUTH_DB_USERNAME=postgres
export AUTH_DB_PASSWORD=pgpass

# Keycloak for human users
export AUTH_KEYCLOAK_ENABLED=true
export AUTH_KEYCLOAK_BASE_URL=https://keycloak.example.com
export AUTH_KEYCLOAK_REALM=gizmosql
export AUTH_KEYCLOAK_CLIENT_ID=gizmosql-proxy
export AUTH_KEYCLOAK_CLIENT_SECRET=your-secret

export JWT_SECRET_KEY=my-shared-secret
```

Service accounts authenticate with username/password (checked against the database). Human users authenticate with Keycloak tokens (verified against JWKS).

### Example: Google + Database for role mapping

Since Google tokens don't include roles, use the database as a role directory:

```bash
export AUTH_GOOGLE_ENABLED=true
export AUTH_GOOGLE_CLIENT_ID=123456789.apps.googleusercontent.com
export AUTH_GOOGLE_CLIENT_SECRET=GOCSPX-xxxxx

export AUTH_DB_ENABLED=true
export AUTH_DB_JDBC_URL="jdbc:postgresql://localhost:5432/gizmosql_auth"
export AUTH_DB_USERNAME=postgres
export AUTH_DB_PASSWORD=pgpass
```

```sql
-- Use email as username to map Google users to roles
INSERT INTO users (username, password, role) VALUES
    ('alice@company.com', '$2b$12$placeholder', 'admin'),
    ('bob@company.com',   '$2b$12$placeholder', 'read');
```

### When no external provider is configured

If no providers are enabled but `GIZMOSQL_USERNAME` is set, the proxy uses the legacy env-var authentication: credentials are checked against `GIZMOSQL_USERNAME` and `GIZMOSQL_PASSWORD`, and the user gets the `admin` role. This only activates when no other basic auth provider is configured.

---

## Role Extraction

The proxy extracts the user's role from JWT token claims. The claim name is configurable:

```bash
export AUTH_ROLE_CLAIM=role    # default
```

The proxy checks claims in this order:

1. The configured claim name (e.g., `role`) as a string
2. The configured claim name as an array (takes the first value)
3. `roles` claim as an array
4. `realm_access.roles` (Keycloak nested structure)
5. `cognito:groups` (AWS Cognito)
6. Default: `user`

---

## Environment Variable Reference

### Core

| Variable | Default | Description |
|---|---|---|
| `JWT_SECRET_KEY` | `a_very_secret_key` | **Must match on proxy and backend.** Shared secret for JWT signing. |
| `AUTH_ROLE_CLAIM` | `role` | JWT claim name to extract the user's role from |

### PostgreSQL Database

| Variable | Default | Description |
|---|---|---|
| `AUTH_DB_ENABLED` | `false` | Enable database authentication |
| `AUTH_DB_JDBC_URL` | `jdbc:postgresql://localhost:5432/gizmosql_auth` | JDBC connection URL |
| `AUTH_DB_USERNAME` | `postgres` | Database connection username |
| `AUTH_DB_PASSWORD` | (empty) | Database connection password |
| `AUTH_DB_USERS_TABLE` | `users` | Table containing user accounts |
| `AUTH_DB_USERNAME_COLUMN` | `username` | Column for the username |
| `AUTH_DB_PASSWORD_COLUMN` | `password` | Column for the BCrypt password hash |
| `AUTH_DB_ROLE_COLUMN` | `role` | Column for the user's role |

### Keycloak

| Variable | Default | Description |
|---|---|---|
| `AUTH_KEYCLOAK_ENABLED` | `false` | Enable Keycloak authentication |
| `AUTH_KEYCLOAK_BASE_URL` | (empty) | Keycloak server URL (e.g., `https://keycloak.example.com`) |
| `AUTH_KEYCLOAK_REALM` | (empty) | Keycloak realm name |
| `AUTH_KEYCLOAK_CLIENT_ID` | (empty) | OAuth client ID |
| `AUTH_KEYCLOAK_CLIENT_SECRET` | (empty) | OAuth client secret |

### Google

| Variable | Default | Description |
|---|---|---|
| `AUTH_GOOGLE_ENABLED` | `false` | Enable Google OAuth authentication |
| `AUTH_GOOGLE_CLIENT_ID` | (empty) | Google OAuth client ID |
| `AUTH_GOOGLE_CLIENT_SECRET` | (empty) | Google OAuth client secret |

### Azure AD

| Variable | Default | Description |
|---|---|---|
| `AUTH_AZURE_ENABLED` | `false` | Enable Azure AD authentication |
| `AUTH_AZURE_TENANT_ID` | (empty) | Azure AD tenant (directory) ID |
| `AUTH_AZURE_CLIENT_ID` | (empty) | Azure AD application (client) ID |
| `AUTH_AZURE_CLIENT_SECRET` | (empty) | Azure AD client secret |

### AWS Cognito

| Variable | Default | Description |
|---|---|---|
| `AUTH_AWS_ENABLED` | `false` | Enable AWS Cognito authentication |
| `AUTH_AWS_REGION` | (empty) | AWS region (e.g., `us-east-1`) |
| `AUTH_AWS_USER_POOL_ID` | (empty) | Cognito User Pool ID |
| `AUTH_AWS_CLIENT_ID` | (empty) | Cognito App Client ID |

### Custom JWT

| Variable | Default | Description |
|---|---|---|
| `AUTH_JWT_SECRET_KEY` | (empty) | HMAC shared secret for JWT verification |
| `AUTH_JWT_PUBLIC_KEY_PATH` | (empty) | Path to RSA public key PEM file |
| `AUTH_JWT_ISSUER` | (empty) | Expected `iss` claim (optional) |
| `AUTH_JWT_AUDIENCE` | (empty) | Expected `aud` claim (optional) |

### OAuth/SSO (Browser Flow)

| Variable | Default | Description |
|---|---|---|
| `AUTH_OAUTH_ENABLED` | `false` | Enable the OAuth HTTP server for browser-based login |
| `AUTH_OAUTH_PORT` | `31339` | HTTP port for the OAuth server |
| `AUTH_OAUTH_BASE_URL` | (auto) | Public base URL for OAuth redirects |
| `AUTH_OAUTH_SCOPES` | `openid profile email` | OAuth scopes to request |
| `AUTH_OAUTH_SESSION_TIMEOUT` | `900` | Session timeout in seconds |
| `AUTH_OAUTH_DISABLE_TLS` | `true` | Disable TLS on the OAuth HTTP server |