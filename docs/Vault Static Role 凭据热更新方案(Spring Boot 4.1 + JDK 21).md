# Spring Boot 4.1.0 + JDK 21 Vault Static Role 凭据热更新方案

## 1. 版本与环境基线

| 组件 | 版本/说明 | 备注 |
| --- | --- | --- |
| JDK | 21 (LTS) | 虚拟线程、Records |
| Spring Boot | 4.1.0 | 已迁移完成（openrewrite + properties-migrator） |
| Oracle UCP | 23.26.x（ucp17） | 支持 `reconfigureDataSource(Properties)` |
| Oracle JDBC | 23.26.x（ojdbc17） | 与 UCP 后缀保持一致（同为 17），版本由 ojdbc-bom 统一管理 |
| Vault | Enterprise 1.18+（Rootless 必需） | Rootless（`self_managed`）为 Enterprise 特性，需 Oracle ent 插件 ≥ 0.11.0+ent；OSS Vault 请使用 §2.3 管理账号方案（OSS 插件最新 0.10.2） |
| ESO | v2.x（2026-07 当前 v2.6.x） | API `external-secrets.io/v1` 稳定；Vault provider 仅支持 KV 引擎，database 引擎须用 `VaultDynamicSecret` generator（§3） |
| Kubernetes | 1.29+ | 无特殊特性依赖 |

> 本方案不引入 Oracle 的 `oracle-spring-boot-starter-ucp`：`spring.datasource.oracleucp.*` 前缀自 Spring Boot 2.4 起即由 `DataSourceConfiguration.OracleUcp` 原生绑定（与 hikari/tomcat/dbcp2 同级；Boot 4.x 位于 `spring-boot-jdbc` 模块），无需额外 starter。该 starter 还会连带引入 `ojdbc11` + `ucp`（无后缀 JDK11 变体），与显式声明的 `ojdbc17` + `ucp17` 形成重复类路径——这是又一排除理由。

## 2. Vault Static Role 配置（Oracle）

### 2.1 前置：插件与 Instant Client（生产部署易漏）

Vault 默认不内置 Oracle 插件，必须先注册插件并在 Vault 主机安装 Oracle Instant Client：

```bash
# 1. 注册插件到 catalog（需带 sha256）
vault write sys/plugins/catalog/database/vault-plugin-database-oracle \
  sha256="<二进制 sha256>" \
  command=vault-plugin-database-oracle

# 2. Vault 主机安装 Oracle Instant Client（libclntsh 置于 ld.so.conf 路径，ldconfig 刷新）
```

插件版本线（重要）：

- **OSS 插件**：最新 0.10.2，对应 Instant Client 19.23；**不支持 Rootless**；
- **Enterprise 插件**：首个版本 0.11.0+ent，Rootless（`self_managed`）必需；插件版本与 Instant Client 的对应关系以 ent 插件发布说明为准。

### 2.2 推荐配置：Rootless（self_managed=true）

> ⚠️ 前提：**Vault Enterprise 1.18+ 且 Oracle Enterprise 插件 ≥ 0.11.0+ent**。OSS 环境请直接使用 §2.3。

推荐采用 Rootless 配置：`database/config` 无需任何特权 root 账号，每个 static role 自带独立连接，最小权限。vault_admin 高权账号方案仅作 fallback 留在 §2.3。

```bash
vault secrets enable database

# Rootless：不传 username/password，加 self_managed=true
vault write database/config/oracle \
  plugin_name="vault-plugin-database-oracle" \
  connection_url="{{username}}/{{password}}@//oracle:1521/XEPDB1" \
  self_managed=true \
  allowed_roles="app-fixed-user"

# Static Role：Rootless 模式需带初始 password；username 为 DB 已存在的固定用户
# rotation_period 最小 5 秒、无 1h 硬下限；生产建议 ≥ 1h
vault write database/static-roles/app-fixed-user \
  db_name=oracle \
  username="APP_USER" \
  password="<初始密码>" \
  rotation_period="24h"
```

Rootless 权衡：不支持 dynamic roles（本方案不需要）；若发生带外密码修改，Vault 与 DB 会失步需手动同步。

### 2.3 管理账号方案（OSS / fallback）最小授权

若环境为 OSS Vault 或不便使用 Rootless，则用管理账号方案。Static Role 仅做密码轮转，管理账号至少需 `ALTER USER`：

```sql
-- 最小：仅密码轮转
GRANT ALTER USER TO vault_admin;
GRANT CREATE SESSION TO vault_admin;
-- 可选：revoke 时终止会话
GRANT SELECT ON gv_$session TO vault_admin;
GRANT ALTER SYSTEM TO vault_admin;   -- 仅当需要 KILL SESSION
```

动态角色才需 `CREATE USER` / `DROP USER`。禁止对 config 所用的 root/admin 账号配置 Static Role（轮转后 config 连接立即失效，全部凭据操作瘫痪）。

### 2.4 Static Role 核心行为

Vault 每 24 小时自动调用 Oracle 修改 `APP_USER` 的密码。应用通过 `database/static-creds/app-fixed-user` 获取当前有效密码，用户名始终为 `APP_USER`。

## 3. ESO 与 K8s Secret

