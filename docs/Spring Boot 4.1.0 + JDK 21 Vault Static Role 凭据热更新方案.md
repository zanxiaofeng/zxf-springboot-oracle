# Spring Boot 4.1.0 + JDK 21 Vault Static Role 凭据热更新方案

## 1. 版本与环境基线

| 组件 | 版本/说明 | 备注 |
|---|---|---|
| JDK | 21 (LTS) | 启用虚拟线程、Records |
| Spring Boot | 4.1.0 | 已迁移完成（openrewrite + properties-migrator） |
| Oracle UCP | 23.26.x（`ucp17`） | 支持 `reconfigureDataSource(Properties)` |
| Oracle JDBC | 23.26.x（`ojdbc17`） | 与 UCP 后缀必须一致（同为 `17`） |
| Vault | 1.18+（Oracle 插件 0.12.x） | Database Secrets Engine (Static Roles)，支持 `self_managed=true` Rootless |
| Oracle Spring Boot Starter | 3.x（`oracle-spring-boot-starter-datasource`） | 绑定 `spring.datasource.oracleucp.*` 前缀（§5.2） |
| ESO | **v2.x**（2026-07 最新 v2.4.1） | `external-secrets.io/v1` API 已稳定；v1beta1 废弃。原 `0.10.3` 仅为 v1 GA 历史最低线 |
| Kubernetes | 1.27+ | 支持 ReadWriteOncePod |

## 2. Vault Static Role 配置（Oracle）

### 2.1 前置：插件与 Instant Client（生产部署易漏）

Vault 默认**不含** Oracle 插件，必须先注册并在 Vault 主机安装 Oracle Instant Client：

```bash
# 1. 注册插件到 catalog（需带 sha256）
vault write sys/plugins/catalog/database/vault-plugin-database-oracle \
  sha256="<二进制 sha256>" \
  command=vault-plugin-database-oracle

# 2. Vault 主机安装 Oracle Instant Client（libclntsh 置于 ld.so.conf 路径，ldconfig 刷新）
#    Enterprise 插件 0.12.x+ 对应 Instant Client 19.26
```

### 2.2 推荐配置：Rootless（`self_managed=true`）

> **推荐采用 Rootless 配置**：`database/config` 无需任何特权 root 账号，每个 static role 自带独立连接，**最小权限**。`vault_admin` 高权账号方案不推荐，仅作 fallback 留在 2.3。

```bash
vault secrets enable database

# Rootless：不传 username/password，加 self_managed=true
vault write database/config/oracle \
  plugin_name="vault-plugin-database-oracle" \
  connection_url="{{username}}/{{password}}@//oracle:1521/XEPDB1" \
  self_managed=true \
  allowed_roles="app-fixed-user"

# Static Role：Rootless 模式需带初始 password；username 为 DB 已存在的固定用户
# rotation_period 单位为秒，生产建议 ≥ 1h；Vault 并无 1h 硬下限
vault write database/static-roles/app-fixed-user \
  db_name=oracle \
  username="APP_USER" \
  password="<初始密码>" \
  rotation_period="24h"
```

> **Rootless 权衡**：不支持 dynamic roles（本方案不需要）；若发生带外密码修改，Vault 与 DB 会失步需手动同步。

### 2.3 最小授权（管理账号方案 / fallback）

若必须用管理账号（非 Rootless），Static Role 仅做密码轮转，管理账号**至少**需 `ALTER USER`：

```sql
-- 最小：仅密码轮转
GRANT ALTER USER TO vault_admin WITH ADMIN OPTION;
GRANT CREATE SESSION TO vault_admin;
-- 可选：revoke 时终止会话
GRANT SELECT ON gv_$session TO vault_admin;
GRANT ALTER SYSTEM TO vault_admin;   -- 仅当需要 KILL SESSION
```

> 动态角色才需 `CREATE USER / DROP USER`。**禁止对 root/admin 账号使用 Static Role**（轮转后 config 连接立即失效，全部凭据操作瘫痪）。

### 2.4 Static Role 核心行为

> Vault 每 24 小时自动调用 Oracle 修改 APP_USER 的密码。应用通过 `/static-creds/app-fixed-user` 获取当前有效密码，用户名始终为 APP_USER。

