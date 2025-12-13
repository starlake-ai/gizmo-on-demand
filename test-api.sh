#!/bin/bash

# Test script for Process Manager API

BASE_URL="http://localhost:10900"
API_KEY="${SL_GIZMO_API_KEY:-}"

# Set up headers
if [ -n "$API_KEY" ]; then
  AUTH_HEADER="-H X-API-Key: $API_KEY"
  echo "=== Process Manager API Test (with authentication) ==="
else
  AUTH_HEADER=""
  echo "=== Process Manager API Test (no authentication) ==="
fi
echo ""

echo "1. Starting process 'app1' with required environment variables..."
curl -X POST "$BASE_URL/api/process/start" \
  -H "Content-Type: application/json" \
  $AUTH_HEADER \
  -d '{"processName": "app1", "arguments": {"GIZMOSQL_USERNAME": "admin", "GIZMOSQL_PASSWORD": "pass123", "SL_PROJECT_ID": "project-001", "SL_DATA_PATH": "/data/starlake", "PG_USERNAME": "postgres", "PG_PASSWORD": "pgpass", "PG_PORT": "5432", "PG_HOST": "localhost"}}' \
  -w "\n\n"

echo "2. Starting process 'app2' with required and optional environment variables..."
curl -X POST "$BASE_URL/api/process/start" \
  -H "Content-Type: application/json" \
  $AUTH_HEADER \
  -d '{"processName": "app2", "arguments": {"GIZMOSQL_USERNAME": "user2", "GIZMOSQL_PASSWORD": "secret456", "SL_PROJECT_ID": "project-002", "SL_DATA_PATH": "/data/starlake2", "PG_USERNAME": "postgres", "PG_PASSWORD": "pgpass2", "PG_PORT": "5433", "PG_HOST": "db.example.com", "LOG_LEVEL": "DEBUG"}}' \
  -w "\n\n"

echo "3. Listing all processes..."
curl "$BASE_URL/api/process/list" \
  $AUTH_HEADER \
  -w "\n\n"

echo "4. Restarting process 'app1'..."
curl -X POST "$BASE_URL/api/process/restart" \
  -H "Content-Type: application/json" \
  $AUTH_HEADER \
  -d '{"processName": "app1"}' \
  -w "\n\n"

echo "5. Listing all processes again..."
curl "$BASE_URL/api/process/list" \
  $AUTH_HEADER \
  -w "\n\n"

echo "6. Stopping process 'app1'..."
curl -X POST "$BASE_URL/api/process/stop" \
  -H "Content-Type: application/json" \
  $AUTH_HEADER \
  -d '{"processName": "app1"}' \
  -w "\n\n"

echo "7. Final process list..."
curl "$BASE_URL/api/process/list" \
  $AUTH_HEADER \
  -w "\n\n"

echo "8. Testing stopAll operation..."
curl -X POST "$BASE_URL/api/process/stopAll" \
  $AUTH_HEADER \
  -w "\n\n"

echo "9. Final process list (should be empty)..."
curl "$BASE_URL/api/process/list" \
  $AUTH_HEADER \
  -w "\n\n"

echo "=== Test Complete ==="

