#!/bin/bash

# Configuration
PROCESS_MANAGER_URL="${PROCESS_MANAGER_URL:-http://localhost:10900}"
API_KEY="${SL_GIZMO_API_KEY:-a_secret_api_key}"

# Build the curl command
if [ -n "$API_KEY" ]; then
    curl -X GET "$PROCESS_MANAGER_URL/api/process/list" \
        -H "X-API-Key: $API_KEY"
else
    curl -X GET "$PROCESS_MANAGER_URL/api/process/list"
fi

echo ""
