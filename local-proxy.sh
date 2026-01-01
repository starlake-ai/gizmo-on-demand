export SL_DB_ID="${SL_DB_ID:-tpch0001}"
export PG_HOST="${PG_HOST:-host.docker.internal}"
export PG_PORT=${PG_PORT:-5432}
export PG_USERNAME="${PG_USERNAME:-postgres}"
export PG_PASSWORD="${PG_PASSWORD:-azizam}"
export SL_DATA_PATH="${SL_DATA_PATH:-/Users/hayssams/git/starlake-api/starlake-api-samples/100/101/ducklake_files/tpch0001}"

export GIZMO_SERVER_PORT=${GIZMO_SERVER_PORT:-31337}
export GIZMOSQL_USERNAME="${GIZMOSQL_USERNAME:-gizmosql_username}"
export GIZMOSQL_PASSWORD="${GIZMOSQL_PASSWORD:-gizmosql_password}"
export JWT_SECRET_KEY="${JWT_SECRET_KEY:-a_very_secret_key}"

export PROXY_PORT=${PROXY_PORT:-31338}
export SL_GIZMO_DEFAULT_SCRIPT="${SL_GIZMO_DEFAULT_SCRIPT:-/Users/hayssams/git/public/gizmo-on-demand/local-start-gizmo.sh}"
export DEFAULT_INIT_SCRIPT="CREATE OR REPLACE PERSISTENT SECRET pg_tpch0001 (TYPE postgres, HOST 'host.docker.internal',PORT 5432, DATABASE tpch0001, USER 'postgres',PASSWORD 'azizam');CREATE OR REPLACE PERSISTENT SECRET tpch0001 (TYPE ducklake, METADATA_PATH '',DATA_PATH '/Users/hayssams/git/starlake-api/starlake-api-samples/100/101/ducklake_files/tpch0001', METADATA_PARAMETERS MAP {'TYPE': 'postgres', 'SECRET': 'pg_tpch0001'});ATTACH IF NOT EXISTS 'ducklake:tpch0001' AS tpch0001 (READ_ONLY); USE tpch0001;"


# SL_GIZMO_IDLE_TIMEOUT takes the following values
# >0: Timeout in seconds, the gizmo backend server is started on the first request and is stopped after the timeout period if there are no requests
# <0: No timeout (default), the gizmo backend server is started on the first request and is never stopped
# =0: The gizmo backend server is started on the first request and is stopped immediately after the request is processed
export SL_GIZMO_IDLE_TIMEOUT=20

#export INIT_SQL_COMMANDS="${INIT_SQL_COMMANDS:-$DEFAULT_INIT_SCRIPT}"

# Trap Ctrl+C and forward to child process
trap 'kill -INT $PROXY_PID 2>/dev/null; exit' INT TERM

# run the proxy server
./local-start-proxy.sh &
PROXY_PID=$!

# Wait for the proxy server
wait $PROXY_PID