## 3. ESO 与 K8s Secret

### 3.1 SecretStore

```yaml
apiVersion: external-secrets.io/v1
kind: SecretStore
metadata:
  name: vault-backend
  namespace: app-namespace
spec:
  provider:
    vault:
      server: "http://vault.vault:8200"
      path: "database"
      version: "v2"
      auth:
        kubernetes:
          mountPath: "kubernetes"
          role: "eso-role"
          serviceAccountRef:
            name: eso-sa
```

### 3.2 ExternalSecret

```yaml
apiVersion: external-secrets.io/v1
kind: ExternalSecret
metadata:
  name: oracle-static-creds
  namespace: app-namespace
spec:
  # ⚠️ 刷新间隔必须显著小于 rotation_period
  # 推荐 ≤ rotation_period / 4，确保在下次轮转前拿到新密码
  refreshInterval: "6h"
  secretStoreRef:
    name: vault-backend
    kind: SecretStore
  target:
    name: oracle-credentials
    creationPolicy: Owner
  data:
    - secretKey: username
      remoteRef:
        # ⚠️ 关键区别：static-creds 而非 creds
        key: database/static-creds/app-fixed-user
        property: username
    - secretKey: password
      remoteRef:
        key: database/static-creds/app-fixed-user
        property: password
```

## 4. Pod 挂载与启动安全

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: springboot-app
spec:
  replicas: 2
  selector:
    matchLabels:
      app: springboot-app
  template:
    metadata:
      labels:
        app: springboot-app
    spec:
      serviceAccountName: eso-sa
      # 以 fsGroup 让挂载的 Secret 文件对非 root 容器可读
      securityContext:
        runAsNonRoot: true
        fsGroup: 10001
      initContainers:
        - name: wait-for-secrets
          image: busybox:1.36
          command: ['sh', '-c', 'until [ -s /etc/secrets/db/username ] && [ -s /etc/secrets/db/password ]; do echo "Waiting for secrets..."; sleep 2; done']
          volumeMounts:
            - name: secrets
              mountPath: /etc/secrets/db
              readOnly: true
      containers:
        - name: app
          image: your-image:latest
          volumeMounts:
            - name: secrets
              mountPath: /etc/secrets/db
              readOnly: true
          env:
            - name: DB_CRED_DIR
              value: /etc/secrets/db
          resources:
            requests:
              memory: "512Mi"
              cpu: "250m"
      volumes:
        - name: secrets
          secret:
            secretName: oracle-credentials
            # 收紧挂载文件权限（默认 0644 → 0400，仅属主可读）
            defaultMode: 0400
```

## 5. Spring Boot 配置

### 5.1 pom.xml

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.1.0</version>
</parent>

<properties>
    <java.version>21</java.version>
    <oracle-jdbc.version>23.26.2.0.0</oracle-jdbc.version>
</properties>

<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-webmvc</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-jdbc</artifactId>
    </dependency>
    <!-- 健康检查与 actuator 端点（6.4 节依赖） -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <!-- 绑定 spring.datasource.oracleucp.* 前缀，否则 oracleucp 调优项被原生忽略 -->
    <dependency>
        <groupId>com.oracle.database.spring</groupId>
        <artifactId>oracle-spring-boot-starter-datasource</artifactId>
        <version>3.5.0</version>
    </dependency>
    <!-- ojdbc17 与 ucp17 后缀必须一致，版本由 ojdbc-bom 统一管理 -->
    <dependency>
        <groupId>com.oracle.database.jdbc</groupId>
        <artifactId>ojdbc17</artifactId>
    </dependency>
    <dependency>
        <groupId>com.oracle.database.jdbc</groupId>
        <artifactId>ucp17</artifactId>
    </dependency>
    <dependency>
        <groupId>com.oracle.database.ha</groupId>
        <artifactId>ons</artifactId>
    </dependency>
    <!-- Lombok：@Slf4j / @Getter / @RequiredArgsConstructor（§6 代码依赖），编译期 provided -->
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <scope>provided</scope>
    </dependency>
</dependencies>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>com.oracle.database.jdbc</groupId>
            <artifactId>ojdbc-bom</artifactId>
            <version>${oracle-jdbc.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

### 5.2 application.properties

> ⚠️ Spring Boot **原生不支持** `spring.datasource.ucp.*` 命名空间。UCP 专有属性需通过 Oracle 官方 starter 绑定的 **`spring.datasource.oracleucp.*`** 前缀（需引入 `com.oracle.database.spring:oracle-spring-boot-starter-datasource`，见 §5.1），或自定义 `PoolDataSource` Bean。
>
> 启动凭据由 §6.2 的 `CredentialBootstrapInitializer` 在上下文创建前以最高优先级注入 `spring.datasource.username/password`，覆盖下方 PLACEHOLDER，确保 Flyway/JPA 启动阶段即用真实凭据。

```properties
spring.datasource.url=jdbc:oracle:thin:@//oracle:1521/XEPDB1
spring.datasource.driver-class-name=oracle.jdbc.OracleDriver
spring.datasource.type=oracle.ucp.jdbc.PoolDataSource

