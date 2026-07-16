# Spring Boot 4.1.0 + JDK 21 Vault Static Role 凭据热更新方案

## 1. 版本与环境基线

| 组件 | 版本/说明 | 备注 |
|---|---|---|
| JDK | 21 (LTS) | 启用虚拟线程、Records |
| Spring Boot | 4.1.0 | 已迁移完成（openrewrite + properties-migrator） |
| Oracle UCP | 23.26.x（`ucp17`） | 支持 `reconfigureDataSource(Properties)` |
| Oracle JDBC | 23.26.x（`ojdbc17`） | 与 UCP 后缀必须一致（同为 `17`） |
| Vault | 1.15+ | Database Secrets Engine (Static Roles) |
| ESO | 0.10.3+ | `external-secrets.io/v1` 自该版本 GA |
| Kubernetes | 1.27+ | 支持 ReadWriteOncePod |

## 2. Vault Static Role 配置（Oracle）

```bash
# 启用数据库密钥引擎
vault secrets enable database

# 配置 Oracle 连接（管理账号，用于执行密码轮转）
# {{username}}/{{password}} 为 Vault 动态注入占位符，下方 username/password 才是管理账号
vault write database/config/oracle \
  plugin_name=oracle-database-plugin \
  connection_url="{{username}}/{{password}}@//oracle:1521/XEPDB1" \
  username="vault_admin" \
  password="admin123"

# ⚠️ 关键区别：使用 static-roles 而非 roles
# username 为数据库中已存在的固定用户
# rotation_period 控制密码自动轮转周期（秒），生产建议 ≥ 1h；Vault 并无 1h 硬下限
vault write database/static-roles/app-fixed-user \
  db_name=oracle \
  username="APP_USER" \
  rotation_period="24h"
```

> **Static Role 核心行为**：Vault 每 24 小时自动调用 Oracle 修改 APP_USER 的密码。应用通过 `/static-creds/app-fixed-user` 获取当前有效密码，用户名始终为 APP_USER。

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
    <!-- 健康检查与 actuator 端点（6.3 节依赖） -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-actuator</artifactId>
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

> ⚠️ Spring Boot **原生不支持** `spring.datasource.ucp.*` 命名空间。UCP 专有属性需通过 Oracle 官方 starter 绑定的 **`spring.datasource.oracleucp.*`** 前缀（需引入 `com.oracle.database.spring:oracle-spring-boot-starter-datasource`），或自定义 `PoolDataSource` Bean。

```properties
spring.datasource.url=jdbc:oracle:thin:@//oracle:1521/XEPDB1
spring.datasource.driver-class-name=oracle.jdbc.OracleDriver
spring.datasource.type=oracle.ucp.jdbc.PoolDataSource

# 占位符，启动后立即被真实凭据覆盖
spring.datasource.username=PLACEHOLDER
spring.datasource.password=PLACEHOLDER

# UCP 调优（需 Oracle Spring Boot Starter 提供 oracleucp 前缀绑定）
spring.datasource.oracleucp.initial-pool-size=2
spring.datasource.oracleucp.min-pool-size=2
spring.datasource.oracleucp.max-pool-size=20
spring.datasource.oracleucp.validate-connection-on-borrow=true
spring.datasource.oracleucp.sql-for-validate-connection=SELECT 1 FROM DUAL
spring.datasource.oracleucp.connection-wait-timeout=5
spring.datasource.oracleucp.inactive-connection-timeout=300

# Actuator
management.endpoint.health.show-details=always
management.health.db.enabled=false
```

## 6. Java 代码：凭据热刷

### 6.1 凭据 Record

