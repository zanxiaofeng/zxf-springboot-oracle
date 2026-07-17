package zxf.logging.springboot.cred;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * 凭据变更监听：把「事件」翻译为「读取 + 应用」。
 * 事件监听在 Debouncer 的单线程调度器上同步执行，刷新天然串行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CredentialsChangedListener {

    private final CredentialFileSource fileSource;
    private final UcpCredentialApplier applier;

    /**
     * 启动对齐：lastApplied 为空，会以相同凭据触发一次 reconfigure（幂等），
     * 同时验证「读文件 → 应用」链路可用。与 CredentialWatchBootstrap 无顺序依赖。
     */
    @EventListener(ApplicationReadyEvent.class)
    void alignOnStartup() {
        if (!fileSource.isAvailable()) {
            return;   // dev：无挂载，跳过
        }
        refresh();
    }

    /** 凭据变更事件 → 读取并应用 */
    @EventListener
    void onCredentialsChanged(CredentialsChangedEvent event) {
        log.debug("Credential change detected at {}", event.detectedAt());
        refresh();
    }

    private void refresh() {
        try {
            applier.apply(fileSource.read());
        } catch (Exception exception) {
            log.error("Failed to apply credentials: {}", exception.toString());
        }
    }
}