# dev 兜底占位符：K8s 环境下会被 §6.2 BootstrapInitializer 注入的真实凭据覆盖
spring.datasource.username=PLACEHOLDER
spring.datasource.password=PLACEHOLDER

# 保持 eager（默认）：懒加载会用 LazyConnectionDataSourceProxy 包装池，§6.3/§6.4 已用 unwrap 兼容；
# 若显式设 lazy，务必保留 unwrap 逻辑，否则 instanceof PoolDataSource 会失败。
spring.datasource.connection-fetch=eager

# UCP 调优（需 Oracle Spring Boot Starter 提供 oracleucp 前缀绑定）
spring.datasource.oracleucp.initial-pool-size=2
spring.datasource.oracleucp.min-pool-size=2
spring.datasource.oracleucp.max-pool-size=20
spring.datasource.oracleucp.validate-connection-on-borrow=true
spring.datasource.oracleucp.sql-for-validate-connection=SELECT 1 FROM DUAL
spring.datasource.oracleucp.connection-wait-timeout=5
spring.datasource.oracleucp.inactive-connection-timeout=300

# Actuator：关闭默认 db 健康检查，改用 §6.4 自定义 dynamicDbHealth（避免与热刷竞争借连接）
management.endpoint.health.show-details=always
management.health.db.enabled=false
```

## 6. Java 代码：凭据热刷

### 6.1 凭据载体与文件源

不可变凭据载体（record 天然线程安全）：

```java
package zxf.logging.springboot.cred;

/**
 * 不可变凭据载体，天然线程安全
 */
public record DbCredentials(String username, String password) {
    public boolean isEmpty() {
        return username == null || username.isBlank()
            || password == null || password.isBlank();
    }
}
```

文件源（单一职责：定位文件、判断可用性、读取凭据、报告 mtime）。既可作 Spring Bean 供运行期热刷注入，也可被 `BootstrapInitializer` 在上下文创建前直接 `new`：

```java
package zxf.logging.springboot.cred;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

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
```

### 6.2 启动期凭据注入（BootstrapRegistryInitializer）

> **启动时序处理**：原方案在 `ApplicationReadyEvent` 才加载真实凭据，但 Flyway/Liquibase、JPA `ddl-auto` 等在上下文刷新阶段（更早）即借连接，会以 `PLACEHOLDER` 建连失败。
> 解法：在 **`ApplicationEnvironmentPreparedEvent`**（环境已就绪、上下文尚未创建）把 `/etc/secrets/db` 中的 username/password 以最高优先级注入 `Environment`，使自动装配出的 `PoolDataSource` 一开始就持有真实凭据。
>
> ⚠️ **注意**：Spring Boot 4.0 已**废弃并标记移除** `EnvironmentPostProcessor`，官方替代是 `BootstrapRegistryInitializer`（通过 bootstrap 注册表挂载 `ApplicationListener`）。下方采用官方推荐写法。

```java
package zxf.logging.springboot.cred;

import org.springframework.boot.BootstrapRegistry;
import org.springframework.boot.BootstrapRegistryInitializer;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * Spring Boot 4 替代废弃的 EnvironmentPostProcessor。
 * 在 ApplicationEnvironmentPreparedEvent（上下文创建前）把 K8s 挂载的凭据
 * 注入 Environment 最高优先级，覆盖 application.yml 中的 PLACEHOLDER，
 * 确保 Flyway/JPA 启动阶段即用真实凭据。dev（无挂载）时静默回退。
 */
