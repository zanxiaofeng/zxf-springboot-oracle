package zxf.logging.springboot.cred;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * K8s 挂载的 Vault Static Role 凭据文件源。
 * 单一职责：定位文件、判断可用性、读取凭据、报告最新 mtime。
 * 既可作 Spring Bean（运行期热刷注入），也可被 BootstrapInitializer 直接 new（上下文创建前）。
 */
@Component
public class CredentialFileSource {

    @Getter
    private final Path dir;
    private final Path usernameFile;
    private final Path passwordFile;

    public CredentialFileSource(@Value("${DB_CRED_DIR:/etc/secrets/db}") String dir) {
        this.dir = Path.of(dir);
        this.usernameFile = this.dir.resolve("username");
        this.passwordFile = this.dir.resolve("password");
    }

    public boolean isAvailable() {
        return Files.isReadable(usernameFile) && Files.isReadable(passwordFile);
    }

    public DbCredentials read() throws IOException {
        return new DbCredentials(
                Files.readString(usernameFile).trim(),
                Files.readString(passwordFile).trim());
    }

    public Instant latestMtime() throws IOException {
        long u = Files.getLastModifiedTime(usernameFile).toMillis();
        long p = Files.getLastModifiedTime(passwordFile).toMillis();
        return Instant.ofEpochMilli(Math.max(u, p));
    }
}
