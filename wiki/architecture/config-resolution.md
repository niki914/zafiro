# Config Resolution

本文件基于 `ipc/src/main/java/com/niki914/nexus/ipc/store/StoreDescriptorRegistry.kt`、`app/src/main/java/com/niki914/nexus/agentic/repo/XRepoRuntimeGateway.kt` 与 `ipc/src/main/java/com/niki914/nexus/ipc/store/ConfigPersistence.kt`，描述当前源码里的配置来源、runtime settings 网关、IPC 边界与落盘方式。

## 配置分层

`ipc/src/main/java/com/niki914/nexus/ipc/store/StoreDescriptorRegistry.kt` 对应的当前实现已经不是单一 web_settings.json / local_settings.json 双文件模型。

- `web_settings`：宿主版本相关的远端配置缓存，默认落到 settings/hooks.json。
- `local_settings`：仍保留的兼容 store，当前落到 local_settings.json。
- agent/runtime 配置：已经拆到 settings/ 目录下的多 store，由 `ipc/src/main/java/com/niki914/nexus/ipc/store/StoreDescriptorRegistry.kt` 统一注册。

静态 store 注册如下：

| Store ID | 运行时相对路径 |
| --- | --- |
| `web_settings` | settings/hooks.json |
| `local_settings` | local_settings.json |
| `agents.registry` | settings/agents/registry.json |
| `agent.main.config` | settings/agents/main/config.json |
| `agent.main.memory` | settings/agents/main/memory.json |
| `tools.builtin` | settings/tools/builtin_tools.json |
| `tools.custom` | settings/tools/custom_tools.json |
| `tools.mcp.servers` | settings/tools/mcp/servers.json |
| `rules.execution` | settings/rules/execution_rules.json |
| `rules.takeover` | settings/rules/takeover_rules.json |
| `app.state` | settings/app_state.json |

动态 store 由 `StoreDescriptorRegistry.resolveDynamic(storeId)` 按前缀生成：

| Store ID 模式 | 运行时相对路径模式 | 生成条件 |
| --- | --- | --- |
| `agent.config.<agentId>` | settings/agents/ 目录下按 agentId 命名的 config.json | `agentId` 符合安全命名规则 |
| `tools.mcp.cache.<serverId>` | settings/tools/mcp/cache/ 目录下按 serverId 命名的 JSON 文件 | `serverId` 符合安全命名规则 |

`XRepo.tryPutDefaultSettings()` 在 onboarding 未完成时会初始化 `agent.main.config`、`agent.main.memory`、`agents.registry`、`tools.custom` 和 `rules.execution`。其中 `agents.registry` 会写入默认的 `main` agent profile。

## 主 App 初始化链路

主 App 启动时的配置相关初始化分成两层：

1. `app/src/main/java/com/niki914/nexus/agentic/app/App.kt`：`XRepo.init(applicationContext)` -> `RuntimeEnvironment.install(createAppRuntimeBridge())` -> 异步触发 `XRepo.web.await()` 和 `XRepo.tryPutDefaultSettings()`。
2. `app/src/main/java/com/niki914/nexus/agentic/app/MainActivity.kt`：`ContextProvider.provide(applicationContext)` -> `XRepo.init(applicationContext)` -> `resolveStartupAssistantUi()` -> `AppLaunchDecision.resolve(...)`。

`app/src/main/java/com/niki914/nexus/agentic/runtime/AppRuntimeBridge.kt` 当前把 runtime settings 网关固定到 `XRepoRuntimeGateway()`；`agent-runtime` 侧统一通过 `RuntimeEnvironment.awaitSettingsGateway()` 访问配置，不直接碰 `XRepo`。

## 宿主进程读取链路

宿主侧入口仍从 `app/src/main/java/a0/a0/a0/a0/a0/a0/Entrance.kt` 启动：

- `XRepo.init(ctx)`：初始化仓库入口。
- `RuntimeEnvironment.install(createAppRuntimeBridge())`：把同一套 runtime settings gateway 装进宿主进程。
- `HookLocalSettings.update(ctx)`：刷新 Hook 侧 `LocalSettings` 缓存。
- `XRepo.web.await()`：读取或刷新远端 web settings。

