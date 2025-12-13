# Gizmo On-Demand Process Manager

A process manager service built with Scala 3 and Tapir that allows you to start, stop, restart, and list processes via a REST API.

## Features

- **Start Process**: Spawn a new process and get an assigned port number
  - Executes a configurable script (default: `/opt/gizmo/scripts/start_gizmosql.sh`)
  - Passes arguments as environment variables to the script
- **Stop Process**: Stop a running process
- **Stop All Processes**: Stop all running processes at once (also scans and kills orphaned processes on managed ports)
- **Restart Process**: Restart an existing process with its original arguments
- **List Processes**: View all running processes with their details

The server automatically stops all processes on startup to ensure a clean state.

## Technology Stack

- **Scala 3.7.4**
- **Tapir 1.11.10** - Type-safe API endpoints with OpenAPI documentation
- **JDK HTTP Server** - Built-in Java HTTP server (lightweight, no heavy frameworks)
- **Circe 0.14.10** - JSON encoding/decoding

## Getting Started

### Prerequisites

- JDK 11 or higher
- SBT 1.x

### Running the Server

```bash
sbt run
```

The server will start on `http://localhost:8080` by default.

### Configuration

The server can be configured using environment variables:

- **SL_GIZMO_HOST**: The host address to bind to (default: `0.0.0.0`)
- **SL_GIZMO_PORT**: The port to listen on (default: `10900`)
- **SL_GIZMO_MIN_PORT**: Minimum port number for managed processes (default: `8000`)
- **SL_GIZMO_MAX_PORT**: Maximum port number for managed processes (default: `9000`)
- **SL_GIZMO_MAX_PROCESSES**: Maximum number of processes that can be spawned (default: `10`)
- **SL_GIZMO_DEFAULT_SCRIPT**: Path to the script to execute when starting processes (default: `/opt/gizmo/scripts/start_gizmosql.sh`)
- **SL_GIZMO_API_KEY**: API key required for authentication (no default - if not set, authentication is disabled)

Example:
```bash
# Run on a different port
SL_GIZMO_PORT=9090 sbt run

# Run on a specific host and port
SL_GIZMO_HOST=127.0.0.1 SL_GIZMO_PORT=9090 sbt run

# Configure port range for managed processes
SL_GIZMO_MIN_PORT=10000 SL_GIZMO_MAX_PORT=11000 sbt run

# Limit the number of processes
SL_GIZMO_MAX_PROCESSES=5 sbt run
```

### Authentication

The API supports optional API key authentication via the `X-API-Key` header:

- **Enabled**: Set the `SL_GIZMO_API_KEY` environment variable to your desired API key
- **Disabled**: Leave `SL_GIZMO_API_KEY` unset (authentication will be bypassed)

When authentication is enabled, all API requests must include the `X-API-Key` header with the correct API key. Requests without a valid API key will receive a `401 Unauthorized` response.

Example with authentication:
```bash
# Set the API key when starting the server
SL_GIZMO_API_KEY=my-secret-key sbt run

# Include the API key in requests
curl http://localhost:10900/api/process/list \
  -H "X-API-Key: my-secret-key"
```

### API Documentation

Once the server is running, you can access the interactive Swagger UI at:

```
http://localhost:8080/docs
```

## API Endpoints

### Start a Process

**POST** `/api/process/start`

Request body:
```json
{
  "processName": "my-service",
  "arguments": {
    "GIZMOSQL_USERNAME": "admin",
    "GIZMOSQL_PASSWORD": "secret123",
    "SL_PROJECT_ID": "project-001",
    "SL_DATA_PATH": "/data/starlake",
    "PG_USERNAME": "postgres",
    "PG_PASSWORD": "pgpass",
    "PG_PORT": "5432",
    "PG_HOST": "localhost",
    "LOG_LEVEL": "INFO"
  }
}
```

