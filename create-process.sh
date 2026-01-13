#!/bin/bash

# Configuration
PROCESS_MANAGER_URL="${PROCESS_MANAGER_URL:-http://localhost:10900}"
API_KEY="${SL_GIZMO_API_KEY:-a_secret_api_key}"
PROCESS_NAME="${1:-my-gizmo-session}"

# Database connection settings
SL_DB_ID="${SL_DB_ID:-tpch0001}"
PG_HOST="${PG_HOST:-localhost}"
PG_PORT="${PG_PORT:-5432}"
PG_USERNAME="${PG_USERNAME:-postgres}"
PG_PASSWORD="${PG_PASSWORD:-azizam}"
SL_DATA_PATH="${SL_DATA_PATH:-/Users/hayssams/git/starlake-api/starlake-api-samples/100/101/ducklake_files/tpch0001}"

# GizmoSQL credentials
GIZMOSQL_USERNAME="${GIZMOSQL_USERNAME:-gizmosql_username}"
GIZMOSQL_PASSWORD="${GIZMOSQL_PASSWORD:-gizmosql_password}"
JWT_SECRET_KEY="${JWT_SECRET_KEY:-a_very_secret_key}"

# Build the JSON payload
# In addition to the variables below, the user may use AWS_KEY, AWS_SECRET AWS_REGION and AWS_SCOPE
# This is useful if ducklake files are store in a S3 filesystem
JSON_PAYLOAD=$(cat <<EOF
{
    "processName": "$PROCESS_NAME",
    "arguments": {
        "SL_DB_ID": "$SL_DB_ID",
        "PG_HOST": "$PG_HOST",
        "PG_PORT": "$PG_PORT",
        "PG_USERNAME": "$PG_USERNAME",
        "PG_PASSWORD": "$PG_PASSWORD",
        "SL_DATA_PATH": "$SL_DATA_PATH",
        "GIZMOSQL_USERNAME": "$GIZMOSQL_USERNAME",
        "GIZMOSQL_PASSWORD": "$GIZMOSQL_PASSWORD",
        "GIZMOSQL_LOG_LEVEL": "debug",
        "DATABASE_FILENAME": ":memory:",
        "JWT_SECRET_KEY": "$JWT_SECRET_KEY",
        "SL_GIZMO_IDLE_TIMEOUT": "-1"
    }
}
EOF
)

# Build the curl command
if [ -n "$API_KEY" ]; then
    # With API key authentication
    curl -X POST "$PROCESS_MANAGER_URL/api/process/start" \
        -H "Content-Type: application/json" \
        -H "X-API-Key: $API_KEY" \
        -d "$JSON_PAYLOAD"
else
    # Without API key authentication
    curl -X POST "$PROCESS_MANAGER_URL/api/process/start" \
        -H "Content-Type: application/json" \
        -d "$JSON_PAYLOAD"
fi

echo ""
