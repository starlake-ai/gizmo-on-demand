#!/bin/bash

# Configuration
PROCESS_MANAGER_URL="${PROCESS_MANAGER_URL:-http://localhost:10900}"
API_KEY="${SL_GIZMO_API_KEY:-a_secret_api_key}"
PROCESS_NAME="${1:-my-gizmo-session}"

# Build the curl command
if [ -n "$API_KEY" ]; then
    curl -X POST "$PROCESS_MANAGER_URL/api/process/stop" \
        -H "Content-Type: application/json" \
        -H "X-API-Key: $API_KEY" \
        -d "{\"processName\": \"$PROCESS_NAME\"}"
else
    curl -X POST "$PROCESS_MANAGER_URL/api/process/stop" \
        -H "Content-Type: application/json" \
        -d "{\"processName\": \"$PROCESS_NAME\"}"
fi

echo ""
