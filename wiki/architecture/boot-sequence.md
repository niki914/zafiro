# Boot Sequence

本文档描述从 Xposed 入口到宿主主 Hook 安装完成的当前启动链路，以及每轮 query 进入 LLM 前的分流前置。

## 启动时序

1. **入口匹配**
   - `app/src/main/java/a0/a0/a0/a0/a0/a0/Entrance.kt` 的 `getTarget()` 返回 `Target.filter(*XValues.appList.toTypedArray())`，只监听受支持宿主包名。

2. **基础 Hook 装载**
   - `Entrance.onLoad()` 先调用 `HookSideLoader.load(scope, ContextHook(), params)` 装载 `ContextHook`。
   - 同文件里保留了 `ActivityHook` 和 `FloatWindowHook` 的注释代码，但当前未参与实际启动链。

3. **上下文与运行时桥接安装**
   - `h/src/main/java/com/niki914/nexus/h/util/ContextProvider.kt` 的 `ContextProvider.await()` 等待宿主 `Context` 就绪。
   - `app/src/main/java/com/niki914/nexus/agentic/repo/XRepo.kt` 的 `XRepo.init(ctx)` 初始化 repo 上下文。
   - `app/src/main/java/com/niki914/nexus/agentic/runtime/AppRuntimeBridge.kt` 的 `createAppRuntimeBridge()` 组装 `RuntimeBridge(settings = XRepoRuntimeGateway(), host = IpcRuntimeHostGateway())`。
   - `agent-runtime/src/main/java/com/niki914/nexus/agentic/runtime/settings/RuntimeEnvironment.kt` 通过 `RuntimeEnvironment.install(...)` 挂载这套 bridge；`RuntimeEnvironment` 实际位于 `agent-runtime` 模块，不在 `app` 模块。
   - `app/src/main/java/com/niki914/nexus/agentic/runtime/client/AgentRuntimeClient.kt` 的 `AgentRuntimeClient(ctx).connect()` 连接 Nexus 主 App 进程中的 `AgentRuntimeService`（前台 Service，Binder IPC），用于后续 LLM 查询提交。
   - `app/src/main/java/com/niki914/nexus/agentic/mod/HookLocalSettings.kt` 的 `HookLocalSettings.update(ctx)` 刷新宿主进程本地设置缓存。

4. **远端配置获取与 fallback 通知**
   - `app/src/main/java/com/niki914/nexus/agentic/repo/WebSettingsApi.kt` 的 `XRepo.web.await()` 先读 `web_settings` store；缓存不可用时再请求远端配置。
   - 命中最近版本回退时，`WebSettingsResult.Success.isFallbackVersion` 为 `true`。
   - `Entrance.onLoad()` 在 fallback 成功后调用 `app/src/main/java/com/niki914/nexus/agentic/mod/XService.kt` 的 `XService.postNotification(...)` 发出版本兼容提示。
   - 只有 `webSettingsResult.configOrNull()` 非空时才继续宿主路由；配置缺失时不会安装业务 Hook。

5. **宿主路由**
   - `Entrance.onSettingsFetched()` 通过 `HostApp.fromPackageName(params.packageName)` 判断当前宿主。
   - 只有 `targetPkg == params.packageName` 时才继续安装，避免把别的宿主配置挂到当前进程。
   - `HostApp.Breeno` 路由到 `BreenoChatHook(scope, client)`，其中 `client` 是上一步创建的 `AgentRuntimeClient` 实例，作为 `AssistantTextSource` 传入。
   - `HostApp.XiaoAi` 路由到 `XiaoaiChatHook(scope, client)`，同理传入 `AgentRuntimeClient`。

6. **宿主进程 Runtime 安装**
   - `h/src/main/java/com/niki914/nexus/h/core/runtime/RuntimeBootstrap.kt` 的 `installIfNeeded()` 为当前宿主进程安装单例 `Runtime`。
   - 当前 `Runtime` 只挂载命中的主业务 Hook；具体的 session、response、input subhook 在该主 Hook 的 `onHook()` 内继续安装。
   - Hook 内部通过 `AssistantTextSource.submit(query)`（即 `AgentRuntimeClient`）将查询经 Binder IPC 提交给主 App 进程的 `AgentRuntimeService`，由 `LLMController.stream()` 处理后流式推回 `RenderFrame`。

## 每轮 query 的分流前置

- `app/src/main/java/com/niki914/nexus/agentic/mod/feat/AbstractAssistantHook.kt` 是 Breeno 与 XiaoAi 共用的运行期入口。
- 宿主输入由各自的 `CaptureInputHook` 捕获后回调 `handleCapturedQuery(roomId, query)`。
- `handleCapturedQuery(...)` 会先调用 `XRepo.takeoverRules.list()` 与 `XRepo.takeoverRules.getDefaultTarget()`，再交给 `TakeoverResolver.resolve(...)` 生成当前轮次决策。
- 决策结果先写入 `ActiveTurnStore` 与 `XEvent` 上下文，再按 `TurnMode` 分流：
  - `TurnMode.NativeTakeover`：调用 `LLMController.stopCurrentRound(keepCurrentTurn = false)` 后返回，保留宿主原生回答链路。
  - `TurnMode.InjectedLLM`：继续调用 `dispatchQueryToLLM(...)`，后续才进入 Nexus 渲染注入链路。

## 关键源码

### `app/src/main/java/`

- `app/src/main/java/a0/a0/a0/a0/a0/a0/Entrance.kt`
- `app/src/main/java/com/niki914/nexus/agentic/mod/HookLocalSettings.kt`
- `app/src/main/java/com/niki914/nexus/agentic/mod/XService.kt`
- `app/src/main/java/com/niki914/nexus/agentic/mod/feat/AbstractAssistantHook.kt`
- `app/src/main/java/com/niki914/nexus/agentic/mod/feat/hyper/XiaoaiChatHook.kt`
- `app/src/main/java/com/niki914/nexus/agentic/mod/feat/oppo/BreenoChatHook.kt`
- `app/src/main/java/com/niki914/nexus/agentic/repo/WebSettingsApi.kt`
- `app/src/main/java/com/niki914/nexus/agentic/repo/XRepo.kt`
- `app/src/main/java/com/niki914/nexus/agentic/runtime/AppRuntimeBridge.kt`
- `app/src/main/java/com/niki914/nexus/agentic/runtime/IpcRuntimeHostGateway.kt`
- `app/src/main/java/com/niki914/nexus/agentic/runtime/service/AgentRuntimeService.kt`
- `app/src/main/java/com/niki914/nexus/agentic/runtime/client/AgentRuntimeClient.kt`
- `app/src/main/java/com/niki914/nexus/agentic/runtime/client/AssistantTextSource.kt`

### `agent-runtime/src/main/java/com/niki914/nexus/agentic/runtime/settings/`

- `agent-runtime/src/main/java/com/niki914/nexus/agentic/runtime/settings/RuntimeEnvironment.kt`

### `h/src/main/java/com/niki914/nexus/h/`

- `h/src/main/java/com/niki914/nexus/h/core/runtime/RuntimeBootstrap.kt`
- `h/src/main/java/com/niki914/nexus/h/util/ContextHook.kt`
- `h/src/main/java/com/niki914/nexus/h/util/ContextProvider.kt`