ESO 的 Vault provider **仅支持 KV 引擎**；读取 database 引擎（含 `static-creds`）必须使用官方的 `VaultDynamicSecret` generator，不能用 SecretStore + remoteRef 直读（`version: v2` 会按 KV-v2 语义改写路径并期望 `data.data`/`metadata` 结构，database 引擎不满足）。

### 3.1 VaultDynamicSecret + ExternalSecret

```yaml
apiVersion: generators.external-secrets.io/v1alpha1
kind: VaultDynamicSecret
metadata:
  name: oracle-static-creds
  namespace: app-namespace
spec:
  # database 引擎 static-creds 读取路径（GET）
  path: "database/static-creds/app-fixed-user"
  method: GET
  resultType: Data
  provider:
    server: "http://vault.vault:8200"
    auth:
      kubernetes:
        mountPath: "kubernetes"
        role: "eso-role"
        serviceAccountRef:
          name: eso-sa
        # Vault 1.21+ 强制要求 audience；Vault role 侧需同步配置 bound_audiences
        audiences:
          - vault
---
apiVersion: external-secrets.io/v1
kind: ExternalSecret
metadata:
  name: oracle-static-creds
  namespace: app-namespace
spec:
  # ⚠️ 刷新间隔必须显著小于 rotation_period
  # 推荐 ≤ rotation_period / 4，确保在下次轮转前拿到新密码
  refreshInterval: "6h"
  target:
    name: oracle-credentials
    creationPolicy: Owner
  dataFrom:
    - sourceRef:
        generatorRef:
          apiVersion: generators.external-secrets.io/v1alpha1
          kind: VaultDynamicSecret
          name: oracle-static-creds
```

generator 自带 provider 配置，无需 SecretStore。`static-creds` 响应无 lease，ESO 按 `refreshInterval` 周期重新读取。

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
      # runAsUser/runAsGroup/fsGroup 三者一致，确保非 root 容器能读 secret 卷：
      #  - fsGroup=10001 → kubelet 把挂载文件 chgrp 为 10001，并以 0440 让组可读
      #  - runAsUser=10001 → 容器以非 root 运行（否则 runAsNonRoot 会拒绝启动）
      securityContext:
        runAsNonRoot: true
        runAsUser: 10001
        runAsGroup: 10001
        fsGroup: 10001
      initContainers:
        - name: wait-for-secrets
          image: busybox:1.36
          # 带超时上限：Secret 长期未就绪时让 Pod 失败可见，而不是无限等待
          command: ['sh', '-c', 'i=0; until [ -s /etc/secrets/db/username ] && [ -s /etc/secrets/db/password ]; do i=$((i+1)); if [ $i -gt 60 ]; then echo "Timed out waiting for secrets"; exit 1; fi; echo "Waiting for secrets..."; sleep 2; done']
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
            # 0440 = 属主可读 + 组(fsGroup)可读 + others 无权限
            # 显式组读，避免依赖 kubelet 把 0400 镜像成 0440 的隐式行为
            defaultMode: 0440
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
    <!-- Boot 3→4 迁移期辅助：对旧属性名告警/临时兼容，迁移稳定后可移除 -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-properties-migrator</artifactId>
        <scope>runtime</scope>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-webmvc</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-jdbc</artifactId>
        <!-- 排除默认 HikariCP：本方案使用 UCP（spring.datasource.type 指定） -->
        <exclusions>
            <exclusion>
                <groupId>com.zaxxer</groupId>
                <artifactId>HikariCP</artifactId>
            </exclusion>
        </exclusions>
    </dependency>
    <!-- 健康检查与 actuator 端点（§6.4 依赖） -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <!-- ojdbc17 与 ucp17 后缀保持一致，版本由 ojdbc-bom 统一管理 -->
    <dependency>
        <groupId>com.oracle.database.jdbc</groupId>
        <artifactId>ojdbc17</artifactId>
    </dependency>
    <dependency>
        <groupId>com.oracle.database.jdbc</groupId>
        <artifactId>ucp17</artifactId>
    </dependency>
    <!-- 可选：ONS 仅在启用 Fast Connection Failover 时需要，可按需移除 -->
    <dependency>
        <groupId>com.oracle.database.ha</groupId>
        <artifactId>ons</artifactId>
    </dependency>
    <!-- Lombok：@Slf4j / @RequiredArgsConstructor（§6 代码依赖），编译期 provided -->
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <scope>provided</scope>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-maven-plugin</artifactId>
        </plugin>
        <!-- OpenRewrite：Boot 4 一次性迁移工具（UpgradeSpringBoot_4_0 配方），随时可移除 -->
        <plugin>
            <groupId>org.openrewrite.maven</groupId>
            <artifactId>rewrite-maven-plugin</artifactId>
            <version>6.43.0</version>
            <configuration>
                <activeRecipes>
                    <recipe>org.openrewrite.java.spring.boot4.UpgradeSpringBoot_4_0</recipe>
                </activeRecipes>
            </configuration>
            <dependencies>
                <dependency>
                    <groupId>org.openrewrite.recipe</groupId>
                    <artifactId>rewrite-spring</artifactId>
                    <version>6.34.0</version>
                </dependency>
            </dependencies>
        </plugin>
    </plugins>
