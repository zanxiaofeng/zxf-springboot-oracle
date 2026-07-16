package zxf.logging.springboot.cred;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * 编排器：把「变化检测」与「凭据应用」解耦，自身只剩读取 + 委派。
 * 启动顺序：注册回调 → 启动监听器 → 对齐一次。
 * 本地 dev（无凭据目录）时优雅降级，跳过热刷新，沿用 application.yml 凭据。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DynamicCredentialRefresher {

    private final CredentialFileSource credSource;
    private final SecretChangeWatcher watcher;
    private final UcpCredentialApplier applier;

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        // 本地 dev：无挂载的凭据目录，跳过热刷新，沿用 application.yml 凭据
        if (!credSource.isAvailable()) {
            log.info("Credential dir not available, skipping hot-reload; using datasource creds from config");
            return;
        }
        watcher.onChange(this::refresh);   // 先注册回调，再启动，确保不漏首次变化
        watcher.start();
        refresh();                         // 启动即对齐一次（凭据已由 ContextInitializer 注入）
        log.info("Dynamic credential refresher started");
    }

    private void refresh() {
        try {
            applier.apply(credSource.read());
        } catch (Exception e) {
            log.error("Failed to read credentials: {}", e.toString());
        }
    }
}
