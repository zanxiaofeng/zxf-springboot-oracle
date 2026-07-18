package zxf.logging.springboot.cred;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
     * 凭据变更事件 → 读取并应用
     */
    @EventListener
    void onCredentialsChanged(CredentialsChangedEvent event) {
        log.debug("Credential change detected at {}", event.detectedAt());
        try {
            applier.apply(fileSource.read());
        } catch (Exception exception) {
            log.error("Failed to apply credentials: {}", exception.toString());
        }
    }
}
