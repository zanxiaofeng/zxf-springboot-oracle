# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a Spring Boot 2.7.18 application demonstrating Oracle JDBC and UCP (Universal Connection Pool) diagnostic logging integration. It uses Java 17 and Oracle JDBC 23.4.0.24.05.

## Build and Run

```bash
# Build the project
mvn clean package

# Run the application (default profile: dev)
mvn spring-boot:run

# Run with specific profile
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# Start Oracle database (required)
docker-compose up -d
```

## Architecture

### Entry Points

Two main application classes demonstrate different approaches to configuring Oracle diagnostics:

- **`ApplicationWithSystemProperties`** - Sets up Oracle diagnostic system properties programmatically before Spring Boot starts. Use this for full diagnostic control.

- **`ApplicationWithoutSystemProperties`** - Relies on Spring Boot configuration properties only. Use this for standard Spring configuration approach.

Both classes activate the `dev` profile by default.

### Oracle Diagnostic Configuration

The `OracleDiagnostic` service provides two key diagnostic areas:

1. **Oracle JDBC Diagnostics** - Uses `CommonDiagnosable` from `ojdbc8`
2. **Oracle UCP Diagnostics** - Uses `DiagnosticsCollectorImpl` from `ucp`

Diagnostic logging is controlled via:
- System properties (set before Spring context loads)
- JUL configuration file (`src/main/resources/my-jul.properties`)
- Spring Boot logging configuration (`application-dev.yml`)

### Configuration Layers (Priority Order)

1. System properties (highest priority)
2. `oracle.ucp.diagnostic.loggingLevel` system property
3. Spring Boot logging levels
4. JUL configuration file
5. Oracle defaults (lowest priority)

## Connection Validation Modes

Oracle supports multiple connection validation strategies (configured via `oracle.jdbc.defaultConnectionValidation`):

| Mode | Description |
|------|-------------|
| `SERVER` or `COMPLETE` | Executes `SELECT 'x' FROM DUAL` |
| `NETWORK` | Issues OPING TTC function (default) |
| `SOCKET` | Writes zero-length NS data packet |
| `INBAND_DOWN` | Non-blocking socket read (23ai+) |
| `LOCAL` | Same as INBAND_DOWN |
| `NONE` | Lifecycle variable check only |

## Database Setup

The project includes `docker-compose.yaml` for running Oracle Free 23c locally:

- Default port: `1521`
- System password: `123456`
- Additional app user: `appuser/123456`

## Key Properties

Pool configuration in `application.yml`:
- Connection pool type: `oracle.ucp.jdbc.PoolDataSource`
- Pool name: `pool-test`
- Initial/min pool size: `0`
- Max pool size: `3`
- Fast Connection Failover: enabled

## JDK Location

- Java 17: `/usr/lib/jvm/java-1.17.0-openjdk-amd64`