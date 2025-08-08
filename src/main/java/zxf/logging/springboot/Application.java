package zxf.logging.springboot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.logging.Logger;

@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        System.setProperty("oracle.ucp.timersAffectAllConnections","true");
        SpringApplication.run(Application.class, args);
    }
}