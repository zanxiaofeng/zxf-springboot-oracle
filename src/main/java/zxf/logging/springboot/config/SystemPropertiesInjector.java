package zxf.logging.springboot.config;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SystemPropertiesInjector {
    public static void inject() {
        System.setProperty("oracle.jdbc.diagnostic.enableLogging", "true");
        System.setProperty("oracle.ucp.timersAffectAllConnections", "true");
        System.setProperty("oracle.ucp.diagnostic.enableLogging", "true");
        System.setProperty("oracle.ucp.diagnostic.loggingLevel", "FINEST");
    }
}