`app/src/main/java/com/niki914/nexus/agentic/repo/XRepoRuntimeGateway.kt` 是 runtime 到仓库的适配层：

- `readLlmConfig(agentId)` 通过 `repo.agents.llm(agentId)` 读取 agent 配置，再通过 `repo.agents.memoriesFor(agentId)` 合并 memory。
- `agentId == "main"` 时读取 `agent.main.config`。
- 非 `main` agent 走动态 store `agent.config.<agentId>`；若 profile 不存在或未启用，则回退为空配置。
- 非 `main` agent 的 memory 当前不单独落盘；只有 profile 的 `memoryMode == SharedMain` 时才复用 `agent.main.memory`。
- `listBuiltinToolSettings()`、`listCustomTools()`、`listMcpServers()`、`listCachedTools(server)`、`listExecutionRules()` 全部走 `XRepo` 对应 API。
- `saveDiscoveredTools()`、`setBuiltinToolEnabled()`、`saveCustomTool()` 等写操作也回到 `XRepo`。

## IPC 与持久化职责

### 进程分流

`ipc/src/main/java/com/niki914/nexus/ipc/XIpcBridge.kt` 根据 `XValues.getAppTypeOf(context)` 分流：

- 主 App 进程：直接调用 `ipc/src/main/java/com/niki914/nexus/ipc/store/XIpcStoreRepository.kt`。
- 宿主进程：通过 `ContentResolver.openInputStream()` / `openOutputStream()` 访问 `SettingsContentProvider.openFile()` 暴露的 store 文件；命令式局部更新走 provider `call()`。

当前只有 `web_settings` 在 `XIpcBridge` 内做进程内字符串缓存；其他 store 读取都走 uncached 路径。

### Provider 协议

`ipc/src/main/java/com/niki914/nexus/ipc/XRes.kt` 当前同时保留旧方法和泛化方法：

- 旧兼容方法：`GET_WEB_SETTINGS`、`GET_LOCAL_SETTINGS`、`MUTATE_WEB_SETTINGS`、`MUTATE_LOCAL_SETTINGS`。
- 当前泛化方法：`GET_STORE`、`MUTATE_STORE`。

`ipc/src/main/java/com/niki914/nexus/ipc/cp/XProviderDispatcher.kt` 的现状是：

- `GET_STORE` 与旧的 `GET_WEB_SETTINGS` / `GET_LOCAL_SETTINGS` 都只返回 `STORE_URI`，不再把整段 JSON 放进 `Bundle`。
- `MUTATE_STORE` 调用 `XIpcStoreRepository.mutateJson(storeId, path, valueJson)`。
- `PUT_WEB_SETTINGS` 与 `PUT_LOCAL_SETTINGS` 当前没有实际分发逻辑；宿主写整段 JSON 的主路径已经改成 `openFile(..., "wt")`。

`ipc/src/main/java/com/niki914/nexus/ipc/cp/SettingsContentProvider.kt` 的职责是：

- 做 caller 校验。
- 在 `call()` 中把 `GET_STORE` / `MUTATE_STORE` / 兼容方法分发给 `XProviderDispatcher`。
- 在 `openFile()` 中按 `StoreDescriptorRegistry.resolveDynamic(storeId)` 打开真实 store 文件；写入时创建 pipe，再由后台线程调用 `XIpcStoreRepository.writeJson()` 做原子落盘。

### Agent Runtime Service (Binder IPC)

除 ContentProvider 通道外，`app/src/main/java/com/niki914/nexus/agentic/runtime/service/AgentRuntimeService.kt` 是运行在 Nexus 主 App 进程的一个 Android 前台 Service（`NOTIFICATION_ID=1001`，通知渠道 `agent_runtime`），通过 `IAgentRuntimeService.Stub` 暴露 AIDL 风格的 Binder 接口，专门用于 LLM 运行时交互：

