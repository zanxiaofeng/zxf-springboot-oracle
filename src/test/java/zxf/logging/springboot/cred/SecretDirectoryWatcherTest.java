package zxf.logging.springboot.cred;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class SecretDirectoryWatcherTest {

    @TempDir
    Path tempDir;

    private SecretDirectoryWatcher watcher;

    @Test
    void signals_when_credential_file_changes() throws Exception {
        Path credDir = Files.createDirectories(tempDir.resolve("creds"));
        Files.writeString(credDir.resolve("username"), "u1");
        Files.writeString(credDir.resolve("password"), "p1");
        CredentialFileSource source = new CredentialFileSource(credDir);

        CredentialChangeNotifier notifier = mock(CredentialChangeNotifier.class);
        watcher = new SecretDirectoryWatcher(source, notifier);

        watcher.start();

        Files.writeString(credDir.resolve("password"), "p2");

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> verify(notifier, times(1)).signal());
    }

    @Test
    void start_throws_when_target_dir_missing() {
        // 不可用目录（无 username/password 文件），registerOn 会抛 NoSuchFileException
        CredentialFileSource source = new CredentialFileSource(tempDir.resolve("nope"));
        CredentialChangeNotifier notifier = mock(CredentialChangeNotifier.class);
        watcher = new SecretDirectoryWatcher(source, notifier);

        assertThatThrownBy(() -> watcher.start()).isInstanceOf(IOException.class);
        verify(notifier, never()).signal();
    }

    @AfterEach
    void tearDown() {
        if (watcher != null) {
            watcher.shutdown();
        }
    }
}
