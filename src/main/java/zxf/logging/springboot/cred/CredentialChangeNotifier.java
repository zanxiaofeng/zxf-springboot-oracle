package zxf.logging.springboot.cred;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 变更通知器：把文件系统事件委托给 Debouncer 去抖合并后，经 Spring ApplicationEvent 总线广播。
 * 职责单一——只回答「何时通知」，不关心「如何应用」。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CredentialChangeNotifier {
    private final ApplicationEventPublisher publisher;
    private final Debouncer debouncer = new Debouncer();

    /** 去抖发布：窗口内多次信号合并为一次事件 */
    public void signal() {
        debouncer.submit(() -> {
            log.debug("Publishing CredentialsChangedEvent");
            publisher.publishEvent(new CredentialsChangedEvent(Instant.now()));
        });
    }

    /** Debouncer 非 Spring 管理对象，其资源由本 Bean 的 @PreDestroy 代为释放 */
    @PreDestroy
    void shutdown() {
        debouncer.shutdown();
    }

    /**
     * 去抖器：自带单线程虚拟线程调度器，窗口内新任务取消旧任务，仅执行最后一次。
     * 事件监听在该单线程上同步执行，刷新天然串行，无需加锁。
     */
    static final class Debouncer {
        /** 去抖窗口：K8s symlink 原子替换会在极短时间内触发多次事件 */
        private static final Duration DEBOUNCE = Duration.ofMillis(800);

        private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("cred-notify-", 0).factory());
        private final AtomicReference<ScheduledFuture<?>> pending = new AtomicReference<>();

        void submit(Runnable action) {
            ScheduledFuture<?> previous = pending.getAndSet(
                    scheduler.schedule(action, DEBOUNCE.toMillis(), TimeUnit.MILLISECONDS));
            if (previous != null) {
                previous.cancel(false);
            }
        }

        void shutdown() {
            scheduler.shutdownNow();
        }
    }
}