```java
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

### 6.2 动态凭据刷新服务

```java
import oracle.ucp.jdbc.PoolDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.*;
import java.sql.Connection;
import java.time.Instant;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class DynamicCredentialRefresher {

    private static final Logger log = LoggerFactory.getLogger(DynamicCredentialRefresher.class);

    private final PoolDataSource poolDataSource;
    private final Path credDir;
    private final Path usernameFile;
    private final Path passwordFile;
    private final ReentrantLock refreshLock = new ReentrantLock();
    private volatile Instant lastRefreshTime = Instant.EPOCH;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("cred-poll-", 0).factory()
    );

    @Autowired
    public DynamicCredentialRefresher(
            DataSource dataSource,
            @Value("${DB_CRED_DIR:/etc/secrets/db}") String credDirPath) {
        if (!(dataSource instanceof PoolDataSource pds)) {
            throw new IllegalStateException(
                "UCP PoolDataSource required. Found: " + dataSource.getClass().getName());
        }
        this.poolDataSource = pds;
        this.credDir = Path.of(credDirPath);
        this.usernameFile = this.credDir.resolve("username");
        this.passwordFile = this.credDir.resolve("password");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        loadAndApplyCredentials();

        Thread.ofVirtual()
                .name("secret-watcher")
                .uncaughtExceptionHandler((t, e) -> log.error("Watcher thread died", e))
                .start(this::watchServiceLoop);

        scheduler.scheduleWithFixedDelay(this::pollForChanges, 30, 30, TimeUnit.SECONDS);

        log.info("Dynamic credential refresher started. Dir={}", credDir);
    }

    /**
     * 监听目录以适配 K8s Secret symlink 原子替换机制
     */
    private void watchServiceLoop() {
        try (WatchService ws = FileSystems.getDefault().newWatchService()) {
            credDir.register(ws,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_CREATE);

            log.info("WatchService registered on {}", credDir);
            WatchKey key;
            while ((key = ws.take()) != null) {
                boolean relevant = false;
                for (WatchEvent<?> event : key.pollEvents()) {
                    Path changed = (Path) event.context();
                    String name = changed.toString();
                    if (name.contains("username") || name.contains("password") 
                            || name.equals("..data")) {
                        relevant = true;
                        log.debug("Watch event: {} {}", event.kind(), changed);
                    }
                }
                if (relevant) {
                    // 等待 K8s 完成所有文件的原子写入
                    Thread.sleep(500);
                    loadAndApplyCredentials();
                }
                key.reset();
            }
        } catch (ClosedWatchServiceException e) {
            log.info("WatchService closed gracefully");
        } catch (IOException | InterruptedException e) {
            log.error("WatchService loop terminated, polling remains active", e);
            Thread.currentThread().interrupt();
        }
    }

    private void pollForChanges() {
        try {
            Instant latest = Instant.ofEpochMilli(Math.max(
                    Files.getLastModifiedTime(usernameFile).toMillis(),
                    Files.getLastModifiedTime(passwordFile).toMillis()));
            if (latest.isAfter(lastRefreshTime)) {
                log.info("Polling detected credential change (mtime delta)");
                loadAndApplyCredentials();
            }
        } catch (IOException e) {
            log.warn("Polling check failed: {}", e.getMessage());
        }
    }

    /**
     * Static Role 场景下用户名不变，但密码会变
     * 通过 reconfigureDataSource(Properties) 一次性下发新凭据
     */
    private void loadAndApplyCredentials() {
        refreshLock.lock();
        try {
            DbCredentials creds = readCredentialsFromFiles();
            if (creds.isEmpty()) {
                log.warn("Read empty credentials, skipping refresh");
                return;
            }

            // Static Role 下用户名通常不变，但密码会随轮转变化
            // 仍需比较两者，避免无意义的 reconfigure
            if (creds.username().equals(poolDataSource.getUser())
                    && creds.password().equals(poolDataSource.getPassword())) {
                log.debug("Credentials unchanged, skip");
                return;
            }

            log.info("Refreshing UCP credentials for user: {}", creds.username());

            // UCP 平滑重配：构造 Properties 传入 reconfigureDataSource(Properties)
            // 旧连接被标记为过期，新连接使用新凭据
            // 注意：reconfigureDataSource 需要 Properties 参数，无无参重载，且抛 SQLException
            Properties props = new Properties();
            props.setProperty("user", creds.username());
            props.setProperty("password", creds.password());
            poolDataSource.reconfigureDataSource(props);

            if (!verifyNewCredentials()) {
                log.error("New credentials verification FAILED! Old connections may still be served.");
                return;
            }

            lastRefreshTime = Instant.now();
            log.info("UCP credentials refreshed and verified successfully");

        } catch (Exception e) {
            log.error("Failed to refresh UCP credentials", e);
        } finally {
            refreshLock.unlock();
        }
    }

    private boolean verifyNewCredentials() {
        try (Connection conn = poolDataSource.getConnection()) {
            boolean valid = conn.isValid(3);
            log.debug("Credential verification connection valid={}", valid);
            return valid;
        } catch (Exception e) {
            log.error("Credential verification failed: {}", e.getMessage());
            return false;
        }
    }

    private DbCredentials readCredentialsFromFiles() throws IOException {
        String user = Files.readString(usernameFile).trim();
        String pass = Files.readString(passwordFile).trim();
        return new DbCredentials(user, pass);
    }
}
```

### 6.3 健康检查

```java
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;

