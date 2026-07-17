package zxf.logging.springboot.cred;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 启动接线：应用就绪后启动目录监听。
 * dev（无挂载文件）时跳过，沿用 application.yml 凭据。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CredentialWatchBootstrap {

    private final CredentialFileSource fileSource;
    private final SecretDirectoryWatcher watcher;

    /**
     * @Order 保证先于其他 ApplicationReadyEvent 监听完成 WatchService 注册——
     * 先注册、再由 CredentialsChangedListener#alignOnStartup 重读一次，
     * 才能完全收口「ContextInitializer 注入 → 监听注册」间的凭据变更窗口。
     */
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @EventListener(ApplicationReadyEvent.class)
    void start() {
        if (!fileSource.isAvailable()) {
            log.info("Credential dir not available, skipping hot-reload; using datasource credentials from config");
            return;
        }
        try {
            watcher.start();
        } catch (IOException exception) {
            // 失败即终止启动：若降级运行（失去热刷能力），Vault 轮转后将演变为连接故障
            throw new IllegalStateException("Failed to start credential watcher, hot-reload unavailable", exception);
        }
        log.info("Credential watcher started");
    }
}