public class CredentialBootstrapInitializer implements BootstrapRegistryInitializer {

    @Override
    public void initialize(BootstrapRegistry registry) {
        registry.addApplicationListener(new CredentialEnvironmentInjector());
    }

    static class CredentialEnvironmentInjector
            implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

        @Override
        public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
            ConfigurableEnvironment env = event.getEnvironment();
            // 上下文创建前无 Bean 可注入，直接 new 文件源（与运行期共享同一读取逻辑）
            CredentialFileSource source = new CredentialFileSource(
                    env.getProperty("DB_CRED_DIR", "/etc/secrets/db"));
            if (!source.isAvailable()) {
                return; // dev/无挂载：回退到 application.yml
            }
            try {
                DbCredentials creds = source.read();
                if (!creds.isEmpty()) {
                    env.getPropertySources().addFirst(new MapPropertySource(
                            "vaultStaticCreds",
                            Map.of(
                                    "spring.datasource.username", creds.username(),
                                    "spring.datasource.password", creds.password())));
                }
            } catch (Exception ignored) {
                // 读取失败不阻断启动：交由后续 DataSource 建连时报错暴露
            }
        }
    }
}
```

注册文件 `src/main/resources/META-INF/spring/org.springframework.boot.BootstrapRegistryInitializer.imports`：

```
zxf.logging.springboot.cred.CredentialBootstrapInitializer
```

### 6.3 运行期热刷

按单一职责拆为三个协作类，`DynamicCredentialRefresher` 退化为薄编排器：

| 类 | 职责 |
|---|---|
| `UcpCredentialApplier` | **如何切换**：`unwrap` 取真实 `PoolDataSource` → `reconfigureDataSource` + 多次借连验证 |
| `SecretChangeWatcher` | **何时通知**：`WatchService`（平台线程）+ mtime 轮询（虚拟线程）双机制 + 去抖 |
| `DynamicCredentialRefresher` | **编排**：注册回调、读文件、委派应用、启动即对齐一次 |

> 设计要点：`unwrap` 兼容 `connection-fetch=lazy` 包装；reconfigure 后旧连接未立即销毁需多次借连验证；watch 循环用平台线程（`WatchService.take()` 原生阻塞，JDK21 钉住载体，JEP 491/JDK24 才修复）；`@PreDestroy` 优雅关闭；异常脱敏（仅记 `toString`）。监听器用单线程调度器，回调天然串行化，无需显式加锁。

#### 6.3.1 凭据应用器（UcpCredentialApplier）

```java
package zxf.logging.springboot.cred;

import lombok.extern.slf4j.Slf4j;
import oracle.ucp.jdbc.PoolDataSource;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Properties;

/**
 * 凭据应用器：把新凭据安全切换到 UCP 连接池（reconfigureDataSource + 多次借连验证）。
 * 职责单一——只回答「如何切换」，不关心「何时切换」。
 */
@Slf4j
@Component
public class UcpCredentialApplier {

    /** 验证借连次数：reconfigure 后旧连接未立即销毁，需多次确认拿到新凭据连接 */
    private static final int VERIFY_BORROWS = 3;

    private final PoolDataSource poolDataSource;
    /** 上次成功应用的凭据缓存；用于变更比较，避免调用已废弃的 PoolDataSource.getPassword() */
    private volatile DbCredentials lastApplied = new DbCredentials(null, null);

    public UcpCredentialApplier(DataSource dataSource) {
        // DataSource 可能被 LazyConnectionDataSourceProxy 包装，需 unwrap 到真实 PoolDataSource
        this.poolDataSource = dataSource.isWrapperFor(PoolDataSource.class)
                ? dataSource.unwrap(PoolDataSource.class)
                : (PoolDataSource) dataSource;
    }

