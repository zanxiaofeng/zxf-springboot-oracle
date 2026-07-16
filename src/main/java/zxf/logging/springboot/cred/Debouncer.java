package zxf.logging.springboot.cred;

import org.springframework.scheduling.TaskScheduler;

import java.time.Instant;
import java.util.concurrent.ScheduledFuture;

/**
 * 去抖器：每次 trigger 取消上一次的延迟任务，重新排一个 debounceMs 后执行。
 * 纯机制类，不感知业务语义。由 SecretChangeWatcher 独占持有（非 Bean，避免有状态单例）。
 */
public class Debouncer {

    private final TaskScheduler scheduler;
    private final long debounceMs;
    private volatile ScheduledFuture<?> pending;

    public Debouncer(TaskScheduler scheduler, long debounceMs) {
        this.scheduler = scheduler;
        this.debounceMs = debounceMs;
    }

    /**
     * 取消上一次待执行的延迟任务，重新调度 action 在 debounceMs 后执行。
     * 仅由 watcher 单一平台线程调用，volatile 保证 stop 后的可见性。
     */
    public void trigger(Runnable action) {
        if (pending != null) {
            pending.cancel(false);
        }
        pending = scheduler.schedule(action, Instant.now().plusMillis(debounceMs));
    }
}
