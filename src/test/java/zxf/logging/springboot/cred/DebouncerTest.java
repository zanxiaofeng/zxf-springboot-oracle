package zxf.logging.springboot.cred;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DebouncerTest {

    private TaskScheduler scheduler;
    private AtomicInteger actionCalls;

    @BeforeEach
    void setUp() {
        ThreadPoolTaskScheduler tps = new ThreadPoolTaskScheduler();
        tps.setPoolSize(1);
        tps.afterPropertiesSet();
        scheduler = tps;
        actionCalls = new AtomicInteger();
    }

    @Test
    void trigger_schedules_action_after_debounce_ms() throws Exception {
        Debouncer debouncer = new Debouncer(scheduler, 50);

        debouncer.trigger(actionCalls::incrementAndGet);

        // 等待去抖窗口 + 调度执行
        Thread.sleep(300);
        assertThat(actionCalls.get()).isEqualTo(1);
    }

    @Test
    void repeated_trigger_cancels_previous_and_runs_once() throws Exception {
        Debouncer debouncer = new Debouncer(scheduler, 100);

        // 连续触发 5 次，都在去抖窗口内
        for (int i = 0; i < 5; i++) {
            debouncer.trigger(actionCalls::incrementAndGet);
            Thread.sleep(20);
        }

        Thread.sleep(400);
        // 只应在最后一次触发后的去抖窗口结束后执行一次
        assertThat(actionCalls.get()).isEqualTo(1);
    }

    @Test
    void first_trigger_does_not_throw_when_no_pending() {
        Debouncer debouncer = new Debouncer(scheduler, 1000);

        // pending 初始为 null，首次 trigger 不应抛 NPE
        debouncer.trigger(actionCalls::incrementAndGet);
        assertThat(actionCalls.get()).isZero();
    }
}
