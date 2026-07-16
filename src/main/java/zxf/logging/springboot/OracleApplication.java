package zxf.logging.springboot;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import zxf.logging.springboot.service.OracleDiagnostic;

@Slf4j
@SpringBootApplication
public class OracleApplication {
    public static void main(String[] args) {
        if (true) {
            // With system properties
            OracleDiagnostic.setupSystemProperties();
            OracleDiagnostic.julConfig();
        } else {
            // Without system properties
            System.setProperty("spring.profiles.active", "dev");
            OracleDiagnostic.julConfig();
        }
        SpringApplication.run(OracleApplication.class, args);
    }
}