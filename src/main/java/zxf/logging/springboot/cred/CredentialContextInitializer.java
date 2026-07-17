package zxf.logging.springboot.cred;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;

import java.nio.file.Path;
import java.util.Map;

/**
 * 在上下文 refresh 前（ApplicationContextInitializer）把 K8s 挂载的 Vault Static Role 凭据
 * 注入 Environment 最高优先级，覆盖 application.yml，确保 DataSource/Flyway/JPA 启动即用真实凭据。
 * dev（无挂载文件）时回退到 application.yml。
 *
 * <p>选型说明：Boot 4.x 中 EnvironmentPostProcessor 仍是有效扩展点（4.0 起接口迁至
 * {@code org.springframework.boot} 包，spring.factories 注册 key 同步调整）。本方案选用 Spring Framework
 * 核心的 ApplicationContextInitializer——时机同样早于 refresh（DataSource/Flyway/JPA 建连），API 面最小，
 * 不受 Boot 扩展点演进影响。</p>
 */
@Slf4j
public class CredentialContextInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        ConfigurableEnvironment environment = applicationContext.getEnvironment();
        // 上下文创建前无 Bean 可注入，直接 new 文件源（与运行期共享同一读取逻辑）
        String dirPath = environment.getProperty("DB_CRED_DIR", "~/secrets/db");
        CredentialFileSource fileSource = new CredentialFileSource(Path.of(dirPath));
        if (!fileSource.isAvailable()) {
            log.info("Credential dir not available, skipping hot-reload; using datasource credentials from config");
            return;
        }
        inject(environment, fileSource);
    }

    /** 读取挂载凭据并以最高优先级注入 Environment；失败仅告警，交由后续建连报错暴露 */
    private void inject(ConfigurableEnvironment environment, CredentialFileSource fileSource) {
        try {
            DbCredentials credentials = fileSource.read();
            if (credentials.isEmpty()) {
                log.warn("Empty credentials, skipping injection");
                return;
            }
            MutablePropertySources propertySources = environment.getPropertySources();
            propertySources.addFirst(new MapPropertySource(
                    "vaultStaticCreds",
                    Map.of(
                            "spring.datasource.username", credentials.username(),
                            "spring.datasource.password", credentials.password())));
            log.info("Injected Vault static credentials from mounted files (user={})", credentials.username());
        } catch (Exception exception) {
            log.warn("Failed to read mounted credentials, falling back to configured properties: {}", exception.toString());
        }
    }
}
