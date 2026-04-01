#!/bin/bash
set -e

# Pre-download DuckDB extensions for Docker (workaround: DuckDB's internal HTTP
# client fails to download extensions inside Docker, while curl/wget succeed)
# Extract DuckDB version (not GizmoSQL version) from the extensions directory inside the image
DUCKDB_VERSION=$(docker run --rm --entrypoint /bin/sh starlakeai/gizmo-on-demand:latest -c \
  "ls /home/app_user/.duckdb/extensions/ 2>/dev/null | grep -oE 'v[0-9]+\.[0-9]+\.[0-9]+' | head -1" \
  || echo "v1.4.3")
PLATFORM=$(docker run --rm --entrypoint uname starlakeai/gizmo-on-demand:latest -m 2>/dev/null)
case "$PLATFORM" in
    aarch64) PLATFORM="linux_arm64" ;;
    x86_64)  PLATFORM="linux_amd64" ;;
esac

EXT_CACHE_DIR="${HOME}/.duckdb-ext-cache/${DUCKDB_VERSION}/${PLATFORM}"
EXTENSIONS="ducklake postgres_scanner"

for ext in $EXTENSIONS; do
    EXT_FILE="${EXT_CACHE_DIR}/${ext}.duckdb_extension"
    if [ ! -f "$EXT_FILE" ]; then
        echo "Pre-downloading ${ext} extension for DuckDB ${DUCKDB_VERSION} (${PLATFORM})..."
        mkdir -p "$EXT_CACHE_DIR"
        curl -fsSL "http://extensions.duckdb.org/${DUCKDB_VERSION}/${PLATFORM}/${ext}.duckdb_extension.gz" \
            | gunzip > "$EXT_FILE"
        echo "Extension cached at ${EXT_FILE}"
    fi
done

docker run \
    --init \
    --rm \
    --publish $GIZMO_SERVER_PORT:$GIZMO_SERVER_PORT \
    ${SL_DATA_PATH:+--volume "$SL_DATA_PATH:$SL_DATA_PATH:ro"} \
    --volume "${EXT_CACHE_DIR}:/home/app_user/.duckdb/extensions/${DUCKDB_VERSION}/${PLATFORM}:ro" \
    --env PORT="$GIZMO_SERVER_PORT" \
    --env TLS_ENABLED="0" \
    --env GIZMOSQL_USERNAME="$GIZMOSQL_USERNAME" \
    --env GIZMOSQL_PASSWORD="$GIZMOSQL_PASSWORD" \
    --env PRINT_QUERIES="1" \
    --env DATABASE_FILENAME=":memory:" \
    --env GIZMOSQL_LOG_LEVEL="debug" \
    --env GIZMOSQL_ACCESS_LOG="debug" \
    --env GIZMOSQL_QUERY_LOG_LEVEL="debug" \
    --env GIZMOSQL_AUTH_LOG_LEVEL="debug" \
    --env SECRET_KEY="$JWT_SECRET_KEY" \
    --env INIT_SQL_COMMANDS="$INIT_SQL_COMMANDS" \
    --entrypoint /opt/gizmosql/scripts/docker-start-sl-gizmosql.sh \
    starlakeai/gizmo-on-demand:latest