    /**
     * 应用凭据：相同则跳过；否则 reconfigure + 验证。
     * @return true=已切换或无变化；false=验证失败（旧连接仍可服务，等下轮重试）
     */
    public boolean apply(DbCredentials creds) {
        if (creds.isEmpty()) {
            log.warn("Read empty credentials, skipping apply");
            return false;
        }
        if (creds.equals(lastApplied)) {
            log.debug("Credentials unchanged, skip");
            return true;
        }
        try {
            log.info("Refreshing UCP credentials for user: {}", creds.username());
            poolDataSource.reconfigureDataSource(toProps(creds));
            if (!verifyNewCredentials()) {
                // 失败不回退——旧连接仍可服务，等待下一轮重试
                log.error("Credential verification FAILED; old connections may still be served, will retry next cycle");
                return false;
            }
            lastApplied = creds;
            log.info("UCP credentials refreshed & verified. borrowed={}, available={}",
                    poolDataSource.getBorrowedConnectionsCount(),
                    poolDataSource.getAvailableConnectionsCount());
            return true;
        } catch (Exception e) {
            // 异常栈含 Properties（含密码），仅记录消息，避免密码进入日志
            log.error("Failed to refresh UCP credentials: {}", e.toString());
            return false;
        }
    }

    private static Properties toProps(DbCredentials creds) {
        Properties props = new Properties();
        props.setProperty("user", creds.username());
        props.setProperty("password", creds.password());
        return props;
    }

    /**
     * reconfigureDataSource 后，池中旧连接被标记过期但不会立即销毁，
     * 紧接着借一条可能分到旧连接（旧密码仍有效）→ 误判。
     * 解法：连续借多条并 isValid，确认至少能以新凭据成功建连。
     */
    private boolean verifyNewCredentials() {
        for (int i = 0; i < VERIFY_BORROWS; i++) {
            try (Connection c = poolDataSource.getConnection()) {
                if (!c.isValid(3)) {
                    return false;
                }
            } catch (Exception e) {
                log.warn("Verify borrow #{} failed: {}", i, e.toString());
                return false;
            }
        }
        return true;
    }
}
```

#### 6.3.2 变化监听器（SecretChangeWatcher）

```java
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
```

#### 6.3.3 编排器（DynamicCredentialRefresher）

```java
package zxf.logging.springboot.cred;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * 编排器：把「变化检测」与「凭据应用」解耦，自身只剩读取 + 委派。
 * 启动顺序：注册回调 → 启动监听器 → 对齐一次。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DynamicCredentialRefresher {

    private final CredentialFileSource credSource;
    private final SecretChangeWatcher watcher;
    private final UcpCredentialApplier applier;

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        watcher.onChange(this::refresh);   // 先注册回调，再启动，确保不漏首次变化
        watcher.start();
        refresh();                         // 启动即对齐一次（凭据已由 BootstrapInitializer 注入）
        log.info("Dynamic credential refresher started");
    }

    private void refresh() {
        try {
            applier.apply(credSource.read());
        } catch (Exception e) {
            log.error("Failed to read credentials: {}", e.toString());
        }
    }
}
```

### 6.4 健康检查

> `connection-fetch=lazy` 下注入的可能是 `LazyConnectionDataSourceProxy`；健康检查直接用 `DataSource` 即可（`isValid` 会触发真实建连），如需读 UCP 统计可 `unwrap`。

```java
package zxf.logging.springboot.cred;

import oracle.ucp.jdbc.PoolDataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;

@Component("dynamicDbHealth")
@RequiredArgsConstructor
public class DatabaseHealthIndicator implements HealthIndicator {

    private final DataSource dataSource;

