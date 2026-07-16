package zxf.logging.springboot.cred;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * 在上下文 refresh 前（ApplicationContextInitializer）把 K8s 挂载的 Vault Static Role 凭据
 * 注入 Environment 最高优先级，覆盖 application.yml，确保 DataSource/Flyway/JPA 启动即用真实凭据。
 * dev（无挂载文件）时静默回退到 application.yml。
 *
 * <p>选型说明：Boot 4 已废弃 EnvironmentPostProcessor；BootstrapRegistryInitializer 在 4.1 已
 * 无 addApplicationListener（仅 addCloseListener），无法挂载环境事件监听。故改用 Spring Framework
 * 核心且稳定的 ApplicationContextInitializer，经 spring.factories 注册。</p>
 */
public class CredentialContextInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        ConfigurableEnvironment env = applicationContext.getEnvironment();
        // 上下文创建前无 Bean 可注入，直接 new 文件源（与运行期共享同一读取逻辑）
        CredentialFileSource source = new CredentialFileSource(
                env.getProperty("DB_CRED_DIR", "~/secrets/db"));
        if (!source.isAvailable()) {

            return; // dev/无挂载：回退到 application.yml
        }
        try {
            DbCredentials creds = source.read();
            if (!creds.isEmpty()) {
                env.getPropertySources().addFirst(new MapPropertySource(
                        "vaultStaticCreds",
                        Map.of(
                                "spring.datasource.username", creds.username(),
                                "spring.datasource.password", creds.password())));
            }
        } catch (Exception ignored) {
            // 读取失败不阻断启动：交由后续 DataSource 建连时报错暴露
        }
    }
}
