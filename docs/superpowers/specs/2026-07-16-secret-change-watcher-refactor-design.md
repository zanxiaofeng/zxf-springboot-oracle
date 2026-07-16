# SecretChangeWatcher 拆分设计

**日期：** 2026-07-16
**状态：** 待实施
**范围：** `zxf.logging.springboot.cred` 包内 `SecretChangeWatcher` 重构

---

## 1. 背景与动机

`SecretChangeWatcher` 当前承担过多职责（共 5 项）：

1. WatchService 事件监听（平台线程）
2. mtime 轮询兜底（`ScheduledExecutorService` 周期任务）
3. 去抖合并（800ms 窗口）
4. mtime 状态追踪（`lastSeenMtime` 基线）
5. 线程 / 调度器生命周期（手写 `start()` + `@PreDestroy`）

手写的 `ScheduledExecutorService`、平台线程、`@PreDestroy` 让生命周期脱离 Spring 容器管理。本设计在拆分职责的同时，将调度与生命周期交给 Spring 框架托管。

## 2. 决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 驱动目标 | 用框架托管 | 让 Spring 容器管理调度与生命周期 |
| 检测机制 | 只保留 WatchService 事件 | 删除轮询兜底与 mtime 追踪，换取简洁 |
| 通知方式 | Spring 应用事件 | 用 `ApplicationEventPublisher` + `@EventListener` 解耦，取代 `Runnable` 回调 |
| 拆分边界 | 抽出 `Debouncer` | 去抖是最独立可复用的关注点，单独成类 |

### 2.1 已知权衡（明确接受）

- **失去轮询兜底**：原注释说明轮询用于应对 K8s 下 kubelet 同步延迟导致的 WatchService 漏事件。删除后，若发生漏触发，需等到下一次真正写文件才会刷新。业务方已确认接受。
- **WatchService loop 自身失败不可自愈**：`watchServiceLoop()` 抛 `IOException` 退出后，不再有兜底检测，需重启进程恢复。这是删除轮询的直接后果。

## 3. 目标组件

### 3.1 `Debouncer`（新增）

纯机制类，封装"延迟 + 取消上一次"的去抖逻辑。不感知业务语义。

- **类型**：普通 `class`，**非** Spring Bean。由 `SecretChangeWatcher` 在构造函数中 `new` 出并独占持有，避免"有状态单例"被多消费者共享的隐患。
- **依赖**：`TaskScheduler`（通过 `SecretChangeWatcher` 构造注入后传入）。
- **状态**：`volatile ScheduledFuture<?> pending`。
- **方法**：
  ```java
  public void trigger(Runnable action) {
      if (pending != null) {
          pending.cancel(false);   // mayInterruptIfRunning=false
      }
      pending = scheduler.schedule(action, Instant.now().plusMillis(debounceMs));
  }
  ```
- **线程安全**：`trigger` 实际只由 watcher 单一平台线程调用（WatchKey 事件串行处理），`volatile` 用于保证 stop 后的可见性。无需额外加锁。
- **构造参数**：`TaskScheduler scheduler, long debounceMs`。

### 3.2 `SecretChangedEvent`（新增）

纯信号事件，无 payload。Spring 4+ 允许发布任意对象作为事件，无需继承 `ApplicationEvent`。

```java
public record SecretChangedEvent() {}
```

### 3.3 `SecretChangeWatcher`（重构）

实现 `SmartLifecycle`，职责收敛为：**WatchService 事件检测 + 容器生命周期 + 发布事件**。

- **构造注入**：`CredentialFileSource`、`TaskScheduler`、`ApplicationEventPublisher`。
- **内部持有**：
  - `WatchService watchService`
  - 未启动的平台线程 `watcher`（`Thread.ofPlatform().name("secret-watcher").unstarted(this::watchServiceLoop)`）
  - `Debouncer debouncer`
- **常量**：`DEBOUNCE_MS = 800`（轮询相关常量删除）。

- **`SmartLifecycle.start()`**：
  1. `if (!credSource.isAvailable()) return;` —— 本地 dev 无凭据目录时优雅降级，避免 `WatchService.register` 抛异常。
  2. 注册目录到 `WatchService`，监听 `ENTRY_MODIFY`、`ENTRY_CREATE`。
  3. `watcher.start();`

- **`watchServiceLoop()`**（平台线程，阻塞）：
  - `while ((key = watchService.take()) != null)` 循环。
  - 命中 `username` / `password` / `..data` → `debouncer.trigger(this::publishChanged)`。
  - `catch (ClosedWatchServiceException)` → `log.info` 正常退出。
  - `catch (IOException)` → `log.error` 并退出 loop（无兜底，见 §2.1）。
  - `catch (InterruptedException)` → 恢复中断标志并退出。

- **`publishChanged()`**：`publisher.publishEvent(new SecretChangedEvent());`

- **`SmartLifecycle.stop()`**（采用无参签名，简化）：
  - `watchService.close();` —— 触发 `take()` 抛 `ClosedWatchServiceException`，`watcher` 线程自然退出。
  - watcher 线程退出是异步的，`stop()` 不阻塞等待其结束（线程退出仅是一次 `take()` 抛异常 + 方法返回，耗时可忽略）。

- **`isAutoStartup()`**：返回 `true`（默认）。
- **`getPhase()`**：返回 `SmartLifecycle.DEFAULT_PHASE`（不强制排序；与 `DynamicCredentialRefresher` 的 `ApplicationReadyEvent` 监听无先后依赖，见 §4.1）。

