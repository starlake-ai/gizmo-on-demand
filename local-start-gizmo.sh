docker run \
    --init \
    --rm \
    --volume /Users/hayssams/git/starlake-api/starlake-api-samples:/Users/hayssams/git/starlake-api/starlake-api-samples \
    --publish $GIZMO_SERVER_PORT:$GIZMO_SERVER_PORT \
    --env PORT="$GIZMO_SERVER_PORT" \
    --env TLS_ENABLED="0" \
    --env GIZMOSQL_USERNAME="$GIZMOSQL_USERNAME" \
    --env GIZMOSQL_PASSWORD="$GIZMOSQL_PASSWORD" \
    --env PRINT_QUERIES="1" \
    --env DATABASE_FILENAME=":memory:" \
    --env GIZMOSQL_LOG_LEVEL="debug" \
    --env SECRET_KEY="$JWT_SECRET_KEY" \
    --env INIT_SQL_COMMANDS="$INIT_SQL_COMMANDS" \
    --entrypoint /opt/gizmosql/scripts/docker-start-sl-gizmosql.sh \
    starlakeai/gizmo-on-demand:latest