</build>

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

### 5.2 application.yml

`spring.datasource.ucp.*` 不是受支持的命名空间；UCP 专有属性使用 `spring.datasource.oracleucp.*` 前缀，自 Spring Boot 2.4 起原生绑定（`DataSourceConfiguration.OracleUcp`），无需额外 starter。

启动凭据由 §6.2 的 CredentialContextInitializer 在上下文创建前以最高优先级注入 `spring.datasource.username/password`，覆盖 yml 中的 dev 凭据，确保 Flyway/JPA 启动阶段即用真实凭据。

下方为本仓库 `application.yml` 实际内容：dev 演示对接 docker-compose 的 Oracle Free（`localhost:1521/FREE`，`system/123456`）。K8s 部署时把 URL 换成 service DNS、凭据由 §6.2 注入覆盖即可；连接池容量为演示值，生产请按需调大。

```yaml
spring:
  threads:
    virtual:
      enabled: true
  datasource:
    url: jdbc:oracle:thin:@//localhost:1521/FREE   # K8s：jdbc:oracle:thin:@//oracle:1521/XEPDB1
    username: system        # dev 凭据；K8s 由 §6.2 ContextInitializer 注入覆盖
    password: 123456
    driver-class-name: oracle.jdbc.OracleDriver
    type: oracle.ucp.jdbc.PoolDataSource
    # eager（默认）：避免 LazyConnectionDataSourceProxy 包装池，凭据热刷新代码直接 unwrap 拿到 PoolDataSource
    connection-fetch: eager
    oracleucp:
      connection-factory-class-name: oracle.jdbc.pool.OracleDataSource
      connection-pool-name: pool-test
      sql-for-validate-connection: select * from dual
      fast-connection-failover-enabled: true   # 需 ons 依赖（§5.1）；未配 ONS 时仅启动告警
      validate-connection-on-borrow: true
      # 演示小池；生产按需调大（如 initial/min 2、max 20）
      initial-pool-size: 0
      min-pool-size: 0
      max-pool-size: 3
      # 23.26 起 setConnectionWaitTimeout(int) 已废弃，使用 Duration 版本
      connection-wait-duration: 15S
      connection-validate-timeout: 5
      inactive-connection-timeout: 60
      max-connection-reuse-time: 300
      abandoned-connection-timeout: 60
      time-to-live-connection-timeout: 180
      connection-properties:
        oracle.jdbc.defaultLobPrefetchSize: 4000
        oracle.net.keepAlive: true
        oracle.net.TCP_KEEPIDLE: 60

# Actuator：关闭默认 db 健康检查，使用 §6.4 自定义 dynamicDbHealth（借连 isValid 探测 + UCP 池统计）
management:
  endpoint:
    health:
      show-details: always
  health:
    db:
      enabled: false
```

## 6. Java 代码：凭据热刷

> **构造注入约定**：所有 Bean 一律用 Lombok `@RequiredArgsConstructor` 构造注入，不手写构造函数；派生值用纯函数方法获得，不保留派生状态字段。
> 为让 `@Value` 随 final 字段进入 Lombok 生成的构造参数，在 `src/main/java/lombok.config` 加入：
>
> ```config
> lombok.copyableAnnotations += org.springframework.beans.factory.annotation.Value
> ```

### 6.1 凭据载体与文件源

不可变凭据载体（record 天然线程安全；`EMPTY` 哨兵作为「尚未应用任何凭据」的初始值）：

```java
package zxf.logging.springboot.cred;

import java.util.Properties;

/**
 * 不可变凭据载体，天然线程安全。
 */
public record DbCredentials(String username, String password) {

    /** 空凭据哨兵：作为「尚未应用任何凭据」的初始值 */
    public static final DbCredentials EMPTY = new DbCredentials(null, null);

    public boolean isEmpty() {
        return username == null || username.isBlank()
            || password == null || password.isBlank();
    }

    /** 转为 reconfigureDataSource 所需的 Properties（user/password） */
    public Properties toProperties() {
        Properties properties = new Properties();
        properties.setProperty("user", username());
        properties.setProperty("password", password());
        return properties;
    }
}
```

文件源（单一职责：定位文件、判断可用性、读取凭据、完成 WatchService 注册）。无 getter——外部不询问 dir，而是**告知**它完成注册（Tell, Don't Ask）。既可作 Spring Bean 供运行期热刷注入，也可被 ContextInitializer 在上下文创建前直接 new：

```java
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
    @Value("${DB_CRED_DIR:/etc/secrets/db}")
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
```

### 6.2 启动期凭据注入（ApplicationContextInitializer）

启动时序处理：若仅在 ApplicationReadyEvent 才加载真实凭据，Flyway/Liquibase、JPA ddl-auto 等在上下文刷新阶段（更早）即借连接，会用 PLACEHOLDER 建连失败。解法：用 `ApplicationContextInitializer` 在上下文 refresh 前把 `/etc/secrets/db` 中的 username/password 以最高优先级注入 Environment，使自动装配出的 PoolDataSource 一开始就持有真实凭据。

