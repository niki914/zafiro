# Current Status

> 本页只登记本轮已回到源码核对的现状。没有源码回指的能力，不在这里写成已实现。

## Stable

### Builtin tools

- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/buildin/BuiltinToolRegistry.kt` 的默认注册表当前包含 15 个 builtin 实现：`CreateCustomToolBuiltin`、`LaunchAppBuiltin`、`MemorizeBuiltin`、`NotifyBuiltin`、`OpenUriBuiltin`、`ReadCustomToolBuiltin`、`LoadSkillBuiltin`、`TerminalBuiltin`、`SshTerminalBuiltin`、`SearchAppsBuiltin`、`ScreenContentBuiltin`、`SearchNodesBuiltin`、`NodeActionBuiltin`、`GestureBuiltin`、`KeyEventBuiltin`。
- `app/src/main/java/com/niki914/nexus/agentic/repo/XRepo.kt` 的 `BuiltinToolApi` 仍兼容旧 `run_command` 开关读取，但实际注册表并不是”2 个 builtin tools”，而是上面的 15 个默认实现。

### Accessibility / phone-control system

- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/accessibility/AccessibilityController.kt` 封装了设备端屏幕交互能力：截屏（AccessibilityNodeInfo 树捕获）、节点操作（`performAction`）、手势注入（`dispatchGesture`），以及在 accessibility 不可用时回退到 shell 模式。
- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/accessibility/NodeModel.kt` 定义了 `NodeInfo`（含 `SemanticType` 枚举：`BUTTON`、`INPUT`、`TEXT`、`IMAGE`、`LIST`、`SWITCH`、`CHECKBOX` 等）和 `Rect` 边界模型。
- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/accessibility/PruningRules.kt` 实现了基于 class name 和父类型的语义类型映射、越界裁剪、空壳节点过滤。
- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/accessibility/TreeFormatter.kt` 将 `AccessibilityNodeInfo` 树格式化为 YAML 文本，供 LLM 读取当前屏幕结构。
- 上述组件为 `ScreenContentBuiltin`、`SearchNodesBuiltin`、`NodeActionBuiltin`、`GestureBuiltin`、`KeyEventBuiltin` 提供底层能力（均在 `agent-runtime/.../buildin/impl/` 目录下）。

### Skill system

- `app/src/main/java/com/niki914/nexus/agentic/app/ui/nexus/nav/NexusSettingsGroup.kt` 定义了 `Skills` 设置组（title/summary/routeSuffix）。
- `app/src/main/java/com/niki914/nexus/agentic/app/ui/nexus/nav/NexusPage.kt` 声明了 `SkillDetailPage` 页面数据类；`app/.../nexus/NexusPages.kt` 将其路由到 `SkillDetailRoute`。
- Repo 层：`app/src/main/java/com/niki914/nexus/agentic/repo/SkillApi.kt`（public API，含 `listAll`/`listEnabled`/`getDetail`/`saveContent`/`setEnabled`/`delete`/`importSkill`/`seedDefaults`）、`SkillFileRepository.kt`（文件级 CRUD）、`SkillFrontmatterParser.kt`（YAML frontmatter 解析）、`SkillPathResolver.kt`（响应式路径解析）、`SkillEnabledStateStore.kt`（启用状态持久化）。
- UI 层：`app/src/main/java/com/niki914/nexus/agentic/app/ui/nexus/content/SkillsSettingsContent.kt`（设置列表页）、`SkillDetailContent.kt`（详情编辑页）。
- Runtime：`agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/buildin/impl/LoadSkillBuiltin.kt`（LLM 可通过 `load_skill` 工具按 id 加载完整 SKILL.md 内容）。
- 测试覆盖：`SkillApiTest`、`SkillFileRepositoryTest`、`SkillFrontmatterParserTest`、`SkillPathResolverTest`、`SkillEnabledStateStoreTest`、`LoadSkillBuiltinTest`、`LLMControllerRefreshSkillTest`。

### AgentRuntimeService (IPC)

- `app/src/main/java/com/niki914/nexus/agentic/runtime/service/AgentRuntimeService.kt` 是一个 foreground service（声明在 `AndroidManifest.xml`），在系统主进程中运行。通过 Binder IPC（`IAgentRuntimeService.aidl`→`StubImpl`）暴露三个方法：`submit(query, callback)` 启动一轮 LLM 流式对话，`cancel()` 取消当前轮，`resetConversation()` 重置会话。
- `app/src/main/java/com/niki914/nexus/agentic/runtime/client/AgentRuntimeClient.kt` 负责 service 连接生命周期（绑定、断开、死亡回调、自动重连），实现了 `AssistantTextSource` 接口，将 LLM 流式输出通过 `Flow<RenderFrame>` 暴露给调用方。
- `app/src/main/java/com/niki914/nexus/agentic/runtime/ipc/IAgentRuntimeService.kt`、`IRenderFrameCallback.kt`、`RenderFrame.kt` 是自定义 Binder 接口和 Parcelable 数据类。
- `app/src/main/java/a0/a0/a0/a0/a0/a0/Entrance.kt` 在 Xposed 入口处创建 `AgentRuntimeClient` 实例并传递到宿主接管流程。

### PointerOverlay

- `app/src/main/java/com/niki914/nexus/agentic/app/overlay/PointerOverlay.kt` 实现了一个屏幕上的视觉反馈覆盖层，在 agent 执行触摸/手势操作时显示指针动画（`show`/`animateTo`/`showSwipe`/`hide`）。
- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/accessibility/IPointerOverlay.kt` 定义了 `AccessibilityController` 依赖的接口合约。
- `AccessibilityController.pointerOverlay` 字段（见 `accessibility/AccessibilityController.kt` 第 46 行）由 app 模块在运行期注入 `PointerOverlay` 实例，将 agent-runtime 的交互抽象与 app 的 UI 层解耦。

