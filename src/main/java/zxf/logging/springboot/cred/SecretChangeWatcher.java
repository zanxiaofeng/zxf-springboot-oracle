package zxf.logging.springboot.cred;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 变化监听器：WatchService（平台线程）+ mtime 轮询（虚拟线程）双机制，去抖后回调。
 * 职责单一——只回答「何时通知变化」，不关心「如何应用」。
 */
@Slf4j
@Component
public class SecretChangeWatcher {

    /** 去抖窗口：K8s symlink 原子替换会在极短时间内触发多次事件，合并为一次回调 */
    private static final long DEBOUNCE_MS = 800;
    /** 轮询周期：即使 WatchService 漏事件（kubelet 同步延迟）也能在此时长内发现 */
    private static final long POLL_INTERVAL_SECONDS = 30;

    private final CredentialFileSource credSource;
    /** 单线程调度器：天然序列化回调执行，无需额外加锁 */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("cred-poll-", 0).factory());
    /** watch 循环用平台线程：WatchService.take() 原生阻塞，JDK21 钉住载体（JEP444/491） */
    private final WatchService watchService;
    private final Thread watcher;

    private volatile Instant lastSeenMtime = Instant.EPOCH;
    private volatile Runnable onChange = () -> {};

    public SecretChangeWatcher(CredentialFileSource credSource) throws IOException {
        this.credSource = credSource;
        this.watchService = FileSystems.getDefault().newWatchService();
        this.watcher = Thread.ofPlatform().name("secret-watcher").unstarted(this::watchServiceLoop);
    }

    /** 注册变化回调（幂等：以最后一次为准） */
    public void onChange(Runnable callback) {
        this.onChange = callback;
    }

    /** 由编排器调用，确保回调注册后再启动，避免漏掉首次变化 */
    public void start() {
        advanceLastSeenMtime();   // 以当前 mtime 为基线，避免启动即误触发
        watcher.start();
        scheduler.scheduleWithFixedDelay(this::pollForChanges,
                POLL_INTERVAL_SECONDS, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);
        log.info("Secret change watcher started. dir={}, poll={}s, debounce={}ms",
                credSource.getDir(), POLL_INTERVAL_SECONDS, DEBOUNCE_MS);
    }

    /** 平台线程：监听目录以适配 K8s Secret symlink 原子替换（..data → ..<timestamp>） */
    private void watchServiceLoop() {
        try {
            credSource.getDir().register(watchService,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_CREATE);
            log.info("WatchService registered on {}", credSource.getDir());
            WatchKey key;
            while ((key = watchService.take()) != null) {
                boolean relevant = key.pollEvents().stream()
                        .map(e -> ((Path) e.context()).toString())
                        .anyMatch(n -> n.contains("username") || n.contains("password") || n.equals("..data"));
                if (relevant) {
                    scheduleNotify();   // 去抖：合并短时多次事件
                }
                key.reset();
            }
        } catch (ClosedWatchServiceException e) {
            log.info("WatchService closed gracefully");
        } catch (IOException e) {
            log.error("WatchService register/loop failed, polling remains active", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("Watcher interrupted, polling remains active");
        }
    }

    /** 轮询兜底 */
    private void pollForChanges() {
        try {
            Instant latest = credSource.latestMtime();
            if (latest.isAfter(lastSeenMtime)) {
                lastSeenMtime = latest;
                scheduleNotify();
            }
        } catch (IOException e) {
            log.warn("Polling check failed: {}", e.getMessage());
        }
    }

    /** 去抖：延后 DEBOUNCE_MS 执行回调；回调须幂等，重复调度无副作用 */
    private void scheduleNotify() {
        advanceLastSeenMtime();   // 防止下一次轮询因 mtime 滞后而重复触发
        scheduler.schedule(onChange, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    private void advanceLastSeenMtime() {
        try {
            lastSeenMtime = credSource.latestMtime();
        } catch (IOException ignored) {
            // 读取失败保留旧值
        }
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
        try {
            watchService.close();   // 触发 take() 抛 ClosedWatchServiceException，watcher 自然退出
        } catch (IOException ignored) {
            // 忽略关闭异常
        }
    }
}