- `submit(query, callback)`：提交一条用户查询，通过 `IRenderFrameCallback.onFrame(RenderFrame)` 流式推送 LLM 响应帧。同一时刻只允许一个活跃 turn；重复提交会收到错误回调。
- `cancel()`：取消当前活跃 turn 的协程，同时调用 `LLMController.stopCurrentRound()`。
- `resetConversation()`：重置对话状态，取消当前 turn 后调用 `LLMController.resetConversation()`。
- Binder 意外死亡时（`IBinder.DeathRecipient`），自动取消当前 turn 并清理。

`app/src/main/java/com/niki914/nexus/agentic/runtime/ipc/IAgentRuntimeService.kt` 是服务端接口及其 `Stub`/`Proxy` 实现。`app/src/main/java/com/niki914/nexus/agentic/runtime/ipc/IRenderFrameCallback.kt` 是 oneway Binder 回调接口，接收 `app/src/main/java/com/niki914/nexus/agentic/runtime/ipc/RenderFrame.kt`（Parcelable，含 `text`/`isFirst`/`isFinal` 字段）。

`app/src/main/java/com/niki914/nexus/agentic/runtime/client/AgentRuntimeClient.kt` 是宿主进程侧的客户端，实现 `app/src/main/java/com/niki914/nexus/agentic/runtime/client/AssistantTextSource.kt` 接口：

- 通过 `context.bindService()` 连接到 `AgentRuntimeService`（action=`com.niki914.nexus.agentic.runtime.BIND`）。
- 将 Binder 调用封装为 `Flow<RenderFrame>`（基于 `callbackFlow`），供 `AbstractAssistantHook.dispatchQueryToLLM()` 消费。
- 支持自动重连（最多 3 次，间隔 2s），并通过 `connectionState: StateFlow<ConnectionState>` 暴露连接状态（`Disconnected` / `Connecting` / `Connected` / `Reconnecting` / `Rejected` / `Unavailable`）。
- 服务端 `validateCaller()` 校验调用方 UID，仅允许 Nexus 自身包名及 `HostApp.packageNames` 中的进程绑定。

#### 与 ContentProvider IPC 的职责划分

| 通道 | 用途 | 方向 |
| --- | --- | --- |
| `SettingsContentProvider`（ContentProvider） | settings store 读写、通知发送 | 双向：宿主 ↔ Nexus |
| `AgentRuntimeService`（Binder Service） | LLM 查询提交、流式回调、对话管理 | 宿主 → Nexus（回调回宿主） |

ContentProvider IPC 继续负责配置持久化和宿主通知；Agent Runtime Service IPC 接管 LLM 运行时交互。

### Store 仓库

`ipc/src/main/java/com/niki914/nexus/ipc/store/XIpcStoreRepository.kt` 当前按 store 维度维护 `Mutex`：

- `readJson()`：读取持久化 JSON；文件不存在或内容非法时回退到 `StoreDescriptor.defaultJson`。
- `writeJson()`：要求写入内容是 JSON object，再调用 `ConfigPersistence.writeJson()`。
- `mutateJson()`：基于 `IpcJsonMutator` 做局部路径更新后再落盘。

`ipc/src/main/java/com/niki914/nexus/ipc/store/ConfigPersistence.kt` 把 store 写到 filesDir 下的相对路径目标，通过 `AtomicFile` 保证原子写入。

## Web Settings 回退

`app/src/main/java/com/niki914/nexus/agentic/repo/WebSettingsApi.kt` 与 `app/src/main/java/com/niki914/nexus/agentic/repo/WebSettingsModels.kt` 共同定义了客户端回退逻辑。

当前客户端行为如下：

1. 先请求 https://gitee.com/niki914/nexus-res/raw/main/{packageName}/{versionCode}/config.json。
2. 若精确版本 404，则请求 https://gitee.com/niki914/nexus-res/raw/main/{packageName}/versions.json。
3. 通过 `app/src/main/java/com/niki914/nexus/agentic/repo/WebSettingsModels.kt` 中的 `WebSettingsVersionFallback.nearestVersionCode()` 选择距离最近的版本；距离相同优先较低版本。
4. 成功结果回写 `web_settings` store，并带上 `requested_version_code` 与 `resolved_version_code`。

