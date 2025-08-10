package zxf.logging.springboot.service;


import lombok.extern.java.Log;
import oracle.jdbc.diagnostics.CommonDiagnosable;
import oracle.ucp.diagnostics.DiagnosticsCollectorImpl;
import org.springframework.stereotype.Service;

import java.nio.file.Paths;
import java.util.logging.Level;
import java.util.logging.Logger;

@Log
@Service
public class OracleDiagnostic {

    public static void julConfig() {
        System.out.println("Checking JUL Configuration.............");
        System.out.println("1.0 Check config setting.............");
        String configClassFromSysProp = System.getProperty("java.util.logging.config.class");
        System.out.println("1.1 Check config class setting from system property java.util.logging.config.class, " + configClassFromSysProp);
        String configFileFromSysProp = System.getProperty("java.util.logging.config.file");
        System.out.println("1.2 Check config file setting from system property java.util.logging.config.file, as local path, " + configFileFromSysProp);
        String configFileDefault = Paths.get(System.getProperty("java.home"), "conf", "logging.properties").toString();
        System.out.println("1.3 Check default config file {java.home}/conf/logging.properties, as local path, " + configFileDefault);
    }

    public static void setupSystemProperties() {
        System.setProperty("oracle.jdbc.diagnostic.enableLogging", "true");
        System.setProperty("oracle.ucp.timersAffectAllConnections", "true");
        System.setProperty("oracle.ucp.diagnostic.enableLogging", "true");
        //Setting by this property will override the settings from springboot logging setttings
        System.setProperty("oracle.ucp.diagnostic.loggingLevel", "FINEST");
        //Setting by log file will be overridded by springboot logging settings(Default: INFO for root logger) or oracle.ucp.diagnostic.loggingLevel
        System.setProperty("java.util.logging.config.file", "./src/main/resources/my-jul.properties");
    }

    public void oracleJdbc(Boolean setting) {
        String logName = ((CommonDiagnosable) CommonDiagnosable.getInstance()).getDiagnosticLoggerName();
        log.info(String.format("Oracle JDBC Diagnosable, name=%s, debug=%s", logName, CommonDiagnosable.getInstance().isDebugEnabled()));
        Logger jdbcLogger = Logger.getLogger(logName);
        log.info(String.format("Oracle JDBC Logging - %s, handlers=%s, level=%s", logName, jdbcLogger.getHandlers().toString(), jdbcLogger.getLevel()));
        if (setting != null && setting) {
            //Set by Logger
            jdbcLogger.setLevel(Level.FINEST);
            //Set by Diagnosable
            ((CommonDiagnosable) CommonDiagnosable.getInstance()).setDebugEnabled(true);
        }
    }

    public void oracleUcp(Boolean setting) {
        String logName = DiagnosticsCollectorImpl.getCommon().getLoggerName();
        log.info(String.format("Oracle UCP Diagnosable, name=%s, logging=%s, level=%s", logName, DiagnosticsCollectorImpl.getCommon().getLoggingEnabled(),
                DiagnosticsCollectorImpl.getCommon().getLogLevel()));
        Logger ucpLogger = Logger.getLogger(logName);
        log.info(String.format("Oracle JDBC Logging - %s, handlers=%s, level=%s", logName, ucpLogger.getHandlers().toString(), ucpLogger.getLevel()));
        String poolName = "pool-test";
        Logger poolLogger = Logger.getLogger(poolName);
        log.info(String.format("Oracle JDBC Logging - %s, handlers=%s, level=%s", poolName, poolLogger.getHandlers().toString(), poolLogger.getLevel()));
        if (setting != null && setting) {
            //Set by Logger
            ucpLogger.setLevel(Level.FINEST);
            poolLogger.setLevel(Level.FINEST);
            //Set by Diagnosable
            DiagnosticsCollectorImpl.getCommon().setLoggingEnabled(true);
            DiagnosticsCollectorImpl.getCommon().setLogLevel(Level.FINEST);
        }
    }
}
