# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a Spring Boot 4.1.0 application demonstrating Oracle JDBC and UCP (Universal Connection Pool) diagnostic logging integration. It uses Java 21 and Oracle JDBC 23.x.

## Build and Run

```bash
# Build the project
mvn clean package

# Run the application (the active system-properties branch activates no Spring profile;
# diagnostics come from system properties)
mvn spring-boot:run

# Also apply application-dev.yml (trace-level oracle.jdbc / oracle.ucp logging)
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# Start Oracle database (required)
docker-compose up -d
```

## Architecture

### Entry Point

`OracleApplication` is the single entry point. Its `main` method keeps a manual `if/else` toggle (flip the literal to switch) between two ways of configuring Oracle diagnostics:

- **System-properties branch** (`if (true)`, active) — calls `OracleDiagnostic.setupSystemProperties()` to set Oracle JDBC/UCP diagnostic system properties before Spring Boot starts. Use this for full diagnostic control. It is self-contained and does **not** activate the `dev` profile.
- **Plain branch** (`else`) — relies on Spring Boot configuration only and activates the `dev` profile. Use this for the standard Spring configuration approach.

(The former `ApplicationWithSystemProperties` / `ApplicationWithoutSystemProperties` pair was merged into this single class.)

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

## Credential Hot-Reload (Vault Static Role)

The `zxf.logging.springboot.cred` package implements hot-reload of Vault Static Role DB credentials (full design: `docs/Vault Static Role 凭据热更新方案(Spring Boot 4.1 + JDK 21).md`).

- `CredentialContextInitializer` (registered in `META-INF/spring.factories`) injects `spring.datasource.username/password` from mounted files before context refresh, so the pool starts with real credentials.
- Credential files are read from `DB_CRED_DIR` (default `~/secrets/db`). When the directory is absent (local dev), hot-reload is skipped and the configured credentials are used.
- Runtime flow: `SecretDirectoryWatcher` (WatchService) → `CredentialChangeNotifier` (debounce) → `CredentialsChangedEvent` → `CredentialsChangedListener` → `UcpCredentialApplier` (`PoolDataSource.reconfigureDataSource`).
- `DatabaseHealthIndicator` (`dynamicDbHealth`) replaces the default db health check (`management.health.db.enabled=false`) to avoid borrowing connections during a credential refresh.

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
- Java 21 (full JDK, required for build): `/home/davis/.jdks/ms-21.0.10` — note that `/usr/lib/jvm/java-*-openjdk-amd64` are JRE-only (no `javac`) and cannot compile. Build with `JAVA_HOME=/home/davis/.jdks/ms-21.0.10 mvn clean package`.