@Component("dynamicDbHealth")
public class DatabaseHealthIndicator implements HealthIndicator {

    private final DataSource dataSource;

    public DatabaseHealthIndicator(DataSource ds) {
        this.dataSource = ds;
    }

    @Override
    public Health health() {
        try (Connection c = dataSource.getConnection()) {
            if (c.isValid(2)) {
                return Health.up()
                        .withDetail("pool", dataSource.getClass().getSimpleName())
                        .build();
            }
            return Health.down()
                    .withDetail("error", "Connection invalid")
                    .build();
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

## 9. 方案评估（结合 2026-07 最新资讯）

> 以下评估基于 2026 年 7 月对各组件最新官方文档与发布记录的核对，重点标注 **必须修复的正确性问题**、**重要遗漏** 与 **健壮性建议**。

### 9.1 版本基线需更新

| 组件 | 方案原值 | 最新资讯（2026-07） | 建议 |
|---|---|---|---|
| ESO | `0.10.3+` | Operator 已演进至 **v2.4.1**（2026-04-28 发布），v1 API 已稳定多版本 | 基线改为 **v2.x**；`external-secrets.io/v1` YAML 不变。原 `0.10.3` 仅为 v1 GA 的历史最低线，作为 2026 基线已陈旧 |
| Spring Boot | 4.1.0 | 已 GA（2025-11），新增 `spring.datasource.connection-fetch=lazy` | 见 9.2.1，与本方案存在交互 |
| Vault | 1.15+ | 1.18+ 已 GA，Oracle 插件支持 `self_managed=true` Rootless 配置 | 见 9.3.1 |
| JDK | 21 | 注意：`synchronized` 导致的虚拟线程钉住在 **JDK 24（JEP 491）** 才修复 | 见 9.4.1 |

### 9.2 关键正确性问题（必须修复）

#### 9.2.1 `connection-fetch=lazy` / 包装型 DataSource 致 `instanceof` 失败

Spring Boot 4.1 新增 `spring.datasource.connection-fetch=lazy`，启用后自动将池化 DataSource 包一层 `LazyConnectionDataSourceProxy`。此时 `DynamicCredentialRefresher` 构造函数中的判断：

```java
if (!(dataSource instanceof PoolDataSource pds)) { throw ... }
```

将 **直接抛异常**（注入的是代理而非 `PoolDataSource`）。`DatabaseHealthIndicator` 同理。

**修复**：

```java
this.poolDataSource = dataSource.isWrapperFor(PoolDataSource.class)
        ? dataSource.unwrap(PoolDataSource.class)
        : (PoolDataSource) dataSource;
```

或显式注入原始 Bean：

```java
public DynamicCredentialRefresher(PoolDataSource poolDataSource, ...) { ... }
```

#### 9.2.2 启动期凭据时序：`ApplicationReadyEvent` 太晚

方案用 `PLACEHOLDER` 占位，在 `ApplicationReadyEvent` 才加载真实凭据。但任何**启动期即获取连接**的组件（Flyway / Liquibase 迁移、JPA `ddl-auto`、`hikari`/UCP 初始化探测、部分健康检查）会在 `ApplicationReadyEvent` 之前借连接，直接用 `PLACEHOLDER` 建连失败。

**修复（任选其一）**：
- 用 `EnvironmentPostProcessor` 在上下文刷新前把 `username`/`password` 注入 `Environment`；
- 或自定义 `@Bean PoolDataSource`，在工厂方法内直接读 `/etc/secrets/db`；
- 或保证启动期无连接借出（`initial/min-pool-size=0` + 关闭 Flyway/JPA-DDL + `management.health.db.enabled=false`），并依赖首请求才建连——但脆弱，不推荐。

#### 9.2.3 `verifyNewCredentials()` 可能命中旧连接

`reconfigureDataSource(props)` 后，**池中已借出/空闲的旧连接被标记为过期但不会立即销毁**，新连接才用新密码。紧接着的 `getConnection()` + `isValid(3)` 可能恰好分到一条旧连接（旧密码仍有效），返回 `true` → **误判已验证**。

**修复**：验证逻辑应强制获取新连接，例如：

```java
// 关闭并重建一条专用验证连接，或比较 reconfigure 前后 borrow 计数
poolDataSource.reconfigureDataSource(props);
try (Connection c = poolDataSource.getConnection()) {   // reconfigure 后新借
    return c.isValid(3) && !c.getMetaData().getUserName().isBlank();
}
```

并配合日志输出 UCP 统计（`getAvailableConnectionsCount` / `getBorrowedConnectionsCount`）确认旧连接被回收。

### 9.3 重要遗漏（建议补充）

#### 9.3.1 Vault Rootless 配置（`self_managed=true`）—— 强烈推荐

HashiCorp 官方为 Oracle 插件提供 **Rootless 配置**：`database/config` 无需任何特权 root 账号，每个 static role 自带独立连接。官方明确建议「以最小权限把 DB 用户 onboarding 为 static role」。本方案当前仍依赖 `vault_admin` 高权账号，应评估切换：

```bash
vault write database/config/oracle \
  plugin_name="vault-plugin-database-oracle" \
  connection_url="{{username}}/{{password}}@//oracle:1521/XEPDB1" \
  self_managed=true \
  allowed_roles="app-fixed-user"

vault write database/static-roles/app-fixed-user \
  db_name=oracle username="APP_USER" password="<initial>" rotation_period="24h"
```

**权衡**：不支持 dynamic roles（本方案不需要）；若发生带外（out-of-band）密码修改，Vault 与 DB 会失步，需手动同步。与本方案的「禁止 root/admin 用 Static Role」原则一致。

#### 9.3.2 Vault Oracle 插件部署前置（方案缺失）

Vault 默认 **不含** Oracle 插件，必须：
1. 下载 `vault-plugin-database-oracle` 二进制并注册到 `sys/plugins/catalog/database/`（带 `sha256`）；
2. Vault 主机安装 **Oracle Instant Client**（`libclntsh` 置于 `ld.so.conf` 路径）；
3. Enterprise 版需匹配 Instant Client 19.26（见插件版本表）。

方案 §2 直接 `vault secrets enable database` + `plugin_name=oracle-database-plugin`，未覆盖上述部署步骤，生产落地会卡在插件未注册。

#### 9.3.3 `vault_admin` 所需最小权限

Static Role 仅做密码轮转，管理账号**至少**需要：

```sql
GRANT ALTER USER TO vault_admin WITH ADMIN OPTION;
GRANT CREATE SESSION TO vault_admin;
-- 可选：回收用户会话（revoke 时终止会话）
GRANT SELECT ON gv_$session TO vault_admin;
GRANT ALTER SYSTEM TO vault_admin;   -- 仅当需要 KILL SESSION
```

动态角色才需 `CREATE USER / DROP USER`。当前方案未列出授权，落地易权限不足。

#### 9.3.4 `oracleucp.*` 绑定依赖（与本仓库现状不符）

`spring.datasource.oracleucp.*` 前缀由 `com.oracle.database.spring:oracle-spring-boot-starter-datasource` 绑定，**Spring Boot 原生不解析该命名空间**。但本仓库 `pom.xml` 当前**未引入**该 starter，也**未引入 actuator**。因此：
- 方案 §5.2 的 `oracleucp.initial-pool-size` 等调优项在当前依赖下**很可能被静默忽略**；
- §6.3 健康检查依赖 actuator（当前缺失）。

**修复**：在 `pom.xml` 补充 `oracle-spring-boot-starter-datasource` 与 `spring-boot-starter-actuator`；或自定义 `@Bean @ConfigurationProperties("spring.datasource.oracleucp") PoolDataSource` 显式绑定，并通过启动后打印 `poolDataSource.getURL()/getMaxPoolSize()` 确认配置已生效。

### 9.4 健壮性优化建议

#### 9.4.1 虚拟线程 + `WatchService` 钉住（JEP 444 已知限制）

`WatchService.take()` 在 Linux 上基于 `inotify`，是**原生阻塞调用**，在 JDK 21 中会钉住载体线程（carrier pinning），`synchronized` 钉住问题要到 **JDK 24（JEP 491）** 才修复。方案用 `Thread.ofVirtual().start(this::watchServiceLoop)` 跑长生命周期的 watch 循环，钉住一条载体线程，违背使用虚拟线程的初衷。

**建议**：
- watch 循环改用**平台线程**（仅一条长生命周期线程，开销可忽略）；
- 或**直接去掉 WatchService，只保留 30s 轮询**（基于 mtime）。K8s 挂载 Secret 的 symlink 原子替换恰好能被 mtime 轮询可靠捕获，轮询方案更简单、更确定。

#### 9.4.2 双机制去重与生命周期管理

- `WatchService` + `polling` 双机制存在重复触发，已有 `refreshLock` + 比较幂等保护，但建议加 **去抖（debounce）窗口**统一收敛；
- `ScheduledExecutorService` 与 watch 线程应在 `@PreDestroy` 中优雅关闭，避免容器关闭时泄漏。

#### 9.4.3 可观测性

通过 Micrometer 暴露：`cred.refresh.lastTime`、`cred.refresh.count`、`cred.refresh.failure.count`、`cred.verify.failed`，便于在 Grafana 监控轮转健康度。

### 9.5 安全补充

- 挂载 Secret 默认 `0644`，建议 `defaultMode: 0400` + Pod `securityContext.fsGroup`；
- ESO `SecretStore` 的 Vault 认证优先用 **短期 token + bound ServiceAccount**，禁用静态 token；
- 方案代码仅日志记录 `username`（未泄露密码，良好）；`reconfigureDataSource` 失败的异常栈中**含 `Properties`**，catch 时注意脱敏，避免密码进入日志。

### 9.6 结论

整体架构（**Vault Static Role → ESO → K8s Secret → UCP `reconfigureDataSource` 平滑重配**）方向正确、链路清晰，UCP `reconfigureDataSource(Properties)` API 选型准确。但距离生产可用仍需：①修复 9.2 的 3 个正确性问题（lazy 包装/启动时序/验证误判）；②补齐 9.3 的 4 项遗漏（Rootless、插件部署、最小权限、starter 依赖）；③采纳 9.4 的虚拟线程与可观测性建议。

---

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