> 选型说明：`EnvironmentPostProcessor` 在 Boot 4.x 仍是有效扩展点（4.0 起接口迁至 `org.springframework.boot` 包，spring.factories 注册 key 同步调整）。本方案选用 Spring Framework 核心的 `ApplicationContextInitializer`——时机同样早于 refresh（DataSource/Flyway/JPA 建连），API 面最小，不受 Boot 扩展点演进影响。

```java
package zxf.logging.springboot.cred;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;

import java.nio.file.Path;
import java.util.Map;

/**
 * 在上下文 refresh 前（ApplicationContextInitializer）把 K8s 挂载的 Vault Static Role 凭据
 * 注入 Environment 最高优先级，覆盖 application.yml，确保 DataSource/Flyway/JPA 启动即用真实凭据。
 * dev（无挂载文件）时回退到 application.yml。
 *
 * <p>选型说明：Boot 4.x 中 EnvironmentPostProcessor 仍是有效扩展点（4.0 起接口迁至
 * {@code org.springframework.boot} 包，spring.factories 注册 key 同步调整）。本方案选用 Spring Framework
 * 核心的 ApplicationContextInitializer——时机同样早于 refresh（DataSource/Flyway/JPA 建连），API 面最小，
 * 不受 Boot 扩展点演进影响。</p>
 */
@Slf4j
public class CredentialContextInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        ConfigurableEnvironment environment = applicationContext.getEnvironment();
        // 上下文创建前无 Bean 可注入，直接 new 文件源（与运行期共享同一读取逻辑）
        String dirPath = environment.getProperty("DB_CRED_DIR", "/etc/secrets/db");
        CredentialFileSource fileSource = new CredentialFileSource(Path.of(dirPath));
        if (!fileSource.isAvailable()) {
            log.info("Credential dir not available, skipping hot-reload; using datasource credentials from config");
            return;
        }
        inject(environment, fileSource);
    }

    /** 读取挂载凭据并以最高优先级注入 Environment；失败仅告警，交由后续建连报错暴露 */
    private void inject(ConfigurableEnvironment environment, CredentialFileSource fileSource) {
        try {
            DbCredentials credentials = fileSource.read();
            if (credentials.isEmpty()) {
                log.warn("Empty credentials, skipping injection");
                return;
            }
            MutablePropertySources propertySources = environment.getPropertySources();
            propertySources.addFirst(new MapPropertySource(
                    "vaultStaticCreds",
                    Map.of(
                            "spring.datasource.username", credentials.username(),
                            "spring.datasource.password", credentials.password())));
            log.info("Injected Vault static credentials from mounted files (user={})", credentials.username());
        } catch (Exception exception) {
            log.warn("Failed to read mounted credentials, falling back to configured properties: {}", exception.toString());
        }
    }
}
```

注册文件 `src/main/resources/META-INF/spring.factories`（Boot 4 经 SpringFactoriesLoader 加载 ApplicationContextInitializer，不是 .imports）：

```properties
org.springframework.context.ApplicationContextInitializer=zxf.logging.springboot.cred.CredentialContextInitializer
```

### 6.3 运行期热刷

事件驱动 + 单一职责拆分，Lombok 与 JDK 特性（record、虚拟线程、线程 Builder、Duration）消除样板代码：

| 类型 | 职责 | 实例字段 |
| --- | --- | --- |
| `CredentialsChangedEvent` | 变更事件载体（record），经 Spring ApplicationEvent 总线传播 | 1 |
| `SecretDirectoryWatcher` | 何时发现：WatchService 监听目录（独立 daemon 平台线程），过滤相关事件 | 3（含资源句柄） |
| `CredentialChangeNotifier` | 何时通知：委派内嵌 `Debouncer` 去抖 → 发布 Spring 事件 | 2 |
| `CredentialChangeNotifier.Debouncer` | 去抖机制：窗口内取消旧任务，仅执行最后一次（自带调度器） | 2 |
| `UcpCredentialApplier` | 如何切换：旁路验证新凭据 → unwrap → reconfigureDataSource（池未启动时 UCP-76 兜底 setter）→ refreshConnectionPool | 2 |
| `CredentialWatchBootstrap` | 启动接线：应用就绪后启动目录监听 | 2 |
| `CredentialsChangedListener` | 事件 → 读取并应用 | 2 |

设计要点：