**Required Arguments:**
The following environment variables are **required** and must be included in the `arguments` map:
- `GIZMOSQL_USERNAME`: Username for GizmoSQL
- `GIZMOSQL_PASSWORD`: Password for GizmoSQL
- `SL_PROJECT_ID`: Starlake project identifier
- `SL_DATA_PATH`: Path to Starlake data directory
- `PG_USERNAME`: PostgreSQL username
- `PG_PASSWORD`: PostgreSQL password
- `PG_PORT`: PostgreSQL port number
- `PG_HOST`: PostgreSQL host address

**Note:**
- Arguments are passed as **environment variables** to the script
- The script executed is configurable via `SL_GIZMO_DEFAULT_SCRIPT` (default: `/opt/gizmo/scripts/start_gizmosql.sh`)
- Each key-value pair in the arguments map becomes an environment variable (e.g., `"DB_HOST": "localhost"` → `DB_HOST=localhost`)
- Additional optional environment variables can be included as needed
- The system automatically generates an `INIT_SQL_COMMANDS` environment variable from the required variables using a template that:
  - Creates a PostgreSQL persistent secret
  - Creates a DuckLake persistent secret
  - Attaches the DuckLake database
  - Sets the active database to the project ID

Response:
```json
{
  "processName": "my-service",
  "port": 8234,
  "message": "Process started successfully on port 8234"
}
```

### Stop a Process

**POST** `/api/process/stop`

Request body:
```json
{
  "processName": "my-service"
}
```

Response:
```json
{
  "processName": "my-service",
  "message": "Process stopped successfully"
}
```

### Restart a Process

**POST** `/api/process/restart`

Request body:
```json
{
  "processName": "my-service"
}
```

Response:
```json
{
  "processName": "my-service",
  "port": 8456,
  "message": "Process restarted successfully on port 8456"
}
```

### List All Processes

**GET** `/api/process/list`

Response:
```json
{
  "processes": [
    {
      "processName": "my-service",
      "port": 8234,
      "pid": 12345,
      "status": "running"
    }
  ]
}
```

### Stop All Processes

**POST** `/api/process/stopAll`

Response:
```json
{
  "processName": "all",
  "message": "Stopped 3 process(es)"
}
```

## Example Usage with curl

```bash
# Start a process with required environment variables (with API key)
curl -X POST http://localhost:10900/api/process/start \
  -H "Content-Type: application/json" \
  -H "X-API-Key: your-secret-api-key" \
  -d '{"processName": "my-app", "arguments": {"GIZMOSQL_USERNAME": "admin", "GIZMOSQL_PASSWORD": "secret123", "SL_PROJECT_ID": "project-001", "SL_DATA_PATH": "/data/starlake", "PG_USERNAME": "postgres", "PG_PASSWORD": "pgpass", "PG_PORT": "5432", "PG_HOST": "localhost"}}'

# List all processes
curl http://localhost:10900/api/process/list \
  -H "X-API-Key: your-secret-api-key"

# Stop a process
curl -X POST http://localhost:10900/api/process/stop \
  -H "Content-Type: application/json" \
  -H "X-API-Key: your-secret-api-key" \
  -d '{"processName": "my-app"}'

# Restart a process (preserves original arguments)
curl -X POST http://localhost:8080/api/process/restart \
  -H "Content-Type: application/json" \
  -d '{"processName": "my-app"}'

# Stop all processes (also scans and kills any process on ports 8000-9000)
curl -X POST http://localhost:8080/api/process/stopAll
```

## Architecture

- **Models.scala**: Domain models and JSON codecs
- **ProcessManager.scala**: Core process management logic with thread-safe state management (using TrieMap)
- **ProcessEndpoints.scala**: Tapir endpoint definitions with type-safe API contracts
- **Main.scala**: JDK HTTP server setup with Tapir integration and Swagger UI

## Customization

The current implementation uses a simple `sleep infinity` command for demonstration. To manage actual applications:

1. Modify the `startProcess` method in `ProcessManager.scala`
2. Replace the `SysProcess(Seq("sleep", "infinity"))` with your actual process command
3. Customize port allocation strategy if needed (currently random between 8000-9000)

## License

MIT

