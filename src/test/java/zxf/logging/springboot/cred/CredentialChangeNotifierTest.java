package zxf.logging.springboot.cred;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CredentialChangeNotifierTest {

    private CredentialChangeNotifier notifier;
    private List<Object> published;

    @BeforeEach
    void setUp() {
        published = new ArrayList<>();
        ApplicationEventPublisher publisher = published::add;
        notifier = new CredentialChangeNotifier(publisher);
    }

    @AfterEach
    void tearDown() {
        if (notifier != null) {
            notifier.shutdown();
        }
    }

    @Test
    void signal_publishes_event_after_debounce_window() {
        notifier.signal();

        // 等待去抖窗口（800ms）+ 虚拟线程调度执行
        Awaitility.await()
                .atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(published).hasSize(1));
        assertThat(published.get(0)).isInstanceOf(CredentialsChangedEvent.class);
    }

    @Test
    void repeated_signals_within_window_collapse_to_single_event() {
        // 连续触发 5 次，都在去抖窗口内，仅最后一次执行
        for (int i = 0; i < 5; i++) {
            notifier.signal();
        }

        Awaitility.await()
                .atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(published).hasSize(1));
    }
}
