# SecretChangeWatcher 拆分实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `SecretChangeWatcher` 拆分为 `Debouncer` + 重构后的 `SecretChangeWatcher`，改用 Spring `SmartLifecycle` 托管生命周期、`ApplicationEventPublisher` 发布事件，删除轮询兜底与 mtime 追踪。

**Architecture:** `SecretChangeWatcher`（`SmartLifecycle`）持有平台线程跑 `WatchService`，命中相关文件事件后委托 `Debouncer`（依赖 `TaskScheduler`）去抖，去抖触发时发布 `SecretChangedEvent`；`DynamicCredentialRefresher` 用 `@EventListener` 接收事件并刷新凭据。

**Tech Stack:** Java 21、Spring Boot 4.1.0、JUnit 5、Mockito、AssertJ、Awaitility（均由 `spring-boot-starter-test` 提供）。

**构建命令（项目无 javac 的 JRE 不能编译，必须用此 JDK）：**
```
JAVA_HOME=/home/davis/.jdks/ms-21.0.10 mvn -q clean package
```
所有 `mvn` 命令都带 `JAVA_HOME=/home/davis/.jdks/ms-21.0.10` 前缀。

---

## File Structure

| 文件 | 操作 | 职责 |
|------|------|------|
| `pom.xml` | 修改 | 新增 `spring-boot-starter-test`（test scope） |
| `src/main/resources/application.yml` | 修改 | 启用 `spring.threads.virtual.enabled: true` |
| `src/main/java/.../cred/SecretChangedEvent.java` | 新增 | 纯信号事件 record |
| `src/main/java/.../cred/Debouncer.java` | 新增 | 去抖：取消上次延迟任务并重新调度 |
| `src/main/java/.../cred/SecretChangeWatcher.java` | 重构 | `SmartLifecycle` + WatchService + 发布事件 |
| `src/main/java/.../cred/DynamicCredentialRefresher.java` | 修改 | 删除 watcher 依赖，加 `@EventListener(SecretChangedEvent.class)` |
| `src/test/java/.../cred/DebouncerTest.java` | 新增 | Debouncer 单测 |
| `src/test/java/.../cred/SecretChangeWatcherTest.java` | 新增 | isRelevant 过滤单测 + 发布事件集成测试 |
| `src/test/java/.../cred/DynamicCredentialRefresherTest.java` | 新增 | 事件监听单测 |

包名一律 `zxf.logging.springboot.cred`。

---

### Task 1: 添加测试基础设施

**Files:**
- Modify: `pom.xml`（`<dependencies>` 块内追加）

- [ ] **Step 1: 在 `pom.xml` 的 `<dependencies>` 中追加测试依赖**

在 `lombok` 依赖之后追加：

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 2: 验证依赖可解析 + 测试阶段可运行（应报 0 测试通过）**

Run:
```
JAVA_HOME=/home/davis/.jdks/ms-21.0.10 mvn -q -DskipTests=false test
```
Expected: 构建成功，`Tests run: 0`（尚无测试），无编译错误。

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "build(cred): add spring-boot-starter-test for upcoming unit tests

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 2: 新增 `SecretChangedEvent`

**Files:**
- Create: `src/main/java/zxf/logging/springboot/cred/SecretChangedEvent.java`

- [ ] **Step 1: 创建纯信号事件 record**

```java
package zxf.logging.springboot.cred;

/**
 * 凭据文件变化信号事件。无 payload——仅表示「重新读取并应用凭据」。
 * Spring 4+ 允许发布任意对象作为事件，无需继承 ApplicationEvent。
 */
public record SecretChangedEvent() {
}
```

- [ ] **Step 2: 验证编译**

Run:
```
JAVA_HOME=/home/davis/.jdks/ms-21.0.10 mvn -q compile
```
Expected: BUILD SUCCESS。

- [ ] **Step 3: Commit**

```bash
git add src/main/java/zxf/logging/springboot/cred/SecretChangedEvent.java
git commit -m "feat(cred): add SecretChangedEvent signal record

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 3: 新增 `Debouncer`（TDD）

**Files:**
- Create: `src/main/java/zxf/logging/springboot/cred/Debouncer.java`
- Test: `src/test/java/zxf/logging/springboot/cred/DebouncerTest.java`

- [ ] **Step 1: 写失败测试**

创建 `src/test/java/zxf/logging/springboot/cred/DebouncerTest.java`：

```java
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
```

- [ ] **Step 2: 运行测试验证失败**

Run:
```
JAVA_HOME=/home/davis/.jdks/ms-21.0.10 mvn -q test -Dtest=DebouncerTest
```
Expected: 编译失败，`cannot find symbol class Debouncer`。

- [ ] **Step 3: 写最小实现**

创建 `src/main/java/zxf/logging/springboot/cred/Debouncer.java`：

```java
package zxf.logging.springboot.cred;

