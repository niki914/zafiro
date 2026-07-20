# LLM Runtime

本文档只记录当前源码里已经落地的 LLM runtime 事实。

## 运行时主链

### Runtime Bridge

`LLMController` 依赖 `RuntimeEnvironment.awaitSettingsGateway()` 获取配置，这条桥接链路在 App 启动时建立：

1. `app/src/main/java/com/niki914/nexus/agentic/app/App.kt` 在 `onCreate` 时初始化 `AppRuntimeBridge`。
2. `AppRuntimeBridge` 将 `XRepoRuntimeGateway` 注入到 `RuntimeEnvironment` 中。
3. 运行时所有的 `readLlmConfig()`、`listMcpServers()` 等操作，最终都由 `XRepoRuntimeGateway` 代理给 `XRepo`。

### 会话历史闭环 (History)

`LLMController` 本身只持有运行时 `Session`，真正的历史落盘和恢复由 UI 层 `HomeChatViewModel` 驱动：

1. **新建会话**：首次发送消息前，`HomeChatState` 才会触发 `createConversation()` 建表记录。
2. **恢复/切换历史**：启动或在历史页选中记录时，从 `ConversationRepo` 读取历史，通过 `LLMController.replaceHistory()` 回灌进运行时 `Session`。
3. **完成落盘**：`LLMController.stream()` 触发 `LlmStreamEvent.Completed` 后，`HomeChatState` 才会通过 `LLMController.getHistory()` 拉取全量 turn，并调用 `ConversationRepo.saveHistory()` 全量替换落库。

### 记忆闭环 (Memory)

Prompt 中的 `Agent Memory` 来源于 `agent.main.memory` Store，其写入与消费链路如下：

1. **写入侧**：
   - 用户手动在设置页写入：`app/src/main/java/com/niki914/nexus/agentic/app/ui/nexus/model/MemorySettingsState.kt`。
   - Agent 运行时通过工具写入：`agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/buildin/impl/MemorizeBuiltin.kt`。
2. **持久化侧**：统一写入 `agent.main.memory` Store，并由 `MemorySettingsCodec` 持久化到 JSON。
3. **消费侧**：`XRepoRuntimeGateway` 从 Store 读取并注入到 `RuntimeLlmConfig.memories` 中，最终由 `PromptComposer` 拼接入 Prompt。

### Refresh

`agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/LLMController.kt` 中的 `refresh()` 当前顺序如下：

1. 通过 `RuntimeEnvironment.awaitSettingsGateway()` 读取 `readLlmConfig()`、`listMcpServers()`、`listCustomTools()`、`listBuiltinToolSettings()`，并按 server 调用 `listCachedTools(server)`。
2. `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/ToolManager.kt` 把 builtin、custom、MCP 配置解析成 `ResolvedTools`；这里不拼 prompt。
3. `LLMController` 先构造不含运行时 prompt 的 `ResolvedLlmConfig`，再按 provider 复用或重建 `Session`。
4. 第一次 `applyRuntimeConfig()` 通过 `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/SessionToolBinder.kt` 先绑定 builtin、custom 和 MCP cached tools。
5. 当存在已解析的 MCP server，且 session 首次创建或 MCP 指纹变化时，调用 `Session.refreshMcpTools()`；`agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/mcp/McpDiscoveryCacheStore.kt` 再经 `RuntimeEnvironment.awaitSettingsGateway().saveDiscoveredTools()` 把发现结果写回对应的 MCP cache store。
6. `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/PromptComposer.kt` 在拿到 `Session.getMcpDiscoverySnapshot()` 后拼接最终 prompt。当前 prompt section 只有 `Agent Memory`、`Tool Context`、`Additional instructions`；MCP 状态写进 `<mcp_servers>` 块。
7. 第二次 `applyRuntimeConfig()` 把最终 `systemPrompt` 与同一组 `ResolvedTools` 写回 session，并更新 `runtimeState`。

`refreshFromHookContext()` 只是 `refresh()` 的别名，没有单独的 Hook 分支逻辑。

### Stream

`LLMController.stream(query)` 的当前顺序如下：

