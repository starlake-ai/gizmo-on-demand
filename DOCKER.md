# Docker Deployment Guide

This guide explains how to build and run the Gizmo Process Manager using Docker.

## Architecture

The deployment consists of two Docker images:

1. **gizmo:latest** - Base image built from `gizmo.Dockerfile` containing GizmoSQL
2. **gizmo-manager** - Process manager built from `Dockerfile` that extends the base image

## Prerequisites

- Docker 20.10 or later
- At least 4GB of available RAM
- At least 10GB of available disk space

## Building the Images

### Step 1: Build the Base Gizmo Image

First, build the base GizmoSQL image from `gizmo.Dockerfile`:

```bash
docker build -f gizmo.Dockerfile -t gizmo:latest .
```

This will:
- Install Python, build tools, and cloud CLIs (AWS, Azure)
- Build GizmoSQL from source
- Create sample databases (TPC-H)
- Set up the GizmoSQL environment

**Note:** This build can take 15-30 minutes depending on your system.

### Step 2: Build the Process Manager Image

Once the base image is built, build the process manager:

```bash
docker build -t gizmo-manager:latest .
```

This will:
- Build the Scala application using sbt
- Create a fat JAR with all dependencies
- Extend the gizmo:latest base image
- Install Java Runtime Environment
- Configure the process manager


## Run Gizmo Process Manager

```bash
docker run -d \
  --name gizmo-manager \
  -p 10900:10900 \
  -p 8000-8010:8000-8010 \
  -e SL_GIZMO_HOST=0.0.0.0 \
  -e SL_GIZMO_PORT=10900 \
  -e SL_GIZMO_MIN_PORT=8000 \
  -e SL_GIZMO_MAX_PORT=8010 \
  -e SL_GIZMO_MAX_PROCESSES=10 \
  gizmo-manager:latest
```

## Environment Variables

Configure the process manager using these environment variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `SL_GIZMO_HOST` | `0.0.0.0` | Host address to bind the server |
| `SL_GIZMO_PORT` | `8080` | Port for the API server |
| `SL_GIZMO_MIN_PORT` | `8000` | Minimum port for managed processes |
| `SL_GIZMO_MAX_PORT` | `9000` | Maximum port for managed processes |
| `SL_GIZMO_MAX_PROCESSES` | `10` | Maximum number of concurrent processes |
| `SL_GIZMO_DEFAULT_SCRIPT` | `/opt/gizmo/scripts/start_gizmosql.sh` | Script to execute for processes |

## Testing the Deployment

Once running, test the API:

```bash
# Check health
curl http://localhost:8080/api/process/list

# Start a process
curl -X POST http://localhost:8080/api/process/start \
  -H "Content-Type: application/json" \
  -d '{
    "processName": "test-app",
    "arguments": {
      "GIZMOSQL_USERNAME": "admin",
      "GIZMOSQL_PASSWORD": "secret",
      "SL_PROJECT_ID": "test-project",
      "SL_DATA_PATH": "/data/starlake",
      "PG_USERNAME": "postgres",
      "PG_PASSWORD": "postgres",
      "PG_PORT": "5432",
      "PG_HOST": "postgres"
    }
  }'
```

## Accessing Swagger UI

The API documentation is available at:

```
http://localhost:8080/docs
```

## Troubleshooting

### View logs

```bash
# Docker Compose
docker-compose logs -f gizmo-manager

# Docker
docker logs -f gizmo-manager
```

### Access container shell

```bash
# Docker Compose
docker-compose exec gizmo-manager bash

# Docker
docker exec -it gizmo-manager bash
```

### Rebuild from scratch

```bash
# Remove all containers and volumes
docker-compose down -v

# Rebuild images
docker build -f gizmo.Dockerfile -t gizmo:latest .
docker build -t gizmo-manager:latest .

# Start again
docker-compose up -d
```

## Production Considerations

1. **Resource Limits**: Set appropriate CPU and memory limits in docker-compose.yml
2. **Persistent Storage**: Use named volumes for data persistence
3. **Secrets Management**: Use Docker secrets or environment files for sensitive data
4. **Networking**: Use custom networks to isolate services
5. **Monitoring**: Add health checks and monitoring tools
6. **Logging**: Configure log rotation and centralized logging

