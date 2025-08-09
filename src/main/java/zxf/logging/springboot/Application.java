package zxf.logging.springboot;

import lombok.extern.slf4j.Slf4j;
import oracle.jdbc.diagnostics.CommonDiagnosable;
import oracle.ucp.diagnostics.DiagnosticsCollectorImpl;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import zxf.logging.springboot.config.SystemPropertiesInjector;

import javax.annotation.PostConstruct;
import java.util.Properties;
import java.util.logging.Logger;

@Slf4j
@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SystemPropertiesInjector.inject();
        SpringApplication.run(Application.class, args);
    }


}