1. 直接调用 `refresh()`；当前源码里没有 `refreshIfPossibleFromHookContext()` 这条路径。
2. 如果 refresh 抛错且已有最近一次 `runtimeState`，则回退到旧快照继续发送；如果旧快照也不存在，则发出 `LlmStreamEvent.Error` 并结束。
3. `session.send(query)` 的 `SessionEvent` 统一交给 `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/stream/LlmStreamEventMapper.kt` 转成 `LlmStreamEvent`。
4. 文本流在 mapper 内累计全文，并基于起始时间计算 `charsPerSecond`。
5. local tool 结果先经 `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/stream/LocalToolResultClassifier.kt` 判定成功或失败，再映射成 `ToolRunning`、`ToolSucceeded`、`ToolFailed`。
6. `LlmStreamEvent` 只承载结构化事件；宿主层是否把 tool 事件转成展示文案，由 `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/stream/ToolEventFormatter.kt` 决定。

## 组件边界

### `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/`

- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/LLMController.kt`：runtime 总入口；持有单例 `Session`、最近一次 `LlmRuntimeSnapshot`、MCP refresh 指纹和 stream 主链。
- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/LlmModels.kt`：`ResolvedLlmConfig`、`ResolvedTools`、`LocalTool`、`McpServerDefinition` 等运行时模型。
- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/LlmStreamEvent.kt`：对宿主暴露的统一流式事件模型。
- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/ConversationTurnState.kt`
- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/ActiveTurnStore.kt`

### `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/`

- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/PromptComposer.kt`：拼接 memory、tool context、附加指令；MCP 发现状态只影响 `<mcp_servers>` 内容。
- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/ToolManager.kt`：把 builtin settings、custom tools、MCP servers 和 cached tools 解析成 `ResolvedTools`。
- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/SessionToolBinder.kt`：把 builtin、custom、MCP server 定义写入 `SessionConfig.Builder`。
- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/ToolCallDispatcher.kt`：运行时分发 builtin 与 custom local tool 调用。

### `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/accessibility/`

- `.../accessibility/AccessibilityController.kt`：accessibility service 交互控制器；管理 service 连接生命周期，提供屏幕节点树捕获、节点动作执行、手势调度、按键注入、pointer overlay 管理。
- `.../accessibility/IPointerOverlay.kt`：pointer overlay UI 接口；定义 show、animateTo、showSwipe、hide。
- `.../accessibility/NodeModel.kt`：节点语义模型；定义 `SemanticType`（BUTTON、INPUT、TEXT 等）、`Rect`、`NodeInfo`。
- `.../accessibility/PruningRules.kt`：语义类型映射、修剪谓词、容器折叠规则；用于减少 YAML 输出噪音。
- `.../accessibility/TreeFormatter.kt`：将 `AccessibilityNodeInfo` 树格式化为 YAML 结构文本。

### `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/buildin/`

- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/buildin/BuiltinToolRegistry.kt`：当前默认注册 `create_custom_tool`、`launch_app`、`memorize`、`notify`、`open_uri`、`read_custom_tool`、`load_skill`、`terminal`、`ssh_terminal`、`search_apps`、`screen_content`、`search_nodes`、`node_action`、`gesture`、`key_event`。
- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/buildin/BuiltinToolExecutor.kt`：builtin 执行入口。
- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/buildin/BuiltinToolSettingsManager.kt`：通过 `RuntimeEnvironment.awaitSettingsGateway()` 读写 builtin 开关。
- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/buildin/impl/TerminalBuiltin.kt`：Android 终端 builtin；raw JSON 协议，支持 `open`、`open_and_exec`、`exec`、`read_async_result`、`close`。
- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/buildin/impl/SshTerminalBuiltin.kt`：交互式 SSH 终端 builtin；支持 `open`、`send_line`、`write`、`interrupt`、`read`、`close`。

### `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/custom/`

- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/custom/CustomToolManager.kt`：custom tool 的校验、保存、删除、启停。
- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/custom/CustomToolExecutor.kt`：通过 `TerminalSessionPool.executeCustomCommand()` 执行固定命令；不会把 tool call 入参展开进命令模板。

### `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/device/`

- `.../device/AppInfoCache.kt`：应用信息缓存与名称匹配；提供 `AppInfo`、`AppMatchResult`、`AppInfoMatcher`。
- `.../device/AppInfoProvider.kt`：`XProvider<AppInfoCache>` 单例；按需初始化应用信息缓存。

### `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/mcp/`

- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/mcp/McpDiscoveryCacheStore.kt`：把 discovered tools 回写到 runtime settings gateway 对应的 MCP cache store。

### `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/shell/`

- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/shell/ShellCommandSafetyPolicy.kt`：命令安全策略。
- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/shell/TerminalSessionPool.kt`：当前终端入口；负责 Android shell session、SSH session、异步执行、交互输出缓存和 session 关闭。
- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/shell/TerminalToolResponse.kt`：terminal/ssh_terminal 的 JSON 响应格式。