- **零手写构造函数**：全部 Bean 经 Lombok `@RequiredArgsConstructor` 注入；`CredentialFileSource` 的 `@Value` 经 lombok.config 复制到构造参数。
- **事件驱动解耦**：检测方（watcher）与应用方（applier）通过 Spring `ApplicationEvent` 通信，互不知晓；事件监听在 Debouncer 的单线程调度器上同步执行，刷新天然串行，无需加锁。
- **单一监听机制**：K8s Secret 更新通过 `..data` symlink 原子替换完成，监听目录的 WatchService 即可稳定捕获。
- **注册前置**：目录注册在 `start()`（调用线程）完成；注册失败即终止应用启动——若降级运行（失去热刷能力），Vault 轮转后将演变为连接故障。
- **先验证后切换**：用 DriverManager 旁路建连验证新凭据（池中旧连接在 reconfigure 后仍可借出，直接借连 `isValid` 无法区分新旧凭据）；验证失败不动池，等下一轮事件。
- **先配置后回收**：`reconfigureDataSource` 仅影响新建连接，随后 `refreshConnectionPool` 回收空闲连接，使后续借连尽快使用新凭据；池未启动（`initialPoolSize=0` 懒建池）时 `reconfigureDataSource` 抛 UCP-76，兜底改用 `setUser/setPassword`，池首次启动即以新凭据建连。
- **去抖合并**：K8s 原子替换短时触发多次事件，窗口内取消旧任务、仅执行最后一次。
- unwrap 兼容 `connection-fetch=lazy` 包装，且为按次调用的纯函数（不持有派生状态）；异常仅记消息与堆栈，不把含密码的 `Properties` 作为日志参数；`@PreDestroy` 优雅关闭。
- **代码规范要点**：实例变量 ≤ 2（watcher 另持有一个由 `@PreDestroy` 管理的资源句柄字段）；无 getter/setter（文件源为 Tell 风格 `registerOn`）；无 `else`；标识符不缩写；原生类型以值对象封装（`Duration`）；每方法一层缩进（try 块不计）。applier 将验证逻辑保留为私有方法，避免因过度拆分再增字段与类。

#### 6.3.1 变更事件（CredentialsChangedEvent）

Spring 支持任意对象作为事件载荷（无需继承 ApplicationEvent），record 作为不可变事件载体：

```java
package zxf.logging.springboot.cred;

import java.time.Instant;

/**
 * 凭据变更事件：secret 卷中的凭据文件发生变化（去抖合并后发布）。
 * Spring 支持任意对象作为事件载荷（无需继承 ApplicationEvent）。
 */
public record CredentialsChangedEvent(Instant detectedAt) {
}
```

#### 6.3.2 目录监听器（SecretDirectoryWatcher）

```java
package zxf.logging.springboot.cred;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.List;

/**
 * 目录监听器：告知文件源完成 WatchService 注册，过滤出与凭据相关的文件事件后上报。
 * 职责单一——只回答「何时发生变化」，不关心「如何通知/应用」。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SecretDirectoryWatcher {

    private final CredentialFileSource fileSource;
    private final CredentialChangeNotifier notifier;

    /** WatchService 资源句柄，由 @PreDestroy 负责关闭 */
    private WatchService watchService;

    /**
     * 启动监听：注册目录并开启独立 daemon 平台线程。
     * 注册在调用线程完成，失败即时暴露给调用方。
     * 监听目录而非单文件，以适配 K8s Secret symlink 原子替换（..data → ..<timestamp>）。
     */
    public void start() throws IOException {
        watchService = FileSystems.getDefault().newWatchService();
        fileSource.registerOn(watchService);
        Thread.ofPlatform().name("secret-watcher").daemon(true).start(this::watchLoop);
        log.info("Watching credentials directory for changes");
    }

    private void watchLoop() {
        try {
            WatchKey key;
            while ((key = watchService.take()) != null) {
                dispatch(key);
            }
        } catch (ClosedWatchServiceException exception) {
            log.info("WatchService closed gracefully");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.info("Watcher interrupted");
        }
    }

    /** 单条 WatchKey 处理：过滤相关事件、复位、上报 */
    private void dispatch(WatchKey key) {
        List<WatchEvent<?>> events = key.pollEvents();
        boolean relevant = events.stream()
                .map(event -> ((Path) event.context()).toString())
                .anyMatch(SecretDirectoryWatcher::isCredentialEvent);
        key.reset();
        if (relevant) {
            notifier.signal();
        }
    }

    /** 仅关心凭据文件与 K8s ..data symlink 的变更 */
    private static boolean isCredentialEvent(String fileName) {
        return fileName.contains("username") || fileName.contains("password") || fileName.equals("..data");
    }

    @PreDestroy
    void shutdown() {
        try {
            if (watchService != null) {
                watchService.close();   // 触发 take() 抛 ClosedWatchServiceException，watch 线程自然退出
            }
        } catch (IOException exception) {
            log.warn("Error closing WatchService: {}", exception.toString());
        }
    }
}
```

#### 6.3.3 去抖与事件发布（CredentialChangeNotifier）

```java
package zxf.logging.springboot.cred;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 变更通知器：把文件系统事件委托给 Debouncer 去抖合并后，经 Spring ApplicationEvent 总线广播。
 * 职责单一——只回答「何时通知」，不关心「如何应用」。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CredentialChangeNotifier {
    private final ApplicationEventPublisher publisher;
    private final Debouncer debouncer = new Debouncer();

    /** 去抖发布：窗口内多次信号合并为一次事件 */
    public void signal() {
        debouncer.submit(() -> {
            log.debug("Publishing CredentialsChangedEvent");
            publisher.publishEvent(new CredentialsChangedEvent(Instant.now()));
        });
    }

    /** Debouncer 非 Spring 管理对象，其资源由本 Bean 的 @PreDestroy 代为释放 */
    @PreDestroy
    void shutdown() {
        debouncer.shutdown();
    }

    /**
     * 去抖器：自带单线程虚拟线程调度器，窗口内新任务取消旧任务，仅执行最后一次。
     * 事件监听在该单线程上同步执行，刷新天然串行，无需加锁。
     */
    static final class Debouncer {
        /** 去抖窗口：K8s symlink 原子替换会在极短时间内触发多次事件 */
        private static final Duration DEBOUNCE = Duration.ofMillis(800);

        private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("cred-notify-", 0).factory());
        private final AtomicReference<ScheduledFuture<?>> pending = new AtomicReference<>();

        void submit(Runnable action) {
            ScheduledFuture<?> previous = pending.getAndSet(
                    scheduler.schedule(action, DEBOUNCE.toMillis(), TimeUnit.MILLISECONDS));
            if (previous != null) {
                previous.cancel(false);
            }
        }

        void shutdown() {
            scheduler.shutdownNow();
        }
    }
}
```