import org.springframework.scheduling.TaskScheduler;

import java.time.Instant;
import java.util.concurrent.ScheduledFuture;

/**
 * 去抖器：每次 trigger 取消上一次的延迟任务，重新排一个 debounceMs 后执行。
 * 纯机制类，不感知业务语义。由 SecretChangeWatcher 独占持有（非 Bean，避免有状态单例）。
 */
public class Debouncer {

    private final TaskScheduler scheduler;
    private final long debounceMs;
    private volatile ScheduledFuture<?> pending;

    public Debouncer(TaskScheduler scheduler, long debounceMs) {
        this.scheduler = scheduler;
        this.debounceMs = debounceMs;
    }

    /**
     * 取消上一次待执行的延迟任务，重新调度 action 在 debounceMs 后执行。
     * 仅由 watcher 单一平台线程调用，volatile 保证 stop 后的可见性。
     */
    public void trigger(Runnable action) {
        if (pending != null) {
            pending.cancel(false);
        }
        pending = scheduler.schedule(action, Instant.now().plusMillis(debounceMs));
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

Run:
```
JAVA_HOME=/home/davis/.jdks/ms-21.0.10 mvn -q test -Dtest=DebouncerTest
```
Expected: BUILD SUCCESS，3 个测试全过。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/zxf/logging/springboot/cred/Debouncer.java src/test/java/zxf/logging/springboot/cred/DebouncerTest.java
git commit -m "feat(cred): add Debouncer for collapsing burst events

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 4: 重构 `SecretChangeWatcher` 为 `SmartLifecycle` + 同步更新 refresher 调用点

> **为何同步改 refresher**：重构会删除 watcher 的 `start(Runnable)` 旧签名，`DynamicCredentialRefresher` 当前调用它。两个文件必须在同一 commit 改完才能保持构建绿色。

**Files:**
- Modify: `src/main/java/zxf/logging/springboot/cred/SecretChangeWatcher.java`（整体重写）
- Modify: `src/main/java/zxf/logging/springboot/cred/DynamicCredentialRefresher.java`（仅删除 `watcher.start(this::refresh)` 一行 + 删 `watcher` 字段，事件监听留到 Task 5）
- Test: `src/test/java/zxf/logging/springboot/cred/SecretChangeWatcherTest.java`

- [ ] **Step 1: 写失败测试（isRelevant 过滤 + 不可用降级 + 发布事件集成）**

创建 `src/test/java/zxf/logging/springboot/cred/SecretChangeWatcherTest.java`：

```java
package zxf.logging.springboot.cred;

import org.awaitility.Awaitility;
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

    /** 过滤逻辑应只对凭据相关文件名敏感——纯函数，无 WatchService 时序依赖 */
    @Test
    void isRelevant_matches_credential_files() {
        assertThat(SecretChangeWatcher.isRelevant("username")).isTrue();
        assertThat(SecretChangeWatcher.isRelevant("password")).isTrue();
        assertThat(SecretChangeWatcher.isRelevant("..data")).isTrue();
        assertThat(SecretChangeWatcher.isRelevant("username.bak")).isTrue();   // contains "username"
        assertThat(SecretChangeWatcher.isRelevant("other")).isFalse();
        assertThat(SecretChangeWatcher.isRelevant("readme")).isFalse();
    }

    /** 端到端：真实目录 + 真实 WatchService，写文件后应在去抖窗口后收到一次事件 */
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

        // 改动 password 文件
        Files.writeString(credDir.resolve("password"), "p2");

        // 去抖 800ms 后应发布一次事件（Awaitility 最多等 5s，容忍 WatchService 调度延迟）
        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(published).hasSize(1));
        assertThat(published.get(0)).isInstanceOf(SecretChangedEvent.class);

        watcher.stop();
    }

    /** 本地 dev：无凭据目录时 start() 不抛异常、不注册 */
    @Test
    void start_skips_when_source_unavailable() {
        // 指向不存在的目录
        CredentialFileSource source = new CredentialFileSource(tempDir.resolve("nope").toString());
        List<Object> published = new ArrayList<>();
        SecretChangeWatcher watcher =
                new SecretChangeWatcher(source, newScheduler(), published::add);

        watcher.start();   // 不应抛异常

        assertThat(published).isEmpty();
    }

    private TaskScheduler newScheduler() {
        ThreadPoolTaskScheduler tps = new ThreadPoolTaskScheduler();
        tps.setPoolSize(1);
        tps.afterPropertiesSet();
        return tps;
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run:
```
JAVA_HOME=/home/davis/.jdks/ms-21.0.10 mvn -q test -Dtest=SecretChangeWatcherTest
```
Expected: 编译失败（`isRelevant` 不存在、`new SecretChangeWatcher(...)` 签名不匹配、无 `stop()`）。

- [ ] **Step 3: 重写 `SecretChangeWatcher.java`**

完整替换 `src/main/java/zxf/logging/springboot/cred/SecretChangeWatcher.java`：

```java
package zxf.logging.springboot.cred;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 变化监听器：WatchService（平台线程）检测凭据目录变化，去抖后发布 SecretChangedEvent。
 * 职责单一——只回答「何时通知变化」，不关心「如何应用」。
 * 生命周期由 Spring 容器以 SmartLifecycle 托管；去抖委托给 Debouncer（走 TaskScheduler）。
 */
@Slf4j
@Component
public class SecretChangeWatcher implements SmartLifecycle {

    /** 去抖窗口：K8s symlink 原子替换会在极短时间内触发多次事件，合并为一次回调 */
    private static final long DEBOUNCE_MS = 800;

    private final CredentialFileSource credSource;
    private final ApplicationEventPublisher publisher;
    /** watch 循环用平台线程：WatchService.take() 原生阻塞，JDK21 钉住载体（JEP444/491） */
    private final WatchService watchService;
    private final Thread watcher;
    private final Debouncer debouncer;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public SecretChangeWatcher(CredentialFileSource credSource,
                               TaskScheduler taskScheduler,
                               ApplicationEventPublisher publisher) throws IOException {
        this.credSource = credSource;
        this.publisher = publisher;
        this.watchService = FileSystems.getDefault().newWatchService();
        this.watcher = Thread.ofPlatform().name("secret-watcher").unstarted(this::watchServiceLoop);
        this.debouncer = new Debouncer(taskScheduler, DEBOUNCE_MS);
    }

    /** 由容器在 refresh 阶段自动调用 start() */
    @Override
    public boolean isAutoStartup() {
        return true;
    }

    /** 命中规则：包名含 username/password，或为 K8s symlink 原子替换标记 ..data */
    static boolean isRelevant(String name) {
        return name.contains("username") || name.contains("password") || name.equals("..data");
    }

    @Override
    public void start() {
        // 本地 dev：无挂载的凭据目录，跳过监听（沿用 application.yml 凭据）
        if (!credSource.isAvailable()) {
            log.info("Credential dir not available, SecretChangeWatcher stays idle");
            return;
        }
        running.set(true);
        try {
            credSource.getDir().register(watchService,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_CREATE);
        } catch (IOException e) {
            running.set(false);
            log.error("WatchService register failed, change detection disabled until restart", e);
            return;
        }
        watcher.start();
        log.info("Secret change watcher started. dir={}, debounce={}ms", credSource.getDir(), DEBOUNCE_MS);
    }

    @Override
    public void stop() {
        running.set(false);
        try {
            watchService.close();   // 触发 take() 抛 ClosedWatchServiceException，watcher 自然退出
        } catch (IOException ignored) {
            // 忽略关闭异常
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    /** 平台线程：监听目录以适配 K8s Secret symlink 原子替换（..data → ..<timestamp>） */
    private void watchServiceLoop() {
        try {
            WatchKey key;
            while ((key = watchService.take()) != null) {
                boolean relevant = key.pollEvents().stream()
                        .map(e -> ((Path) e.context()).toString())
                        .anyMatch(SecretChangeWatcher::isRelevant);
                if (relevant) {
                    debouncer.trigger(this::publishChanged);   // 去抖：合并短时多次事件
                }
                key.reset();
            }
        } catch (ClosedWatchServiceException e) {
            log.info("WatchService closed gracefully");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("Watcher interrupted");
        }
    }

    private void publishChanged() {
        publisher.publishEvent(new SecretChangedEvent());
    }
}
```

- [ ] **Step 4: 同步更新 `DynamicCredentialRefresher.java`（仅删除 watcher 调用与依赖，事件监听留到 Task 5）**

替换 `src/main/java/zxf/logging/springboot/cred/DynamicCredentialRefresher.java` 为：

```java
package zxf.logging.springboot.cred;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * 编排器：把「变化检测」与「凭据应用」解耦，自身只剩读取 + 委派。
 * 启动顺序：SecretChangeWatcher 由 SmartLifecycle 自动启动 → 这里在 ready 时对齐一次。
 * 本地 dev（无凭据目录）时优雅降级，跳过热刷新，沿用 application.yml 凭据。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DynamicCredentialRefresher {
    private final CredentialFileSource credSource;
    private final UcpCredentialApplier applier;

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        // 本地 dev：无挂载的凭据目录，跳过热刷新，沿用 datasource creds from config
        if (!credSource.isAvailable()) {
            log.info("Credential dir not available, skipping hot-reload; using datasource creds from config");
            return;
        }
        refresh();   // 启动即对齐一次（凭据已由 ContextInitializer 注入）
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

> 注意：此处移除了 `watcher` 字段与 `watcher.start(this::refresh)`。事件监听在 Task 5 添加。

- [ ] **Step 5: 运行测试验证通过**

Run:
```
JAVA_HOME=/home/davis/.jdks/ms-21.0.10 mvn -q test -Dtest=SecretChangeWatcherTest
```
Expected: BUILD SUCCESS，3 个测试全过（`isRelevant_matches_credential_files`、`publishes_event_when_credential_file_changes`、`start_skips_when_source_unavailable`）。

- [ ] **Step 6: 跑全量构建确认无破坏**

Run:
```
JAVA_HOME=/home/davis/.jdks/ms-21.0.10 mvn -q clean package
```
Expected: BUILD SUCCESS（main 编译通过——此时 refresher 已不引用 watcher）。

- [ ] **Step 7: Commit**

```bash
git add src/main/java/zxf/logging/springboot/cred/SecretChangeWatcher.java \
        src/main/java/zxf/logging/springboot/cred/DynamicCredentialRefresher.java \
        src/test/java/zxf/logging/springboot/cred/SecretChangeWatcherTest.java
git commit -m "refactor(cred): SecretChangeWatcher to SmartLifecycle + Debouncer

- Drop ScheduledExecutorService, polling fallback, mtime tracking, Runnable callback
- WatchService loop on platform thread, debounce via TaskScheduler-backed Debouncer
- Publish SecretChangedEvent instead of invoking callback
- DynamicCredentialRefresher: drop watcher dep (event listener added next)

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 5: `DynamicCredentialRefresher` 监听 `SecretChangedEvent`（TDD）

**Files:**
- Modify: `src/main/java/zxf/logging/springboot/cred/DynamicCredentialRefresher.java`
- Test: `src/test/java/zxf/logging/springboot/cred/DynamicCredentialRefresherTest.java`

- [ ] **Step 1: 写失败测试**

创建 `src/test/java/zxf/logging/springboot/cred/DynamicCredentialRefresherTest.java`：

```java
package zxf.logging.springboot.cred;

import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DynamicCredentialRefresherTest {

    @Test
    void onSecretChanged_reads_and_applies_credentials() throws Exception {
        CredentialFileSource source = mock(CredentialFileSource.class);
        UcpCredentialApplier applier = mock(UcpCredentialApplier.class);
        DbCredentials creds = new DbCredentials("u", "p");
        when(source.read()).thenReturn(creds);

        DynamicCredentialRefresher refresher = new DynamicCredentialRefresher(source, applier);

        refresher.onSecretChanged();

        verify(applier, times(1)).apply(creds);
    }

    @Test
    void onSecretChanged_swallows_read_exception_without_propagating() throws Exception {
        CredentialFileSource source = mock(CredentialFileSource.class);
        UcpCredentialApplier applier = mock(UcpCredentialApplier.class);
        when(source.read()).thenThrow(new java.io.IOException("boom"));

        DynamicCredentialRefresher refresher = new DynamicCredentialRefresher(source, applier);

        // 不应抛出
        refresher.onSecretChanged();

        verify(applier, never()).apply(any());
    }

    @Test
    void start_skips_refresh_when_source_unavailable() throws Exception {
        CredentialFileSource source = mock(CredentialFileSource.class);
        UcpCredentialApplier applier = mock(UcpCredentialApplier.class);
        when(source.isAvailable()).thenReturn(false);

        DynamicCredentialRefresher refresher = new DynamicCredentialRefresher(source, applier);

        refresher.start();   // 模拟 ApplicationReadyEvent 触发

        verify(source, never()).read();
        verify(applier, never()).apply(any());
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run:
```
JAVA_HOME=/home/davis/.jdks/ms-21.0.10 mvn -q test -Dtest=DynamicCredentialRefresherTest
```
Expected: 编译失败，`method onSecretChanged() not found`（Task 4 的 refresher 还没有事件监听方法）。

- [ ] **Step 3: 给 `DynamicCredentialRefresher` 加事件监听方法**

在 `DynamicCredentialRefresher.java` 的 `start()` 方法之后、`refresh()` 之前插入：

```java
    @org.springframework.context.event.EventListener(SecretChangedEvent.class)
    public void onSecretChanged() {
        refresh();
    }
```

完整 import 形式（避免在文件顶部再加 import 行出错）使用全限定名 `org.springframework.context.event.EventListener`。若倾向顶部 import，则改为在文件 import 区加 `import org.springframework.context.event.EventListener;` 并在注解处用 `@EventListener(SecretChangedEvent.class)`。

最终 `DynamicCredentialRefresher.java` 完整内容：

```java
package zxf.logging.springboot.cred;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * 编排器：把「变化检测」与「凭据应用」解耦，自身只剩读取 + 委派。
 * 启动顺序：SecretChangeWatcher 由 SmartLifecycle 自动启动 → 这里在 ready 时对齐一次。
 * 凭据变化由 SecretChangedEvent 触发。
 * 本地 dev（无凭据目录）时优雅降级，跳过热刷新，沿用 application.yml 凭据。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DynamicCredentialRefresher {
    private final CredentialFileSource credSource;
    private final UcpCredentialApplier applier;

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        // 本地 dev：无挂载的凭据目录，跳过热刷新，沿用 datasource creds from config
        if (!credSource.isAvailable()) {
            log.info("Credential dir not available, skipping hot-reload; using datasource creds from config");
            return;
        }
        refresh();   // 启动即对齐一次（凭据已由 ContextInitializer 注入）
        log.info("Dynamic credential refresher started");
    }

    @EventListener(SecretChangedEvent.class)
    public void onSecretChanged() {
        refresh();
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

- [ ] **Step 4: 运行测试验证通过**

Run:
```
JAVA_HOME=/home/davis/.jdks/ms-21.0.10 mvn -q test -Dtest=DynamicCredentialRefresherTest
```
Expected: BUILD SUCCESS，2 个测试全过。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/zxf/logging/springboot/cred/DynamicCredentialRefresher.java \
        src/test/java/zxf/logging/springboot/cred/DynamicCredentialRefresherTest.java
git commit -m "feat(cred): refresher listens for SecretChangedEvent

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 6: 启用虚拟线程 + 全量验证

**Files:**
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: 在 `application.yml` 顶层 `spring:` 下启用虚拟线程**

在 `spring.datasource` 同级（`spring:` 下）追加 `threads` 块。修改后的 `spring:` 起始部分：

```yaml
spring:
  threads:
    virtual:
      enabled: true
  datasource:
    url: jdbc:oracle:thin:@//localhost:1521/FREE
    # ...其余不变
```

即在第 1 行 `spring:` 之后、第 2 行 `  datasource:` 之前插入 3 行。

- [ ] **Step 2: 跑全量测试 + 构建**

Run:
```
JAVA_HOME=/home/davis/.jdks/ms-21.0.10 mvn -q clean package
```
Expected: BUILD SUCCESS，全部测试通过（DebouncerTest 3 + SecretChangeWatcherTest 3 + DynamicCredentialRefresherTest 3 = 9 个）。

- [ ] **Step 3: 手动验证（需先 `docker-compose up -d` 起 Oracle）**

无需挂载真实 Vault 目录即可验证 dev 降级路径：
```
JAVA_HOME=/home/davis/.jdks/ms-21.0.10 mvn -q spring-boot:run
```
Expected 日志出现（本地无凭据目录，降级）：
```
Credential dir not available, SecretChangeWatcher stays idle
Credential dir not available, skipping hot-reload; using datasource creds from config
```
应用正常启动、不崩溃。Ctrl+C 退出。

> 完整端到端（真实凭据目录 + 文件改动 → 凭据刷新）需 K8s/Vault 环境，本计划范围内不强求；如需本地模拟，可创建 `/etc/secrets/db` 目录（需权限）写入 username/password 文件，启动后改写文件，观察日志出现 `Refreshing UCP credentials`。

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/application.yml
git commit -m "chore(cred): enable virtual threads for scheduler-backed debounce

Co-Authored-By: Claude <noreply@anthropic.com>"
```
