#!/bin/bash

# Docker Process Manager Startup Script
set -e

# Configuration
CONTAINER_NAME="gizmo-on-demand"
IMAGE_NAME="starlakeai/gizmo-on-demand:latest"

# Process Manager Configuration
export SL_GIZMO_ON_DEMAND_HOST="${SL_GIZMO_ON_DEMAND_HOST:-0.0.0.0}"
export SL_GIZMO_ON_DEMAND_PORT="${SL_GIZMO_ON_DEMAND_PORT:-10900}"
export SL_GIZMO_MIN_PORT="${SL_GIZMO_MIN_PORT:-11900}"
export SL_GIZMO_MAX_PORT="${SL_GIZMO_MAX_PORT:-12000}"
export SL_GIZMO_MAX_PROCESSES="${SL_GIZMO_MAX_PROCESSES:-10}"
export SL_GIZMO_API_KEY="${SL_GIZMO_API_KEY:-a_secret_api_key}"

# Database connection settings
export SL_DB_ID="${SL_DB_ID:-tpch2}"
export PG_HOST="${PG_HOST:-host.docker.internal}"
export PG_PORT="${PG_PORT:-5432}"
export PG_USERNAME="${PG_USERNAME:-postgres}"
export PG_PASSWORD="${PG_PASSWORD:-azizam}"

# GizmoSQL credentials
export GIZMOSQL_USERNAME="${GIZMOSQL_USERNAME:-gizmosql_username}"
export GIZMOSQL_PASSWORD="${GIZMOSQL_PASSWORD:-gizmosql_password}"
export JWT_SECRET_KEY="${JWT_SECRET_KEY:-a_very_secret_key}"

# Host port to expose the process manager
export HOST_PORT="${HOST_PORT:-31339}"

export SL_GIZMO_IDLE_TIMEOUT="${SL_GIZMO_IDLE_TIMEOUT:-20}"

echo "========================================="
echo "  GizmoSQL Process Manager (Docker)"
echo "========================================="
echo ""
echo "Configuration:"
echo "  Container:   $CONTAINER_NAME"
echo "  Image:       $IMAGE_NAME"
echo "  Host Port:   $HOST_PORT -> $SL_GIZMO_ON_DEMAND_PORT"
echo "  Port Range:  $SL_GIZMO_MIN_PORT-$SL_GIZMO_MAX_PORT"
echo "  Max Processes: $SL_GIZMO_MAX_PROCESSES"
echo ""
echo "========================================="
echo ""

# Stop and remove existing container if running
if docker ps -a --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
    echo "Stopping existing container '$CONTAINER_NAME'..."
    docker stop "$CONTAINER_NAME" 2>/dev/null || true
    docker rm "$CONTAINER_NAME" 2>/dev/null || true
fi

# add here a variable that contains multiple absolute folders
# that will be used each to build the volume mounts required
# These are required when running ducklake stores files locally
SL_GIZMO_DATA_PATHS="${SL_GIZMO_DATA_PATHS:-$HOME/git/starlake-api/starlake-api-samples}"

for path in $SL_GIZMO_DATA_PATHS; do
    VOLUMES="$VOLUMES -v $path:$path"
done

# Run the container
docker run --rm \
    --name "$CONTAINER_NAME" \
    $VOLUMES \
    -p "${HOST_PORT}:${SL_GIZMO_ON_DEMAND_PORT}" \
    -p "${SL_GIZMO_MIN_PORT}-${SL_GIZMO_MAX_PORT}:${SL_GIZMO_MIN_PORT}-${SL_GIZMO_MAX_PORT}" \
    -e SL_GIZMO_ON_DEMAND_HOST="$SL_GIZMO_ON_DEMAND_HOST" \
    -e SL_GIZMO_ON_DEMAND_PORT="$SL_GIZMO_ON_DEMAND_PORT" \
    -e SL_GIZMO_MIN_PORT="$SL_GIZMO_MIN_PORT" \
    -e SL_GIZMO_MAX_PORT="$SL_GIZMO_MAX_PORT" \
    -e SL_GIZMO_MAX_PROCESSES="$SL_GIZMO_MAX_PROCESSES" \
    -e SL_GIZMO_API_KEY="$SL_GIZMO_API_KEY" \
    -e SL_DB_ID="$SL_DB_ID" \
    -e PG_HOST="$PG_HOST" \
    -e PG_PORT="$PG_PORT" \
    -e PG_USERNAME="$PG_USERNAME" \
    -e PG_PASSWORD="$PG_PASSWORD" \
    -e GIZMOSQL_USERNAME="$GIZMOSQL_USERNAME" \
    -e GIZMOSQL_PASSWORD="$GIZMOSQL_PASSWORD" \
    -e JWT_SECRET_KEY="$JWT_SECRET_KEY" \
    -e SL_GIZMO_IDLE_TIMEOUT="$SL_GIZMO_IDLE_TIMEOUT" \
    "$IMAGE_NAME"