#### 6.3.4 凭据应用器（UcpCredentialApplier）

```java
package zxf.logging.springboot.cred;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import oracle.ucp.admin.UniversalConnectionPoolManagerImpl;
import oracle.ucp.jdbc.PoolDataSource;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * 凭据应用器：先用旁路连接验证新凭据，再切换 UCP 连接池配置。
 * 职责单一——只回答「如何切换」，不关心「何时切换」。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UcpCredentialApplier {

    /**
     * 借连验证的超时秒数
     */
    private static final int VALIDATION_TIMEOUT_SECONDS = 3;

    private final DataSource dataSource;
    /**
     * 上次成功应用的凭据缓存；用于变更比较，避免调用已废弃的 PoolDataSource.getPassword()
     */
    private volatile DbCredentials lastApplied = DbCredentials.EMPTY;

    /**
     * 应用凭据：相同则跳过；不同则先旁路验证，再 reconfigure。
     *
     * @return true=已切换或无变化；false=验证失败（池未被改动，旧凭据继续服务，等下轮事件）
     */
    public boolean apply(DbCredentials credentials) {
        if (credentials.isEmpty()) {
            log.warn("Read empty credentials, skipping apply");
            return false;
        }
        if (credentials.equals(lastApplied)) {
            log.debug("Credentials unchanged, skip");
            return true;
        }
        return doApply(credentials);
    }

    /**
     * 验证 → 切换 → 记录；任何异常仅记消息（异常信息可能携带含密码的 Properties）
     */
    private boolean doApply(DbCredentials credentials) {
        try {
            // unwrap 是纯函数且代价可忽略，按次解出，不持有派生状态字段
            PoolDataSource pool = unwrap(dataSource);
            if (!verifyNewCredentials(pool.getURL(), credentials)) {
                // 验证失败不动池：旧连接仍可服务，等待下一轮事件
                log.error("New credentials failed out-of-band check; pool left untouched, will retry next cycle");
                return false;
            }
            log.info("Refreshing UCP credentials for user: {}", credentials.username());


            try {
                pool.reconfigureDataSource(credentials.toProperties());
            } catch (SQLException ex) {
                if (ex.getErrorCode() != 76) {
                    throw ex;
                }
                // 池未启动（initialPoolSize=0 时首次借连前 UCP 懒建池），reconfigureDataSource 会抛 UCP-76；
                // 直接用 setter 改连接工厂配置，池首次启动时即以新凭据建连
                pool.setUser(credentials.username());
                pool.setPassword(credentials.password());
                log.warn("UCP pool not started, reconfigureDataSource failed with UCP-76; using setters instead");
            }

            UniversalConnectionPoolManagerImpl.getUniversalConnectionPoolManager().refreshConnectionPool(pool.getConnectionPoolName());
            log.info("UCP credentials refreshed. borrowed={}, available={}", pool.getBorrowedConnectionsCount(), pool.getAvailableConnectionsCount());

            lastApplied = credentials;
            return true;
        } catch (Exception ex) {
            log.error("Failed to refresh UCP credentials: {}", ex, ex);
            return false;
        }
    }

    /**
     * 旁路验证：不经池，直接用 DriverManager 以新凭据建连。
     * 结论确定——不受池中旧连接（reconfigure 后仍可能借出旧会话）干扰。
     */
    private boolean verifyNewCredentials(String jdbcUrl, DbCredentials credentials) {
        try (Connection connection = DriverManager.getConnection(
                jdbcUrl, credentials.username(), credentials.password())) {
            return connection.isValid(VALIDATION_TIMEOUT_SECONDS);
        } catch (Exception exception) {
            log.warn("Out-of-band credential check failed: {}", exception.toString());
            return false;
        }
    }

    /**
     * DataSource 可能被 LazyConnectionDataSourceProxy 包装（connection-fetch=lazy），需 unwrap 到真实 PoolDataSource
     */
    private static PoolDataSource unwrap(DataSource dataSource) {
        try {
            return dataSource.isWrapperFor(PoolDataSource.class)
                    ? dataSource.unwrap(PoolDataSource.class)
                    : (PoolDataSource) dataSource;
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to unwrap UCP PoolDataSource", exception);
        }
    }
}
```

#### 6.3.5 启动接线与变更监听（Bootstrap / Listener）

