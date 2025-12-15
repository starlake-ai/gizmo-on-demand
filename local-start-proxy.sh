#!/bin/bash

# GizmoSQL Proxy Server Startup and Management Script
set -e

CERT_DIR="certs"
DAYS_VALID=365

show_usage() {
    echo "Usage: $0 [OPTIONS]"
    echo ""
    echo "Options:"
    echo "  --with-no-tls             Start the proxy with TLS disabled (default: enabled)"
    echo "  --help                    Show this help message"
    echo ""
}

generate_certs() {
    echo "========================================="
    echo "  Generating Self-Signed TLS Certificates"
    echo "========================================="
    echo ""

    mkdir -p "$CERT_DIR"
    echo "Generating private key..."
    openssl genrsa -out "$CERT_DIR/server-key.pem" 2048

    echo "Generating certificate signing request..."
    openssl req -new -key "$CERT_DIR/server-key.pem" -out "$CERT_DIR/server.csr" \
      -subj "/C=US/ST=State/L=City/O=Organization/CN=localhost"

    echo "Generating self-signed certificate..."
    openssl x509 -req -days $DAYS_VALID -in "$CERT_DIR/server.csr" \
      -signkey "$CERT_DIR/server-key.pem" -out "$CERT_DIR/server-cert.pem" \
      -extfile <(printf "subjectAltName=DNS:localhost,IP:127.0.0.1")

    rm "$CERT_DIR/server.csr"

    echo ""
    echo "✓ Certificates generated successfully!"
    echo "  - $CERT_DIR/server-cert.pem"
    echo "  - $CERT_DIR/server-key.pem"
    echo ""
}

# Parse command-line arguments
WITH_TLS=true

while [[ $# -gt 0 ]]; do
  case $1 in
    --with-no-tls)
      WITH_TLS=false
      shift
      ;;
    --help)
      show_usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1"
      show_usage
      exit 1
      ;;
  esac
done

# Configuration for TLS
if [ "$WITH_TLS" = true ]; then
    if [ ! -f "$CERT_DIR/server-cert.pem" ] || [ ! -f "$CERT_DIR/server-key.pem" ]; then
        echo "TLS certificates not found. Generating them now..."
        generate_certs
    fi
    export PROXY_TLS_ENABLED=true
    export PROXY_TLS_CERT_CHAIN="$(pwd)/$CERT_DIR/server-cert.pem"
    export PROXY_TLS_PRIVATE_KEY="$(pwd)/$CERT_DIR/server-key.pem"
fi

# Validations
export PROXY_HOST="${PROXY_HOST:-0.0.0.0}"
export PROXY_PORT="${PROXY_PORT:-31338}"
export PROXY_TLS_ENABLED="${PROXY_TLS_ENABLED:-false}"
export PROXY_TLS_CERT_CHAIN="${PROXY_TLS_CERT_CHAIN:-}"
export PROXY_TLS_PRIVATE_KEY="${PROXY_TLS_PRIVATE_KEY:-}"
export GIZMO_SERVER_HOST="${GIZMO_SERVER_HOST:-localhost}"
export GIZMO_SERVER_PORT="${GIZMO_SERVER_PORT:-31337}"
export GIZMOSQL_TLS_ENABLED="${GIZMOSQL_TLS_ENABLED:-false}"
export GIZMOSQL_DEFAULT_USERNAME="${GIZMOSQL_DEFAULT_USERNAME:-}"
export GIZMOSQL_DEFAULT_PASSWORD="${GIZMOSQL_DEFAULT_PASSWORD:-}"
export VALIDATION_ENABLED="${VALIDATION_ENABLED:-true}"
export VALIDATION_ALLOW_BY_DEFAULT="${VALIDATION_ALLOW_BY_DEFAULT:-false}"
export LOG_LEVEL="${LOG_LEVEL:-DEBUG}"
export LOG_STATEMENTS="${LOG_STATEMENTS:-true}"
export LOG_VALIDATION="${LOG_VALIDATION:-true}"

export SL_PROJECT_ID="${SL_PROJECT_ID:-tpch2}"
export PG_HOST="${PG_HOST:-host.docker.internal}"
export PG_PORT=${PG_PORT:-5432}
export PG_USERNAME="${PG_USERNAME:-postgres}"
export PG_PASSWORD="${PG_PASSWORD:-azizam}"
export SL_DATA_PATH="${SL_DATA_PATH:-/Users/hayssams/git/starlake-api/starlake-api-samples/100/177/ducklake_files/tpch2}"

export GIZMOSQL_USERNAME="${GIZMOSQL_USERNAME:-gizmosql_username}"
export GIZMOSQL_PASSWORD="${GIZMOSQL_PASSWORD:-gizmosql_password}"
export JWT_SECRET_KEY="${JWT_SECRET_KEY:-a_very_secret_key}"

export SL_GIZMO_DEFAULT_SCRIPT="${SL_GIZMO_DEFAULT_SCRIPT:-/Users/hayssams/git/public/gizmo-on-demand/local-start-gizmo.sh}"

echo "========================================="
echo "  GizmoSQL Proxy Server"
if [ "$WITH_TLS" = true ]; then echo "  (TLS Enabled)"; fi
echo "========================================="
echo ""
echo "Configuration:"
echo "  Proxy:   ${PROXY_HOST}:${PROXY_PORT} (TLS: ${PROXY_TLS_ENABLED})"
echo "  Backend: ${GIZMO_SERVER_HOST}:${GIZMO_SERVER_PORT} (TLS: ${GIZMOSQL_TLS_ENABLED})"
echo "  Validation: ${VALIDATION_ENABLED}"
echo "  Log Level: ${LOG_LEVEL}"

if [ -n "$GIZMO_SERVER_PORT" ]; then
  echo "  Gizmo Server Port: ${GIZMO_SERVER_PORT}"
else
  echo "  Gizmo Server: Disabled"
fi
echo ""
echo "========================================="
echo ""

# JVM options required for Apache Arrow
JAVA_OPTS="--add-opens=java.base/java.nio=ALL-UNNAMED"

# Check if assembly JAR exists
JAR_PATH="distrib/gizmo-on-demand-assembly-0.1.0-SNAPSHOT.jar"
if [ -f "/opt/gizmosql/manager/gizmo-on-demand.jar" ]; then
     # Docker path
     JAR_PATH="/opt/gizmosql/manager/gizmo-on-demand.jar"
elif [ -f "target/scala-3.7.4/gizmo-on-demand-assembly-0.1.0-SNAPSHOT.jar" ]; then
     # Fallback if distrib not found but target is
     JAR_PATH="target/scala-3.7.4/gizmo-on-demand-assembly-0.1.0-SNAPSHOT.jar"
fi

#AGENT_LIB="-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005"
if [ -f "$JAR_PATH" ]; then
    java $JAVA_OPTS $AGENT_LIB -cp "$JAR_PATH" ai.starlake.gizmo.proxy.ProxyServer
else
    echo "Assembly JAR not found at $JAR_PATH. Running with sbt..."
    sbt -J-Xmx2G -J--add-opens=java.base/java.nio=ALL-UNNAMED "runMain ai.starlake.gizmo.proxy.ProxyServer"
fi