## 关键源码

### `app/src/main/java/com/niki914/nexus/agentic/app/`

- `app/src/main/java/com/niki914/nexus/agentic/app/App.kt`
- `app/src/main/java/com/niki914/nexus/agentic/app/MainActivity.kt`

### `app/src/main/java/com/niki914/nexus/agentic/repo/`

- `app/src/main/java/com/niki914/nexus/agentic/repo/XRepo.kt`
- `app/src/main/java/com/niki914/nexus/agentic/repo/XRepoRuntimeGateway.kt`
- `app/src/main/java/com/niki914/nexus/agentic/repo/DomainSettingsStore.kt`
- `app/src/main/java/com/niki914/nexus/agentic/repo/WebSettingsApi.kt`
- `app/src/main/java/com/niki914/nexus/agentic/repo/WebSettingsModels.kt`
- `app/src/main/java/com/niki914/nexus/agentic/repo/AgentSettingsCodec.kt`
- `app/src/main/java/com/niki914/nexus/agentic/repo/MemorySettingsCodec.kt`
- `app/src/main/java/com/niki914/nexus/agentic/repo/ToolSettingsCodec.kt`
- `app/src/main/java/com/niki914/nexus/agentic/repo/McpSettingsCodec.kt`
- `app/src/main/java/com/niki914/nexus/agentic/repo/RuleSettingsCodec.kt`
- `app/src/main/java/com/niki914/nexus/agentic/repo/AppStateSettingsCodec.kt`

### `app/src/main/java/com/niki914/nexus/agentic/runtime/`

- `app/src/main/java/com/niki914/nexus/agentic/runtime/AppRuntimeBridge.kt`
- `app/src/main/java/com/niki914/nexus/agentic/runtime/IpcRuntimeHostGateway.kt`

### `app/src/main/java/com/niki914/nexus/agentic/runtime/service/`

- `app/src/main/java/com/niki914/nexus/agentic/runtime/service/AgentRuntimeService.kt`

### `app/src/main/java/com/niki914/nexus/agentic/runtime/client/`

- `app/src/main/java/com/niki914/nexus/agentic/runtime/client/AgentRuntimeClient.kt`
- `app/src/main/java/com/niki914/nexus/agentic/runtime/client/AssistantTextSource.kt`

### `app/src/main/java/com/niki914/nexus/agentic/runtime/ipc/`

- `app/src/main/java/com/niki914/nexus/agentic/runtime/ipc/IAgentRuntimeService.kt`
- `app/src/main/java/com/niki914/nexus/agentic/runtime/ipc/IRenderFrameCallback.kt`
- `app/src/main/java/com/niki914/nexus/agentic/runtime/ipc/RenderFrame.kt`

### `app/src/main/java/com/niki914/nexus/agentic/mod/`

- `app/src/main/java/com/niki914/nexus/agentic/mod/HookLocalSettings.kt`
- `app/src/main/java/com/niki914/nexus/agentic/mod/XService.kt`

### `ipc/src/main/java/com/niki914/nexus/ipc/`

- `ipc/src/main/java/com/niki914/nexus/ipc/XIpcBridge.kt`
- `ipc/src/main/java/com/niki914/nexus/ipc/XRes.kt`
- `ipc/src/main/java/com/niki914/nexus/ipc/cp/SettingsContentProvider.kt`
- `ipc/src/main/java/com/niki914/nexus/ipc/cp/XProviderDispatcher.kt`
- `ipc/src/main/java/com/niki914/nexus/ipc/store/StoreDescriptor.kt`
- `ipc/src/main/java/com/niki914/nexus/ipc/store/StoreDescriptorRegistry.kt`
- `ipc/src/main/java/com/niki914/nexus/ipc/store/XIpcStoreRepository.kt`
- `ipc/src/main/java/com/niki914/nexus/ipc/store/ConfigPersistence.kt`