启动接线与事件应用分属不同生命周期，拆为两个类：`CredentialWatchBootstrap` 监听 ApplicationReadyEvent 完成 WatchService 注册；`CredentialsChangedListener` 仅消费 CredentialsChangedEvent。当前实现不做启动对齐重读——「ContextInitializer 注入 → 监听注册」之间存在一个亚秒级窗口，落在窗口内的轮转要等下一次文件事件才会被应用。

```java
package zxf.logging.springboot.cred;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 启动接线：应用就绪后启动目录监听。
 * dev（无挂载文件）时跳过，沿用 application.yml 凭据。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CredentialWatchBootstrap {
    private final CredentialFileSource fileSource;
    private final SecretDirectoryWatcher watcher;

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        if (!fileSource.isAvailable()) {
            log.info("Credential dir not available, skipping hot-reload; using datasource credentials from config");
            return;
        }
        try {
            watcher.start();
        } catch (IOException exception) {
            // 失败即终止启动：若降级运行（失去热刷能力），Vault 轮转后将演变为连接故障
            throw new IllegalStateException("Failed to start credential watcher, hot-reload unavailable", exception);
        }
        log.info("Credential watcher started");
    }
}
```

```java
package zxf.logging.springboot.cred;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * 凭据变更监听：把「事件」翻译为「读取 + 应用」。
 * 事件监听在 Debouncer 的单线程调度器上同步执行，刷新天然串行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CredentialsChangedListener {
    private final CredentialFileSource fileSource;
    private final UcpCredentialApplier applier;

    /**
     * 凭据变更事件 → 读取并应用
     */
    @EventListener
    void onCredentialsChanged(CredentialsChangedEvent event) {
        log.debug("Credential change detected at {}", event.detectedAt());
        try {
            applier.apply(fileSource.read());
        } catch (Exception exception) {
            log.error("Failed to apply credentials: {}", exception.toString());
        }
    }
}
```

### 6.4 健康检查

`connection-fetch=lazy` 下注入的可能是 `LazyConnectionDataSourceProxy`；健康检查直接用 DataSource 即可（`isValid` 会触发真实建连），如需读 UCP 统计可 unwrap。

```java
package zxf.logging.springboot.cred;

import lombok.RequiredArgsConstructor;
import oracle.ucp.jdbc.PoolDataSource;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * 自定义 DB 健康检查，替代默认 db 指标（默认已用 management.health.db.enabled=false 关闭）。
 * 借连做 isValid 探测，UP 时 unwrap 暴露 UCP 池统计（borrowed/available）便于排障。
 */
@Component("dynamicDbHealth")
@RequiredArgsConstructor
public class DatabaseHealthIndicator implements HealthIndicator {
    /** 借连验证的超时秒数 */
    private static final int VALIDATION_TIMEOUT_SECONDS = 2;

    private final DataSource dataSource;

    @Override
    public Health health() {
        try (Connection connection = dataSource.getConnection()) {
            return probe(connection);
        } catch (Exception exception) {
            return Health.down(exception).build();
        }
    }

    /** 探测：借连无效直接 DOWN，有效则补充池明细 */
    private Health probe(Connection connection) throws SQLException {
        if (!connection.isValid(VALIDATION_TIMEOUT_SECONDS)) {
            return Health.down().withDetail("error", "Connection invalid").build();
        }
        return buildUpHealth();
    }

    /** UP 明细：可选暴露 UCP 统计便于排障 */
    private Health buildUpHealth() throws SQLException {
        Health.Builder upBuilder = Health.up().withDetail("pool", dataSource.getClass().getSimpleName());
        if (!dataSource.isWrapperFor(PoolDataSource.class)) {
            return upBuilder.build();
        }
        PoolDataSource poolDataSource = dataSource.unwrap(PoolDataSource.class);
        return upBuilder
                .withDetail("borrowed", poolDataSource.getBorrowedConnectionsCount())
                .withDetail("available", poolDataSource.getAvailableConnectionsCount())
                .build();
    }
}
```

## 7. Static Role vs Dynamic Role 关键差异速查

| 维度 | Dynamic Role | Static Role（本方案） |
| --- | --- | --- |
| Vault 读取路径 | `database/creds/<role>` | `database/static-creds/<role>` |
| 用户名 | 每次请求生成新用户名 | 固定不变 |
| 密码变更触发 | 每次请求生成新密码 | Vault 按 rotation_period 自动轮转 |
| ESO 读取方式 | VaultDynamicSecret generator | VaultDynamicSecret generator（相同） |
| ESO refreshInterval | < default_ttl（如 30m < 1h） | ≤ rotation_period / 4（如 6h < 24h） |
| 数据库用户管理 | Vault 自动 CREATE/DROP | 需预先手动创建，Vault 仅 ALTER PASSWORD |
| 审计粒度 | 每个实例独立用户名 | 所有实例共享同一用户名 |
| 适用场景 | 微服务、临时访问 | 遗留系统、用户名不可变的第三方集成 |

## 8. ⚠️ Static Role 生产注意事项

