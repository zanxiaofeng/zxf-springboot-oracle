package zxf.logging.springboot;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import zxf.logging.springboot.service.OracleDiagnostic;

@Slf4j
@SpringBootApplication
public class ApplicationWithSystemProperties {
    public static void main(String[] args) {
        System.setProperty("spring.profiles.active", "dev");
        OracleDiagnostic.setupSystemProperties();
        OracleDiagnostic.julConfig();
        SpringApplication.run(ApplicationWithSystemProperties.class, args);
    }
}