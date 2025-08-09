package zxf.logging.springboot.app;

import lombok.extern.java.Log;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import zxf.logging.springboot.service.OracleDiagnostic;

import java.time.LocalDateTime;
import java.time.ZonedDateTime;

@Log
@RestController
public class HomeController {
    @Autowired
    private NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    @Autowired
    private OracleDiagnostic oracleDiagnostic;

    @GetMapping("/test")
    public String test() {
        SqlParameterSource sqlParameterSource = new MapSqlParameterSource()
                .addValue("localDateTime", LocalDateTime.now())
                .addValue("zonedDateTime", ZonedDateTime.now());
        return namedParameterJdbcTemplate.queryForObject("SELECT DBTIMEZONE, SESSIONTIMEZONE, CURRENT_DATE, CURRENT_TIMESTAMP, LOCALTIMESTAMP, SYSDATE, SYSTIMESTAMP, :localDateTime AS LOCAL_DATETIME, :zonedDateTime AS ZONED_DATETIME from dual"
                , sqlParameterSource, (rs, rowNum) -> {
                    return String.format("DBTIMEZONE=%s, SESSIONTIMEZONE=%s %s %s %s %s %s %s %s",
                            rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9));
                });
    }

    @GetMapping("/oracle/jdbc")
    public void oracleJdbc(@RequestParam(required = false) Boolean setting) {
        oracleDiagnostic.oracleJdbc(setting);
    }

    @GetMapping("/oracle/ucp")
    public void oracleUcp(@RequestParam(required = false) Boolean setting) {
        oracleDiagnostic.oracleUcp(setting);
    }
}
