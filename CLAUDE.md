# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Gizmo On-Demand is a multi-tenant platform for provisioning on-demand GizmoSQL database instances exposed through Apache Arrow Flight SQL. It provides isolated proxy+backend stacks per user/workload with authentication, SQL validation, and automatic lifecycle management.

## Build & Development Commands

```bash
# Build (produces uber-jar in distrib/)
make build          # or: sbt assembly

# Run locally
make run            # or: sbt run

# Clean
make clean          # sbt clean + removes target dirs

# Format code
sbt scalafmt        # Uses Scalafmt 3.10.0, Scala 3 dialect

# Run API integration tests (curl-based, requires running instance)
make test           # or: ./test-api.sh

# Docker
make docker-build   # Build local image
make docker-run     # Run container (needs SL_GIZMO_API_KEY env var)
make docker-stop    # Stop and remove container
```

## Tech Stack

- **Language**: Scala 3.7.4, SBT 1.11.7
- **HTTP/REST**: Tapir (typed endpoints) + HTTP4s Ember server
- **Serialization**: Circe (JSON)
- **gRPC/Flight**: Apache Arrow Flight SQL 14.0.1, gRPC-Java 1.59.0
- **Kubernetes**: Fabric8 client 7.1.0
- **Config**: PureConfig with `${?ENV_VAR}` overrides in `application.conf`
- **Auth**: java-jwt (HMAC-256 JWT)
- **SQL**: JSQLParser + JSQLTranspiler for validation/transpilation

## Architecture

Two-tier system with a Strategy pattern for runtime backends:

### Tier 1: Process Manager (`ai.starlake.gizmo.ondemand`)
REST API (default port 10900) that manages proxy instance lifecycles.

- **Main.scala** — Entry point: loads config, selects backend, starts HTTP server
- **ProcessManager.scala** — Central orchestrator, maintains `TrieMap[String, ManagedProcess]`
- **ProcessEndpoints.scala** — Tapir REST endpoint definitions (start/stop/restart/list/stopAll + health)
- **Models.scala** — Request/Response DTOs
- **EnvVars.scala** — Environment variable configuration facade
- **ProcessBackend trait** — Strategy interface with `start()`, `stop()`, `isAlive()`, `discoverExisting()`, `cleanup()`
  - **LocalProcessBackend** — Spawns OS processes, allocates ports from configurable range (default 11900-12000)
  - **KubernetesProcessBackend** — Creates Pod+Service per instance via Fabric8, uses DNS discovery

### Tier 2: FlightSQL Proxy (`ai.starlake.gizmo.proxy`)
gRPC proxy that authenticates users, validates SQL, and forwards queries to GizmoSQL backends.

- **ProxyServer.scala** — Bootstrap, builds init SQL for backend
- **FlightSqlProxy.scala** — Flight SQL producer, multi-auth (Basic/Bearer JWT/ODBC), per-user `FlightClient` caching in `ConcurrentHashMap`
- **GizmoServerManager.scala** — Backend subprocess lifecycle with idle timeout, uses `synchronized` for start/stop
- **StatementValidator.scala** — SQL statement security validation (allowlist-based)

### Runtime Modes
- **Local** (`SL_GIZMO_RUNTIME_TYPE=local`): OS processes, dynamic port allocation, backend port = proxy port + 1000
- **Kubernetes** (`SL_GIZMO_RUNTIME_TYPE=kubernetes`): Pod+Service per instance, fixed ports (31337/31338), DNS-based discovery

### Authentication
- Process Manager API: `X-API-Key` header (optional, set via `SL_GIZMO_API_KEY`)
- FlightSQL Proxy: Basic auth, Bearer/JWT, ODBC format; JWT uses HMAC-256 with 1-hour expiry

## Configuration

All config lives in `src/main/resources/application.conf` with two top-level sections:
- `gizmosql-proxy` — proxy settings (host, port, TLS, validation, session)
- `gizmo-on-demand` — process manager settings (host, port, port range, runtime type, K8s config)

Environment variables override config values (prefix `SL_GIZMO_*`).

## Concurrency Model

Lock-free `TrieMap` for process registry and port allocation. `synchronized` blocks in `GizmoServerManager` for backend lifecycle. Async exit monitoring via `process.onExit().thenAccept`.

## Local Development

```bash
# Start process manager
./local-start-process-manager.sh

# Create/list/stop processes
./create-process.sh <name>
./list-processes.sh
./stop-process.sh <name>
```