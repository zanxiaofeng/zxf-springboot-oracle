package zxf.logging.springboot.service;


import lombok.extern.java.Log;
import oracle.jdbc.diagnostics.CommonDiagnosable;
import oracle.ucp.diagnostics.DiagnosticsCollectorImpl;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.logging.Level;
import java.util.logging.Logger;

@Log
@Service
public class OracleDiagnostic {

    @PostConstruct
    public void init() {
        oracleJdbc(null);
        oracleUcp(null);
    }

    public void oracleJdbc(Boolean setting) {
        String logName = ((CommonDiagnosable) CommonDiagnosable.getInstance()).getDiagnosticLoggerName();
        Logger jdbcLogger = Logger.getLogger("oracle.jdbc");
        log.info(String.format("Oracle JDBC Logging, name=%s, level=%s, debug=%s", logName, jdbcLogger.getLevel(), CommonDiagnosable.getInstance().isDebugEnabled()));
        if (setting != null && setting) {
            jdbcLogger.setLevel(Level.FINEST);
        }
    }

    public void oracleUcp(Boolean setting) {
        String logName = DiagnosticsCollectorImpl.getCommon().getLoggerName();
        Logger ucpLogger = Logger.getLogger(logName);
        log.info(String.format("Oracle  UCP Logging, name=%s, level=%s-%s", logName, ucpLogger.getLevel(), DiagnosticsCollectorImpl.getCommon().getLogLevel()));
        if (setting != null && setting) {
            ucpLogger.setLevel(Level.FINEST);
        }
    }
}
