package zxf.logging.springboot.cred;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DebouncerTest {

    private ThreadPoolTaskScheduler tps;
    private TaskScheduler scheduler;
    private AtomicInteger actionCalls;

    @BeforeEach
    void setUp() {
        tps = new ThreadPoolTaskScheduler();
        tps.setPoolSize(1);
        tps.afterPropertiesSet();
        scheduler = tps;
        actionCalls = new AtomicInteger();
    }

    @AfterEach
    void tearDown() {
        if (tps != null) {
            tps.shutdown();
        }
    }

    @Test
    void trigger_schedules_action_after_debounce_ms() {
        Debouncer debouncer = new Debouncer(scheduler, 50);

        debouncer.trigger(actionCalls::incrementAndGet);

        // 等待去抖窗口 + 调度执行（Awaitility 轮询，比固定 sleep 稳健）
        Awaitility.await()
                .atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(actionCalls.get()).isEqualTo(1));
    }

    @Test
    void repeated_trigger_cancels_previous_and_runs_once() {
        Debouncer debouncer = new Debouncer(scheduler, 100);

        // 连续触发 5 次，都在去抖窗口内
        for (int i = 0; i < 5; i++) {
            debouncer.trigger(actionCalls::incrementAndGet);
        }

        // 给最后一次触发足够时间执行（若未正确取消，会执行多次）
        Awaitility.await()
                .atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(actionCalls.get()).isEqualTo(1));
    }

    @Test
    void first_trigger_does_not_throw_when_no_pending() {
        Debouncer debouncer = new Debouncer(scheduler, 1000);

        // pending 初始为 null，首次 trigger 不应抛 NPE
        debouncer.trigger(actionCalls::incrementAndGet);
        assertThat(actionCalls.get()).isZero();
    }
}