1. **禁止对 config 所用 root/admin 账号配置 Static Role**：Vault 轮转密码时不区分普通用户和管理员。若 vault_admin 被设为 Static Role，轮转后 `database/config/oracle` 中的连接凭据立即失效，导致所有凭据操作瘫痪。如需轮转 root 凭据，请使用专用 API `vault write -force database/rotate-root/oracle`。
2. **rotation_period 建议值**：最小 5 秒、无 1h 硬下限；生产建议 ≥ 1h，避免频繁轮转造成的 DB 负载与连接抖动。
3. **首次创建 Static Role 时 Vault 会立即轮转密码**：执行 `vault write database/static-roles/...` 的瞬间，数据库中该用户的密码即被修改（Enterprise 可用 `skip_import_rotation` 跳过）。请确保此时没有正在使用该用户的生产连接，或已在维护窗口内操作。
4. **ESO refreshInterval 必须留足余量**：若 rotation_period=24h，建议 refreshInterval=6h。若设置为接近 24h，可能在 Vault 轮转后、ESO 刷新前的窗口期内，应用仍持有旧密码导致连接失败。
5. **Kubernetes auth 需配置 audience**：Vault 1.20 起无 audience 的 role 产生警告，1.21+ 认证直接失败。ESO 侧 `auth.kubernetes.audiences` 与 Vault role 侧 `bound_audiences` 需同时配置。
6. **Java 代码无需区分 Static/Dynamic**：上述热刷链路（监听 → 事件 → 应用）对两种模式完全兼容。Static Role 下用户名虽不变，但密码变化仍会触发 reconfigureDataSource(Properties)，逻辑自洽。

## 关键决策参考资料

1. **Vault Static Role 机制与约束**
   - HashiCorp Vault Docs — Database Secrets Engine · Static Roles；Vault API — Static Roles
   - 支撑：Static Role 仅轮转密码而不改变用户名；`rotation_period` 最小 5 秒、无 1h 硬下限（生产建议 ≥ 1h）；首次创建时立即触发密码轮转（`skip_import_rotation` 可跳过）；`/static-creds/` 读取路径；禁止对 config 所用 root 账号配置 Static Role。
2. **Vault Oracle 插件与 Rootless**
   - HashiCorp Vault Docs — Oracle Database Secrets Engine；vault-plugin-database-oracle GitHub Releases / README
   - 支撑：Oracle 插件不随 Vault 发布，需注册到 plugin catalog 且 Vault 主机安装 Oracle Instant Client；Rootless（`self_managed=true`）为 **Enterprise 特性**（Vault Enterprise 1.18+，Oracle 插件首个 ent 版本 0.11.0+ent，OSS 最新 0.10.2 / Instant Client 19.23）；Rootless 不支持 dynamic roles；带外改密会导致 Vault 与 DB 失步。
   - Vault API — Rotate Root Credentials：root/admin 凭据轮转使用 `database/rotate-root/<name>` 专用端点。
3. **External Secrets Operator**
   - ESO Docs — HashiCorp Vault Provider
   - 支撑：官方明确 Vault provider **仅支持 KV 引擎**，其他 secrets engine 须使用 Vault Generator；kubernetes auth 在 Vault 1.21+ 必须提供 `audiences`。
   - ESO Docs — VaultDynamicSecret Generator：database 引擎（creds / static-creds）的标准读取方式；`path` + `method` + `resultType` 语义。
   - ESO GitHub Releases：2026-07 当前最新为 v2.6.x；CRD API `external-secrets.io/v1` 稳定。
4. **Spring Boot 4.x**
   - Spring Boot 4.1 Release Notes
   - 支撑：新增 `spring.datasource.connection-fetch`（eager/lazy），lazy 时以 `LazyConnectionDataSourceProxy` 包装池；4.0 废弃项已在 4.1 移除。
   - Spring Boot 4.0 Migration Guide
   - 支撑：`EnvironmentPostProcessor` 迁至 `org.springframework.boot` 包（旧包形式废弃并于 4.1 移除）；`spring.factories` 仍可注册 `ApplicationContextInitializer`。
   - Spring Boot Reference — Data Access；Oracle 官方博客（UCP with Spring Boot）
   - 支撑：`spring.datasource.type=oracle.ucp.jdbc.PoolDataSource` 可被自动装配识别；`spring.datasource.oracleucp.*` 前缀自 Boot 2.4 起由 `DataSourceConfiguration.OracleUcp` 原生绑定；`spring.datasource.ucp.*` 不受支持。
5. **Oracle UCP**
   - UCP 26ai（23.26）Javadoc — `PoolDataSource` / `PoolDataSourceImpl`
   - 支撑：`reconfigureDataSource(Properties)` 仅可修改 `user`/`password`/`description`/`serviceName`/`pdbRoles`；`getPassword()` 已废弃；`setConnectionWaitTimeout(int)` 已废弃，改用 `setConnectionWaitDuration(Duration)`；reconfigure 后池中既有连接不立即销毁，新建连接使用新凭据。
6. **Kubernetes 与 JDK**
   - Kubernetes Docs — Secrets · Mounted secrets are updated automatically
   - 支撑：挂载 Secret 更新通过 `..data` symlink 原子替换完成，WatchService 需监听目录而非单文件；传播存在 kubelet 同步周期级延迟。
   - JEP 444（Virtual Threads，JDK 21）：虚拟线程适用于本方案的去抖调度与事件分发。
