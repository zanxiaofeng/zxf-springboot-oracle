package zxf.logging.springboot.cred;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchService;

/**
 * K8s 挂载的 Vault Static Role 凭据文件源。
 * 单一职责：定位文件、判断可用性、读取凭据、完成 WatchService 注册。
 * 无 getter——外部不询问 dir，而是告知它完成注册（Tell, Don't Ask）。
 * 既可作 Spring Bean 供运行期热刷注入，也可被 ContextInitializer 在上下文创建前直接 new。
 */
@Component
@RequiredArgsConstructor
public class CredentialFileSource {
    /** @Value 经 lombok.config copyableAnnotations 复制到构造参数，实现构造注入 */
    @Value("${DB_CRED_DIR:~/secrets/db}")
    private final Path dir;

    public boolean isAvailable() {
        return Files.isReadable(usernameFile()) && Files.isReadable(passwordFile());
    }

    public DbCredentials read() throws IOException {
        String username = Files.readString(usernameFile());
        String password = Files.readString(passwordFile());
        return new DbCredentials(username.trim(), password.trim());
    }

    /** 写回新密码（模拟 ESO/kubelet 更新挂载文件），触发 watcher 热刷管线 */
    public void writePassword(String newPassword) throws IOException {
        Files.writeString(passwordFile(), newPassword);
    }

    /** Tell 风格：由文件源自己完成 WatchService 注册，而非暴露 dir 供外部询问 */
    public void registerOn(WatchService watchService) throws IOException {
        dir.register(watchService,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_CREATE);
    }

    /** 派生路径用纯函数方法，无需字段 */
    private Path usernameFile() {
        return dir.resolve("username");
    }

    private Path passwordFile() {
        return dir.resolve("password");
    }
}