### Store registry 与配置落盘模型

- `store/src/main/java/com/niki914/nexus/store/StoreDescriptorRegistry.kt` 当前注册了 11 个静态 store id：`web_settings`、`local_settings`、`agents.registry`、`agent.main.config`、`agent.main.memory`、`tools.builtin`、`tools.custom`、`tools.mcp.servers`、`rules.execution`、`rules.takeover`、`app.state`。
- 同一文件还支持两类动态 store：`agent.config.<agentId>` 会映射到 settings/agents/ 目录下按 agentId 命名的 config.json，`tools.mcp.cache.<serverId>` 会映射到 settings/tools/mcp/cache/ 目录下按 serverId 命名的 JSON 文件。
- `app/src/main/java/com/niki914/nexus/agentic/repo/XRepo.kt` 的初始化逻辑会落盘 `agents.registry`、`agent.main.config`、`agent.main.memory`，说明 agent registry 已经不是纯文档概念。

### Agent registry 与会话历史页面

- `app/src/main/java/com/niki914/nexus/agentic/repo/AgentSettingsCodec.kt` 与 `app/src/main/java/com/niki914/nexus/agentic/repo/XRepo.kt` 已实现 agent profile 的解析、默认 `main` agent 补全、`list/get/saveProfile/setEnabled`、以及按 `agentId` 读写 LLM 配置。
- `app/src/main/java/com/niki914/nexus/agentic/app/ui/nexus/nav/NexusPage.kt`、`app/src/main/java/com/niki914/nexus/agentic/app/ui/nexus/NexusPages.kt`、`app/src/main/java/com/niki914/nexus/agentic/app/ui/nexus/route/HomePageRoute.kt` 证明 `ConversationHistoryPage` 已接入页面枚举、路由分发和 Home 入口。
- `app/src/main/java/com/niki914/nexus/agentic/app/ui/nexus/route/ConversationHistoryPageRoute.kt` 会加载会话列表、处理选中、删除当前或历史会话；`app/src/main/java/com/niki914/nexus/agentic/app/ui/nexus/content/ConversationHistoryPageContent.kt` 已实现 loading / empty / error / list / delete-confirmation。
- 会话持久化的当前能力边界：
  - `ConversationRepo` 的 `saveHistory()` 每次都是**全量替换** `conversation_turn` 表，不是增量 append。
  - 草稿态（`draftText`）在输入变化时实时写回 Repo，与正式的消息历史持久化分离。
  - 当前会话指针（`last_opened_conversation_id`）没有存在 Room，而是存在 `app.state` JSON（由 `AppStateSettingsCodec.kt` 负责）。

### 宿主接管与注入链路

- **Breeno** (`ColorOS`)：已在 `BreenoChatHook.kt` 实现了完整的拦截流。通过 `BlockNativeCardHook` 阻断原生卡片，通过 `CaptureInputHook` 截获 `dataCenterInstance`，然后利用反射生成伪造卡片并执行**全量刷新**更新。
- **XiaoAi** (`HyperOS`)：已在 `XiaoaiChatHook.kt` 实现了基于流式分片的注入。利用 `CaptureResponseTargetHook` 捕获渲染目标并使用 `CompletableDeferred` 处理并发时序，配合白名单指令拦截与 TTS 拦截子 Hook，通过 `RenderTextStreamCardHook` 实现打字机式注入。
- 通用接管逻辑 `TakeoverResolver` 已接入上述两个宿主的输入管线。

## In Progress

### Multi-agent state

- 多 agent 的数据层已经部分落地：`agents.registry`、`agent.config.<agentId>`、`RuntimeAgentProfile`、`AgentMemoryMode`、以及 builtin / custom / MCP 的 `enabled_for_agents` 编解码，分别可在 `store/src/main/java/com/niki914/nexus/store/StoreDescriptorRegistry.kt`、`app/src/main/java/com/niki914/nexus/agentic/repo/AgentSettingsCodec.kt`、`app/src/main/java/com/niki914/nexus/agentic/repo/ToolSettingsCodec.kt`、`app/src/main/java/com/niki914/nexus/agentic/repo/McpSettingsCodec.kt` 中回指。
- 但 runtime 侧还不是完整的多 agent 执行链：`agent-runtime/src/main/java/com/niki914/nexus/agentic/runtime/settings/RuntimeSettingsGateway.kt` 只有 `readLlmConfig(agentId)` 带 `agentId`，`listMcpServers()`、`listCustomTools()`、`listBuiltinToolSettings()`、`listExecutionRules()` 仍是无参接口；`app/src/main/java/com/niki914/nexus/agentic/repo/XRepoRuntimeGateway.kt` 也按这个形状实现。
- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/LLMController.kt` 当前直接调用 `gateway.readLlmConfig()` 的默认参数，并搭配上述无参列表接口刷新 runtime；因此“多 agent 已完整接通到运行时”目前没有源码证据，只能记为部分落地。

## Proposal

- 本轮核对范围内，没有新增到可以单独登记的 source-backed Proposal 条目。

## Unverified

- 设置页覆盖范围、MCP transport 细节，本轮没有为这次修文重新逐项回到源码核验。
