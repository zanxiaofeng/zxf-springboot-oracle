package zxf.logging.springboot.cred;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SecretChangeWatcherTest {

    @TempDir
    Path tempDir;

    private ThreadPoolTaskScheduler tps;

    @Test
    void isRelevant_matches_credential_files() {
        assertThat(SecretChangeWatcher.isRelevant("username")).isTrue();
        assertThat(SecretChangeWatcher.isRelevant("password")).isTrue();
        assertThat(SecretChangeWatcher.isRelevant("..data")).isTrue();
        assertThat(SecretChangeWatcher.isRelevant("username.bak")).isTrue();
        assertThat(SecretChangeWatcher.isRelevant("other")).isFalse();
        assertThat(SecretChangeWatcher.isRelevant("readme")).isFalse();
    }

    @Test
    void publishes_event_when_credential_file_changes() throws Exception {
        Path credDir = Files.createDirectories(tempDir.resolve("creds"));
        Files.writeString(credDir.resolve("username"), "u1");
        Files.writeString(credDir.resolve("password"), "p1");
        CredentialFileSource source = new CredentialFileSource(credDir.toString());

        List<Object> published = new ArrayList<>();
        TaskScheduler scheduler = newScheduler();
        SecretChangeWatcher watcher = new SecretChangeWatcher(source, scheduler, published::add);

        watcher.start();

        Files.writeString(credDir.resolve("password"), "p2");

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(published).hasSize(1));
        assertThat(published.get(0)).isInstanceOf(SecretChangedEvent.class);

        watcher.stop();
    }

    @Test
    void start_skips_when_source_unavailable() throws Exception {
        CredentialFileSource source = new CredentialFileSource(tempDir.resolve("nope").toString());
        List<Object> published = new ArrayList<>();
        SecretChangeWatcher watcher =
                new SecretChangeWatcher(source, newScheduler(), published::add);

        watcher.start();

        assertThat(published).isEmpty();
    }

    private TaskScheduler newScheduler() {
        tps = new ThreadPoolTaskScheduler();
        tps.setPoolSize(1);
        tps.afterPropertiesSet();
        return tps;
    }

    @AfterEach
    void tearDown() {
        if (tps != null) {
            tps.shutdown();
        }
    }
}
