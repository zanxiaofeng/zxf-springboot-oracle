package zxf.logging.springboot.service;


import lombok.extern.java.Log;
import oracle.jdbc.diagnostics.CommonDiagnosable;
import oracle.ucp.diagnostics.DiagnosticsCollectorImpl;
import org.springframework.stereotype.Service;

import java.util.logging.Level;
import java.util.logging.Logger;

@Log
@Service
public class OracleDiagnostic {

    public static void init() {
        System.setProperty("oracle.jdbc.diagnostic.enableLogging", "true");
        System.setProperty("oracle.ucp.timersAffectAllConnections", "true");
        System.setProperty("oracle.ucp.diagnostic.enableLogging", "true");
        System.setProperty("oracle.ucp.diagnostic.loggingLevel", "FINEST");
        System.setProperty("java.util.logging.config.file", "classpath:my-jul.properties");
    }

    public void oracleJdbc(Boolean setting) {
        String logName = ((CommonDiagnosable) CommonDiagnosable.getInstance()).getDiagnosticLoggerName();
        Logger jdbcLogger = Logger.getLogger(logName);
        log.info(String.format("Oracle JDBC Logging, name=%s, level=%s, debug=%s", logName, jdbcLogger.getLevel(), CommonDiagnosable.getInstance().isDebugEnabled()));
        if (setting != null && setting) {
            jdbcLogger.setLevel(Level.FINEST);
        }
    }

    public void oracleUcp(Boolean setting) {
        String logName = DiagnosticsCollectorImpl.getCommon().getLoggerName();
        Logger ucpLogger = Logger.getLogger(logName);
        Logger poolLogger = Logger.getLogger("pool-test");
        log.info(String.format("Oracle  UCP Logging, name=%s, level=%s-%s, pool=%s", logName, ucpLogger.getLevel(), DiagnosticsCollectorImpl.getCommon().getLogLevel(), poolLogger.getLevel()));
        if (setting != null && setting) {
            ucpLogger.setLevel(Level.FINEST);
        }
    }
}
