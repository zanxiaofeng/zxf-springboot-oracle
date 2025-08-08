# Configuration Schema
- spring-boot-autoconfigure-2.7.18.jar!/META-INF/spring-configuration-metadata.json
- ucp-21.5.0.0.jar!/oracle/ucp/xml/configuration.xsd

# Core classes
- oracle.jdbc.OracleConnection.CONNECTION_PROPERTY_NET_KEEPALIVE
- oracle.jdbc.OracleConnection.CONNECTION_PROPERTY_NET_KEEPALIVE_DEFAULT
- oracle.jdbc.OracleConnection.CONNECTION_PROPERTY_TCP_KEEPIDLE
- oracle.jdbc.OracleConnection.CONNECTION_PROPERTY_TCP_KEEPIDLE_DEFAULT
- oracle.jdbc.OracleConnection.CONNECTION_PROPERTY_TCP_KEEPINTERVAL
- oracle.jdbc.OracleConnection.CONNECTION_PROPERTY_TCP_KEEPINTERVAL_DEFAULT
- ojdbc8-23.4.0.24.05.jar!/oracle/jdbc/diagnostics/CommonDiagnosable.class
- ucp-23.4.0.24.05.jar!/oracle/ucp/diagnostics/DiagnosticsCollectorImpl.class

# oracle.jdbc.defaultConnectionValidation
- "SERVER" (or "COMPLETE") makes the driver execute a basic SQL query "SELECT 'x' FROM DUAL".
- "NETWORK" causes the driver to issue an OPING TTC function. It's the default.
- "SOCKET" causes the driver to write a zero-length NS data packet on the socket, which the server ignores.
- New in 23ai: “INBAND_DOWN” makes the do a non-blocking socket read call on the socket
- "LOCAL" same as above
- "NONE" checks the connection's lifecycle (variable check)

# UCP Best Practices for Oracle Database and Spring Boot
- https://blogs.oracle.com/developers/post/ucp-best-practices-for-oracle-database-19c-and-spring-boot