**删除项**：`ScheduledExecutorService scheduler` 字段、`pollForChanges()`、`lastSeenMtime` 字段、`advanceLastSeenMtime()`、`start(Runnable callback)` 旧签名、`@PreDestroy shutdown()`、`POLL_INTERVAL_SECONDS` 常量、`Executors` / `ScheduledExecutorService` / `TimeUnit` 等 import。

### 3.4 `DynamicCredentialRefresher`（微调）

- **`@EventListener(ApplicationReadyEvent.class) start()`**：
  - 保留 `isAvailable()` 检查与跳过逻辑。
  - **删除** `watcher.start(this::refresh);` 这一行（watcher 现由容器以 `SmartLifecycle` 启动）。
  - 保留 `refresh();` 启动对齐一次。
- **新增监听**：
  ```java
  @EventListener(SecretChangedEvent.class)
  public void onSecretChanged() {
      refresh();
  }
  ```
- `refresh()` 不变（`apply(credSource.read())`，内部 try/catch 已存在）。

## 4. 数据流

```
文件变化
  → WatchService.take() 返回事件（平台线程）
  → 命中相关文件名
  → debouncer.trigger(this::publishChanged)        // 取消上一次，800ms 后执行
  → publishChanged()
  → ApplicationEventPublisher.publishEvent(SecretChangedEvent)

  → @EventListener onSecretChanged() [DynamicCredentialRefresher]
  → refresh()
  → applier.apply(credSource.read())
```

### 4.1 启动时序

- 容器 refresh 阶段：`SecretChangeWatcher`（`SmartLifecycle`）的 `start()` 被调用 → 注册 WatchService、启动 watcher 线程。
- `ApplicationReadyEvent`：`DynamicCredentialRefresher.start()` 被调用 → `refresh()` 对齐一次。
- 若 watcher 在 `ApplicationReadyEvent` 之前就检测到变化并发布事件，`onSecretChanged` 监听器此时已注册（同处 refresh 阶段完成绑定），会触发一次额外 `refresh` —— 幂等无副作用。

## 5. 错误处理

| 场景 | 处理 |
|------|------|
| 本地 dev 无凭据目录 | `SmartLifecycle.start()` 内 `isAvailable()` 判空，直接 return，不注册 WatchService |
| `WatchService.register` 失败 | `log.error`，watcher 线程退出，无兜底检测（见 §2.1） |
| `take()` 被中断 | 恢复中断标志，`log.info`，退出 loop |
| 容器关闭 | `stop()` 关闭 WatchService，线程经 `ClosedWatchServiceException` 退出 |
| `refresh()` 内部异常 | `DynamicCredentialRefresher.refresh()` 现有 try/catch 兜底，不影响 watcher |
| 去抖任务发布事件异常 | `publishEvent` 本身不抛；即便抛出也不影响 WatchService loop |

## 6. 测试

### 6.1 `DebouncerTest`

- 注入 mock `TaskScheduler`。
- 验证：连续两次 `trigger`，第一次 schedule 的 `ScheduledFuture` 被 `cancel(false)`，第二次重新 schedule。
- 验证：首次 `trigger`（`pending == null`）不抛 NPE。

### 6.2 `SecretChangeWatcherTest`

- 使用 JUnit 5 临时目录（`@TempDir`）+ 真实 `WatchService`，或注入 fake `CredentialFileSource`。
- 验证：写入 / 修改 `username` / `password` 文件后，发布 `SecretChangedEvent`（用 `ApplicationEventPublisher` 的捕获式 mock 或 spy）。
- 验证：修改无关文件（如 `other`）不发布事件。
- 验证：`stop()` 关闭后 watcher 线程退出，无残留。
- 验证：`credSource.isAvailable() == false` 时 `start()` 不注册、不抛异常。
- 去抖窗口内连续多次文件改动只发布一次事件（注意跨平台 WatchService 时序，必要时放宽超时）。

### 6.3 `DynamicCredentialRefresherTest`

- 验证：收到 `SecretChangedEvent` 后调用 `refresh`（mock `UcpCredentialApplier` + `CredentialFileSource`）。
- 验证：`isAvailable() == false` 时 `start()` 不触发 `refresh`。

## 7. 配置

- Spring Boot 自动配置 `ThreadPoolTaskScheduler` bean，`SecretChangeWatcher` 直接注入 `TaskScheduler`。
- 建议确认 `application.yml` 启用 `spring.threads.virtual.enabled=true`，使调度任务走虚拟线程。
- `DEBOUNCE_MS = 800` 保持为类内私有常量；如未来需外部化，再改为 `@Value` 注入。

## 8. 变更清单

| 文件 | 操作 |
|------|------|
| `Debouncer.java` | 新增 |
| `SecretChangedEvent.java` | 新增 |
| `SecretChangeWatcher.java` | 重构：实现 `SmartLifecycle`，删除轮询/mtime/回调/手写调度器 |
| `DynamicCredentialRefresher.java` | 删除 `watcher.start(...)`，新增 `@EventListener(SecretChangedEvent.class)` |
| 测试类（3 个） | 新增 |

## 9. 非目标（YAGNI）

- 不引入可配置的轮询开关（轮询已彻底删除）。
- 不把 `Debouncer` 做成通用 Bean（当前仅一处使用）。
- 不为 `SecretChangedEvent` 增加 payload（纯信号即可）。
- 不调整 `CredentialFileSource` / `UcpCredentialApplier` / `DatabaseHealthIndicator`。
