package zxf.logging.springboot.cred;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 变化监听器：WatchService（平台线程）检测凭据目录变化，去抖后发布 SecretChangedEvent。
 * 职责单一——只回答「何时通知变化」，不关心「如何应用」。
 * 生命周期由 Spring 容器以 SmartLifecycle 托管；去抖委托给 Debouncer（走 TaskScheduler）。
 */
@Slf4j
@Component
public class SecretChangeWatcher implements SmartLifecycle {

    /** 去抖窗口：K8s symlink 原子替换会在极短时间内触发多次事件，合并为一次回调 */
    private static final long DEBOUNCE_MS = 800;

    private final CredentialFileSource credSource;
    private final ApplicationEventPublisher publisher;
    /** watch 循环用平台线程：WatchService.take() 原生阻塞，JDK21 钉住载体（JEP444/491） */
    private final WatchService watchService;
    private final Thread watcher;
    private final Debouncer debouncer;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public SecretChangeWatcher(CredentialFileSource credSource,
                               TaskScheduler taskScheduler,
                               ApplicationEventPublisher publisher) throws IOException {
        this.credSource = credSource;
        this.publisher = publisher;
        this.watchService = FileSystems.getDefault().newWatchService();
        this.watcher = Thread.ofPlatform().name("secret-watcher").unstarted(this::watchServiceLoop);
        this.debouncer = new Debouncer(taskScheduler, DEBOUNCE_MS);
    }

    /** 由容器在 refresh 阶段自动调用 start() */
    @Override
    public boolean isAutoStartup() {
        return true;
    }

    /** 命中规则：包名含 username/password，或为 K8s symlink 原子替换标记 ..data */
    static boolean isRelevant(String name) {
        return name.contains("username") || name.contains("password") || name.equals("..data");
    }

    @Override
    public void start() {
        // 本地 dev：无挂载的凭据目录，跳过监听（沿用 application.yml 凭据）
        if (!credSource.isAvailable()) {
            log.info("Credential dir not available, SecretChangeWatcher stays idle");
            return;
        }
        running.set(true);
        try {
            credSource.getDir().register(watchService,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_CREATE);
        } catch (IOException e) {
            running.set(false);
            log.error("WatchService register failed, change detection disabled until restart", e);
            return;
        }
        watcher.start();
        log.info("Secret change watcher started. dir={}, debounce={}ms", credSource.getDir(), DEBOUNCE_MS);
    }

    @Override
    public void stop() {
        running.set(false);
        try {
            watchService.close();
        } catch (IOException ignored) {
            // 忽略关闭异常
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    /** 平台线程：监听目录以适配 K8s Secret symlink 原子替换（..data → ..<timestamp>） */
    private void watchServiceLoop() {
        try {
            WatchKey key;
            while ((key = watchService.take()) != null) {
                boolean relevant = key.pollEvents().stream()
                        .map(e -> ((Path) e.context()).toString())
                        .anyMatch(SecretChangeWatcher::isRelevant);
                if (relevant) {
                    debouncer.trigger(this::publishChanged);
                }
                key.reset();
            }
        } catch (ClosedWatchServiceException e) {
            log.info("WatchService closed gracefully");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("Watcher interrupted");
        }
    }

    private void publishChanged() {
        publisher.publishEvent(new SecretChangedEvent());
    }
}