### `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/stream/`

- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/stream/LlmStreamEventMapper.kt`：`SessionEvent` 到 `LlmStreamEvent` 的映射。
- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/stream/LocalToolResultClassifier.kt`：根据 local tool result 判定 tool 调用失败。
- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/stream/ToolEventFormatter.kt`：tool 事件文案格式化。

## 能力状态

### Builtin

- 源码依据：`agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/buildin/BuiltinToolRegistry.kt`、`agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/buildin/BuiltinToolExecutor.kt`、`agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/buildin/BuiltinToolSettingsManager.kt`、`agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/buildin/impl/TerminalBuiltin.kt`、`agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/buildin/impl/SshTerminalBuiltin.kt`。
- 已实现：注册表、运行期开关读取、builtin 执行分发。
- 当前默认 builtin（15 个）：`create_custom_tool`、`launch_app`、`memorize`、`notify`、`open_uri`、`read_custom_tool`、`load_skill`、`terminal`、`ssh_terminal`、`search_apps`、`screen_content`、`search_nodes`、`node_action`、`gesture`、`key_event`。
- 边界：`terminal` 运行在 Android terminal session，不是桌面 shell；`ssh_terminal` 是交互式 SSH 终端，不支持 `exec` 或 `open_and_exec`。
- 兼容性：`app/src/main/java/com/niki914/nexus/agentic/repo/XRepo.kt` 在解析 builtin 开关时仍兼容旧 `run_command` key，但当前真实 builtin 名称已经是 `terminal`。

### Custom

- 源码依据：`agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/custom/CustomToolManager.kt`、`agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/custom/CustomToolExecutor.kt`。
- 已实现：名称校验、保留名校验、命令安全校验、保存、删除、启停、执行。
- 边界：执行的始终是配置中的固定 `command`；tool call 入参不会拼进命令模板。

### MCP

- 源码依据：`agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/ToolManager.kt`、`agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/SessionToolBinder.kt`、`agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/mcp/McpDiscoveryCacheStore.kt`。
- 已实现：HTTP MCP server 绑定、tools discovery、discovered tools 本地缓存写回、按 server 指纹控制 refresh。
- 边界：当前 `McpServerDefinition` 只有 `Http`；源码里未见 stdio 或本地进程 transport。

### Stream

- 源码依据：`agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/stream/LlmStreamEventMapper.kt`、`agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/stream/LocalToolResultClassifier.kt`、`agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/stream/ToolEventFormatter.kt`。
- 已实现：`RoundStarted`、`TextDelta`、`ToolRunning`、`ToolSucceeded`、`ToolFailed`、`Error`、`Completed` 映射。
- 边界：`LlmStreamEvent` 自身只提供结构化状态；展示文本格式化仍在宿主层。

## 关键源码

### `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/`

- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/LLMController.kt`
- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/LlmModels.kt`
- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/LlmStreamEvent.kt`
- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/ConversationTurnState.kt`
- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/ActiveTurnStore.kt`

### `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/`

- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/PromptComposer.kt`
- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/ToolManager.kt`
- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/SessionToolBinder.kt`
- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/ToolCallDispatcher.kt`

### `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/accessibility/`

- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/accessibility/AccessibilityController.kt`
- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/accessibility/IPointerOverlay.kt`
- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/accessibility/NodeModel.kt`
- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/accessibility/PruningRules.kt`
- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/accessibility/TreeFormatter.kt`

### `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/buildin/`

- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/buildin/BuiltinToolRegistry.kt`
- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/buildin/BuiltinToolExecutor.kt`
- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/buildin/BuiltinToolSettingsManager.kt`
- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/buildin/impl/TerminalBuiltin.kt`
- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/buildin/impl/SshTerminalBuiltin.kt`

### `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/custom/`

- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/custom/CustomToolManager.kt`
- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/custom/CustomToolExecutor.kt`

### `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/device/`

- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/device/AppInfoCache.kt`
- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/device/AppInfoProvider.kt`

### `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/mcp/`

- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/mcp/McpDiscoveryCacheStore.kt`

### `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/shell/`

- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/shell/ShellCommandSafetyPolicy.kt`
- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/shell/TerminalSessionPool.kt`
- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/shell/TerminalToolResponse.kt`

### `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/stream/`

- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/stream/LlmStreamEventMapper.kt`
- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/stream/LocalToolResultClassifier.kt`
- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/stream/ToolEventFormatter.kt`
