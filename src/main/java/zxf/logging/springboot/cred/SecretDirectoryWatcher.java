package zxf.logging.springboot.cred;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.List;

/**
 * 目录监听器：告知文件源完成 WatchService 注册，过滤出与凭据相关的文件事件后上报。
 * 职责单一——只回答「何时发生变化」，不关心「如何通知/应用」。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SecretDirectoryWatcher {

    private final CredentialFileSource fileSource;
    private final CredentialChangeNotifier notifier;

    /** WatchService 资源句柄，由 @PreDestroy 负责关闭 */
    private WatchService watchService;

    /**
     * 启动监听：注册目录并开启独立 daemon 平台线程。
     * 注册在调用线程完成，失败即时暴露给调用方。
     * 监听目录而非单文件，以适配 K8s Secret symlink 原子替换（..data → ..<timestamp>）。
     */
    public void start() throws IOException {
        watchService = FileSystems.getDefault().newWatchService();
        fileSource.registerOn(watchService);
        Thread.ofPlatform().name("secret-watcher").daemon(true).start(this::watchLoop);
        log.info("Watching credentials directory for changes");
    }

    private void watchLoop() {
        try {
            WatchKey key;
            while ((key = watchService.take()) != null) {
                dispatch(key);
            }
        } catch (ClosedWatchServiceException exception) {
            log.info("WatchService closed gracefully");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.info("Watcher interrupted");
        }
    }

    /** 单条 WatchKey 处理：过滤相关事件、复位、上报 */
    private void dispatch(WatchKey key) {
        List<WatchEvent<?>> events = key.pollEvents();
        boolean relevant = events.stream()
                .map(event -> ((Path) event.context()).toString())
                .anyMatch(SecretDirectoryWatcher::isCredentialEvent);
        key.reset();
        if (relevant) {
            notifier.signal();
        }
    }

    /** 仅关心凭据文件与 K8s ..data symlink 的变更 */
    private static boolean isCredentialEvent(String fileName) {
        return fileName.contains("username") || fileName.contains("password") || fileName.equals("..data");
    }

    @PreDestroy
    void shutdown() {
        try {
            if (watchService != null) {
                watchService.close();   // 触发 take() 抛 ClosedWatchServiceException，watch 线程自然退出
            }
        } catch (IOException exception) {
            log.warn("Error closing WatchService: {}", exception.toString());
        }
    }
}
