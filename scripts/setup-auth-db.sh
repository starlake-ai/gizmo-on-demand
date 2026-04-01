#!/usr/bin/env bash
#
# Sets up a PostgreSQL database for proxy authentication.
# Creates the database, users table, and sample users with BCrypt-hashed passwords.
#
# Usage:
#   ./scripts/setup-auth-db.sh
#
# Environment variables (all optional, with defaults):
#   PG_HOST       PostgreSQL host       (default: localhost)
#   PG_PORT       PostgreSQL port       (default: 5432)
#   PG_USERNAME   PostgreSQL admin user (default: postgres)
#   PG_PASSWORD   PostgreSQL password   (default: "")
#   AUTH_DB_NAME  Database to create    (default: gizmosql_auth)

set -euo pipefail

PG_HOST="${PG_HOST:-localhost}"
PG_PORT="${PG_PORT:-5432}"
PG_USERNAME="${PG_USERNAME:-postgres}"
AUTH_DB_NAME="${AUTH_DB_NAME:-gizmosql_auth}"

# Export for psql
export PGHOST="$PG_HOST"
export PGPORT="$PG_PORT"
export PGUSER="$PG_USERNAME"
if [ -n "${PG_PASSWORD:-}" ]; then
  export PGPASSWORD="$PG_PASSWORD"
fi

echo "==> Connecting to PostgreSQL at $PG_HOST:$PG_PORT as $PG_USERNAME"

# Create database if it doesn't exist
if psql -lqt | cut -d \| -f 1 | grep -qw "$AUTH_DB_NAME"; then
  echo "==> Database '$AUTH_DB_NAME' already exists"
else
  echo "==> Creating database '$AUTH_DB_NAME'"
  createdb "$AUTH_DB_NAME"
fi

echo "==> Creating users table and inserting sample users"

# Generate BCrypt hashes for sample passwords using python3
# Requires: pip install bcrypt
ADMIN_HASH=$(python3 -c "import bcrypt; print(bcrypt.hashpw(b'admin123', bcrypt.gensalt()).decode())")
ANALYST_HASH=$(python3 -c "import bcrypt; print(bcrypt.hashpw(b'analyst123', bcrypt.gensalt()).decode())")
VIEWER_HASH=$(python3 -c "import bcrypt; print(bcrypt.hashpw(b'viewer123', bcrypt.gensalt()).decode())")

psql -d "$AUTH_DB_NAME" <<SQL
-- Create users table
CREATE TABLE IF NOT EXISTS users (
    username VARCHAR(255) PRIMARY KEY,
    password VARCHAR(255) NOT NULL,
    role     VARCHAR(100) NOT NULL DEFAULT 'user'
);

-- Insert sample users (ignore conflicts if they already exist)
INSERT INTO users (username, password, role) VALUES
    ('admin',   '$ADMIN_HASH',   'admin'),
    ('analyst', '$ANALYST_HASH', 'read'),
    ('viewer',  '$VIEWER_HASH',  'read')
ON CONFLICT (username) DO UPDATE
    SET password = EXCLUDED.password,
        role     = EXCLUDED.role;
SQL

echo ""
echo "==> Done. Sample users created:"
echo ""
echo "  username  | password    | role"
echo "  ----------|-------------|------"
echo "  admin     | admin123    | admin"
echo "  analyst   | analyst123  | read"
echo "  viewer    | viewer123   | read"
echo ""
echo "==> To enable database authentication, set these environment variables:"
echo ""
echo "  export AUTH_DB_ENABLED=true"
echo "  export AUTH_DB_JDBC_URL=jdbc:postgresql://$PG_HOST:$PG_PORT/$AUTH_DB_NAME"
echo "  export AUTH_DB_USERNAME=$PG_USERNAME"
echo "  export AUTH_DB_PASSWORD=${PG_PASSWORD:-<your_pg_password>}"