    @Override
    public Health health() {
        try (Connection c = dataSource.getConnection()) {
            if (c.isValid(2)) {
                Health.Builder up = Health.up().withDetail("pool", dataSource.getClass().getSimpleName());
                // 可选：暴露 UCP 统计便于排障
                if (dataSource.isWrapperFor(PoolDataSource.class)) {
                    PoolDataSource pds = dataSource.unwrap(PoolDataSource.class);
                    up.withDetail("borrowed", pds.getBorrowedConnectionsCount())
                      .withDetail("available", pds.getAvailableConnectionsCount());
                }
                return up.build();
            }
            return Health.down().withDetail("error", "Connection invalid").build();
        } catch (Exception e) {
            return Health.down(e).build();
        }
    }
}
```

## 7. Static Role vs Dynamic Role 关键差异速查

| 维度 | Dynamic Role | Static Role（本版） |
|---|---|---|
| Vault 路径 | database/creds/&lt;role&gt; | database/static-creds/&lt;role&gt; |
| 用户名 | 每次请求生成新用户名 | 固定不变 |
| 密码变更触发 | 每次请求生成新密码 | Vault 按 rotation_period 自动轮转 |
| ESO refreshInterval | &lt; default_ttl（如 30m &lt; 1h） | ≤ rotation_period / 4（如 6h &lt; 24h） |
| 数据库用户管理 | Vault 自动 CREATE/DROP | 需预先手动创建，Vault 仅 ALTER PASSWORD |
| 审计粒度 | 每个实例独立用户名 | 所有实例共享同一用户名 |
| 适用场景 | 微服务、临时访问 | 遗留系统、用户名不可变的第三方集成 |

## 8. ⚠️ Static Role 生产注意事项

- **禁止对 root/admin 账号使用 Static Role**：Vault 轮转密码时不区分普通用户和管理员。若 vault_admin 被设为 Static Role，轮转后 database/config/oracle 中的连接凭据立即失效，导致所有凭据操作瘫痪。如需轮转 root 凭据，请使用专用 API `vault write -force database/rotate-root/oracle`。
- **rotation_period 建议值**：Vault 以秒为单位，**无 1h 硬下限**；生产建议 ≥ 1h 以避免频繁轮转压力。过短值虽不被拒绝，但会造成 DB 负载与连接抖动。
- **首次创建 Static Role 时 Vault 会立即轮转密码**：执行 `vault write database/static-roles/...` 的瞬间，数据库中该用户的密码即被修改。请确保此时没有正在使用该用户的生产连接，或已在维护窗口内操作。
- **ESO refreshInterval 必须留足余量**：若 rotation_period=24h，建议 refreshInterval=6h。若设置为接近 24h，可能在 Vault 轮转后、ESO 刷新前的窗口期内，应用仍持有旧密码导致连接失败。
- **Java 代码无需区分 Static/Dynamic**：上述 DynamicCredentialRefresher 对两种模式完全兼容。Static Role 下用户名虽不变，但密码变化仍会触发 `reconfigureDataSource(Properties)`，逻辑自洽。

# 关键决策参考资料

## 1. Vault Static Role 机制与约束

- **HashiCorp Vault Database Secrets Engine - Static Roles**
  - **关键决策支撑**：确认 Static Role 仅轮转密码而不改变用户名；确认 rotation_period 以秒为单位且无 1h 硬下限（生产建议 ≥ 1h）；确认首次创建时立即触发密码轮转；确认 /static-creds/ API 路径。
  - **参考来源**：[Vault Docs - Database Secrets Engine - Static Roles](https://developer.hashicorp.com/vault/docs/secrets/databases#static-roles) · [Vault API - Static Roles](https://developer.hashicorp.com/vault/api-docs/secret/databases#static-roles)

- **Vault Oracle Database Secrets Engine（含 Rootless / self_managed）**
  - **关键决策支撑**：确认 Oracle 插件需独立下载注册 + Oracle Instant Client；确认 `self_managed=true` Rootless 配置可免除 root 账号；确认 SSL/TNS/Wallet 连接写法。
  - **参考来源**：[Vault Docs - Oracle Database Secrets Engine](https://developer.hashicorp.com/vault/docs/secrets/databases/oracle)

- **Vault Root Credential Rotation API**
  - **关键决策支撑**：确认 root/admin 账号不应使用 Static Role，而应通过专用 database/rotate-root/&lt;name&gt; 端点单独管理，避免连接配置失效。
  - **参考来源**：[Vault API - Rotate Root Credentials](https://developer.hashicorp.com/vault/api-docs/secret/databases#rotate-root-credentials)

## 2. Oracle UCP 热更新能力

- **Oracle Universal Connection Pool (UCP) Developer's Guide**
  - **关键决策支撑**：确认 `PoolDataSource.reconfigureDataSource(Properties)` 可在不销毁连接池对象的前提下平滑切换凭据；确认旧连接被标记为过期、新连接使用新凭据的行为语义。**该方法签名需传入 `Properties` 参数且抛 `SQLException`，不存在无参重载。**
  - **参考来源**：[Oracle UCP Developer's Guide (23)](https://docs.oracle.com/en/database/oracle/oracle-database/23/jjucp/index.html) · [Managing UCP Connections](https://docs.oracle.com/en/database/oracle/oracle-database/23/jjucp/managing-connections.html)

- **Spring Boot DataSource Auto-Configuration**
  - **关键决策支撑**：确认 `spring.datasource.type=oracle.ucp.jdbc.PoolDataSource` 可被 Spring Boot 自动装配识别；**确认 UCP 专有属性需使用 `spring.datasource.oracleucp.*` 前缀（由 Oracle Spring Boot Starter 绑定），`spring.datasource.ucp.*` 不被原生支持；确认 4.1 新增 `connection-fetch=lazy` 会以 `LazyConnectionDataSourceProxy` 包装池，影响 `instanceof` 判断。**
  - **参考来源**：[Spring Boot 4.1 Reference - Data Access](https://docs.spring.io/spring-boot/docs/4.1.0/reference/htmlsingle/#data.sql.datasource) · [Spring Boot 4.1 Release Notes](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.1-Release-Notes)

## 3. External Secrets Operator (ESO) API 演进

- **ESO v1 API GA 与版本演进**
  - **关键决策支撑**：`external-secrets.io/v1` 自 v0.10.0 引入、v0.10.3 GA；但**截至 2026-07 Operator 已发布至 v2.4.1**（v1beta1 已废弃）。建议基线锁定 v2.x，YAML 中 `apiVersion: external-secrets.io/v1` 不变。
  - **参考来源**：[ESO GitHub Releases](https://github.com/external-secrets/external-secrets/releases) · [ESO Docs 首页](https://external-secrets.io/latest/)

- **ESO Vault Provider - Static Credentials**
  - **关键决策支撑**：确认 ESO Vault Provider 支持 database/static-creds/ 路径的 remoteRef.key 配置，以及 refreshInterval 与 Vault TTL/rotation_period 的配合策略。
  - **参考来源**：[ESO Docs - HashiCorp Vault Provider](https://external-secrets.io/latest/provider/hashicorp-vault/)

## 4. Kubernetes Secret 挂载与文件监听

- **Kubernetes Secrets - Mounted Secrets Update Mechanism**
  - **关键决策支撑**：确认 K8s 更新 mounted Secret 时使用 symlink 原子替换（..data → ..<timestamp>），而非原地修改文件内容；更新传播存在最长等同 kubelet 同步周期的延迟；WatchService 必须监听目录而非单个文件才能捕获此行为。
  - **参考来源**：[Kubernetes Docs - Secrets - Mounted Secrets are updated automatically](https://kubernetes.io/docs/concepts/configuration/secret/#mounted-secrets-are-updated-automatically)

- **JDK Virtual Threads 规范与钉住限制**
  - **关键决策支撑**：JEP 444（JDK 21）确认虚拟线程适用于阻塞型 I/O 任务，但 `synchronized` 代码块/原生阻塞会钉住载体线程；`WatchService.take()` 属原生阻塞，钉住问题要到 **JEP 491（JDK 24）** 才解除。`Thread.ofVirtual().factory()` 可用于 `ScheduledExecutorService`。
  - **参考来源**：[JEP 444: Virtual Threads](https://openjdk.org/jeps/444) · [JEP 491: Synchronize Virtual Threads without Pinning](https://openjdk.org/jeps/491)

## 5. Spring Boot 4 兼容性

- **Spring Framework 7 / Spring Boot 4 已验证项**
  - **关键决策支撑**：本项目已升级至 Spring Boot 4.1.0（经 openrewrite UpgradeSpringBoot_4_0 + properties-migrator 迁移）；确认虚拟线程可作为默认 Web 容器线程模型；确认 DataSourceBuilder 扩展机制向后兼容；当前独立 Service 实现方式不依赖任何已废弃的内部 API。
  - **参考来源**：[Spring Boot 4.1 Release Notes](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.1-Release-Notes) · [Spring Boot 4.0 升级 Wiki](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Release-Notes)
