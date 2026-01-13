#!/bin/bash

# Configuration
export SL_GIZMO_ON_DEMAND_HOST="${SL_GIZMO_ON_DEMAND_HOST:-0.0.0.0}"
export SL_GIZMO_ON_DEMAND_PORT="${SL_GIZMO_ON_DEMAND_PORT:-10900}"
export SL_GIZMO_MIN_PORT="${SL_GIZMO_MIN_PORT:-11900}"
export SL_GIZMO_MAX_PORT="${SL_GIZMO_MAX_PORT:-12000}"
export SL_GIZMO_MAX_PROCESSES="${SL_GIZMO_MAX_PROCESSES:-10}"
export SL_GIZMO_API_KEY="${SL_GIZMO_API_KEY:-a_secret_api_key}"

export SL_GIZMO_DEFAULT_SCRIPT="${SL_GIZMO_DEFAULT_SCRIPT:-/Users/hayssams/git/public/gizmo-on-demand/local-start-gizmo.sh}"
export SL_GIZMO_PROXY_SCRIPT="${SL_GIZMO_PROXY_SCRIPT:-/Users/hayssams/git/public/gizmo-on-demand/local-start-proxy.sh}"

# Database connection settings for INIT_SQL_COMMANDS
export SL_DB_ID="${SL_DB_ID:-tpch2}"
export PG_HOST="${PG_HOST:-localhost}"
export PG_PORT="${PG_PORT:-5432}"
export PG_USERNAME="${PG_USERNAME:-postgres}"
export PG_PASSWORD="${PG_PASSWORD:-azizam}"
export SL_DATA_PATH="${SL_DATA_PATH:-/Users/hayssams/git/starlake-api/starlake-api-samples/100/177/ducklake_files/tpch2}"

# GizmoSQL credentials
export GIZMOSQL_USERNAME="${GIZMOSQL_USERNAME:-gizmosql_username}"
export GIZMOSQL_PASSWORD="${GIZMOSQL_PASSWORD:-gizmosql_password}"
export JWT_SECRET_KEY="${JWT_SECRET_KEY:-a_very_secret_key}"

echo "========================================="
echo "  GizmoSQL Process Manager"
echo "========================================="
echo ""
echo "Configuration:"
echo "  Manager:     $SL_GIZMO_ON_DEMAND_HOST:$SL_GIZMO_ON_DEMAND_PORT"
echo "  Port Range:  $SL_GIZMO_MIN_PORT-$SL_GIZMO_MAX_PORT"
echo "  Max Processes: $SL_GIZMO_MAX_PROCESSES"
echo "  Default Script: $SL_GIZMO_DEFAULT_SCRIPT"
echo "  Proxy Script: $SL_GIZMO_PROXY_SCRIPT"
echo ""
echo "========================================="
echo ""

# Check if JAR exists
JAR_PATH="distrib/gizmo-on-demand-assembly-0.1.0-SNAPSHOT.jar"

#AGENT_LIB="-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005"
if [ -f "$JAR_PATH" ]; then
    java -cp "$JAR_PATH" $AGENT_LIB ai.starlake.gizmo.ondemand.Main
else
    echo "Assembly JAR not found at $JAR_PATH. Running with sbt..."
    sbt "runMain ai.starlake.gizmo.ondemand.Main"
fi
