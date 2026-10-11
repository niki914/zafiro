---
name: test-hang-watchdog
description: Prevent and diagnose hanging or long-running Gradle unit tests (> 30s) in Android/Kotlin coroutines codebases. Use when running tests, when a test execution takes unexpectedly long, when background test tasks appear frozen, or when taking thread dumps (jstack) to unblock coroutine deadlocks.
---

# Test Hang Watchdog

在本项目中，针对特定类的单元测试（`testDebugUnitTest --tests "..."`）正常执行时间为 **2 ~ 8 秒**；全模块单测执行时间通常在 **15 秒以内**。

**任何单测执行超过 30 秒，100% 存在死锁、挂起或桩缺失。** 无限等待是零拦截力且严重浪费人机协同时间的行为。

---

## 1. 运行看门狗协议（主动介入，杜绝傻等）

运行单元测试命令时，必须遵循以下看门狗守则：

1. **背景任务倒计时**：
   - 若 `./gradlew testDebugUnitTest` 转入后台任务，**严禁无限期挂起等待**。
   - 任务运行达到 **15 秒** 时主动查看一次进度与输出；
   - 任务运行达到 **30 秒** 且仍在运行，**判定为异常挂起**，立即触发下文的「现场解剖 SOP」，绝不等待用户提醒。
2. **测试超时防御**：
   - 涉及挂起函数（`suspend`）、`CompletableDeferred`、`Channel`、`Flow` 收集的用例，尽量在 `runTest` 中使用 `backgroundScope` 或以 `withTimeout(2_000)` 保护，杜绝裸 `runBlocking` 无界等待。

---

## 2. 现场解剖 SOP（30 秒内定位挂死点）

一旦测试任务超过 30 秒未结束，按以下步骤定位并熔断：

### Step 1: 定位 Test Worker 进程

```bash
jps -v | grep GradleWorkerMain
```
（输出第一列即为运行当前测试用例的 JVM Worker 进程 PID，例如 `82065`）

### Step 2: 抓取线程快照

```bash
jstack <worker-pid> | head -n 45
```

### Step 3: 读取案发现场调用栈

在 `jstack` 输出的头部直接寻找 `"Test worker"` 线程（通常为 `#3`），查看第 20 ~ 30 行栈帧：
```text
"Test worker" #3 prio=5 os_prio=31 ... waiting on condition
   at jdk.internal.misc.Unsafe.park(Native Method)
   - parking to wait for <0x...> (a kotlinx.coroutines.BlockingCoroutine)
   ...
   at com.niki914.zafiro.business.agent.AgentManagerImplTest.switchTo_reusesExistingActiveAgent(AgentManagerImplTest.kt:50)
```
- **定位被测用例**：栈顶清晰指示出是哪个测试类、哪个 `@Test` 方法。
- **定位挂起原语**：
  - `LockSupport.parkNanos` / `CompletableDeferred.await`：协程 deferred 从未被 complete；
  - `awaitSettingsGateway()`：缺少依赖注入/全局桩环境；
  - `runBlockingImpl`：主线程等待子协程终态，子协程未退出。

### Step 4: 果断熔断

立即终止挂起任务与 Worker 进程，避免占用 Gradle Daemon 槽位：
```bash
manage_task(Action='kill', TaskId='<task-id>')
kill -9 <worker-pid>
```

---

## 3. 四大典型挂死病灶与速治清单

通过调用栈定位到测试方法与具体行后，对照以下四大病灶对号入座：

### 病灶 ①：自引用状态死锁（Self-Referential Job State）
- **现象**：测试在等待某组任务全部完成，例如 `jobs.values.all { it.isCompleted }`。
- **病理**：在协程体执行内部去查询自身的 Job 状态——**当前协程体未退出前，自身的 `job.isCompleted` 恒为 `false`**，形成闭环死锁。
- **解法**：解耦状态。使用独立的并发数据结构（如 `ConcurrentHashMap<Key, Result>`）显式记录各任务的结果，不要用未退出的 Job 生命周期代替语义结果。

### 病灶 ②：运行时测试桩缺失（Missing Test Doubles / Gateway）
- **现象**：栈停在 `RuntimeEnvironment.awaitSettingsGateway()` 或其他 `await*` 延迟装配门禁。
- **病理**：生产代码依赖全局环境（如 Application 启动时安装的 Bridge/Gateway），而新写的单元测试 `@Before setUp()` 中仅 mock 了局部 Store，漏掉了运行时 Gateway，导致协程无限等待 Bridge 安装。
- **解法**：在测试的 `@Before` 中安装测试专用 Stub（如 `StubRuntimeSettingsGateway`），并在 `@After tearDown()` 中显式清理。

### 病灶 ③：废弃契约反向挂起（Dead Contract Mismatch）
- **现象**：改动业务逻辑后，旧单测突然挂死在 `agent.doSomething()`。
- **病理**：业务契约已经重构（例如：后台无 Approver 时不再立即判 `Deny`，而是挂起等待前台切回）。而旧单测依然在验证「立即返回 Deny」这一废弃契约，导致其在 `runBlocking` 中合法挂起却等不到返回。
- **解法**：依照 `test-triage` 准则，**被测契约改变后，旧测试必须重写或直接删除**，严禁留着死契约。若需测试挂起等待行为，必须在独立的协程中延时提供触发事件或验证挂起状态后取消。

### 病灶 ④：无界流收集泄露（Unbounded Flow Collector）
- **现象**：测试执行完断言后依然不退出，等待协程清理。
- **病理**：测试中对冷/热 Flow 启动了无限监听（`flow.collect { }`），而未绑定测试生命周期或未在 `finally` 中 cancel。
- **解法**：
  - 使用 `kotlinx-coroutines-test` 的 `runTest` 并在 `backgroundScope.launch { ... }` 中收集流；
  - 或者使用 `take(1)` / `first()` 获取单次发射。

---

## 4. 验证与闭环标准

修复代码后，按照以下阶梯验证：

1. **单点隔离验证**：仅运行此前卡住的单一测试：
   ```bash
   ./gradlew :module:testDebugUnitTest --tests "com.package.TargetTest.targetMethod"
   ```
   **目标耗时**：5 秒以内通过。
2. **类级别回归**：
   ```bash
   ./gradlew :module:testDebugUnitTest --tests "com.package.TargetTest"
   ```
3. **全工程验证**：
   ```bash
   ./gradlew testDebugUnitTest
   ```
