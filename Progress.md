# Nexus × OKIA 接入 Progress

> 本文档是 Agent 会话的恢复锚点。每次回退会话/回退提交后，先读本文件恢复上下文。
> 状态标签：`待讨论` / `已确认` / `进行中` / `已完成` / `已提交`

## 状态

- 分支：`feat/okia-integration`（基于 main `84f28cd`，即并入 PR #121 okia 库之后）
- 当前阶段：**T3 已提交 + 双份消息 bug 已修复**（34bd1bb 根因修复 + cc01488 workaround 清理）；T4 待讨论
- 目标：把 Nexus 的 LLM 运行时从 `libs:kai` 切换为 `libs:okia`（`Okia` 门面），`libs:kai` 最终删除，不留兼容层
- 约束：单个功能点新增+删除合计 ≤1000 行；每完成一个功能点更新本文档并由用户触发 git 提交；允许部分业务 Bug，先跑通主链路

## 工作方式（与用户的约定）

1. 每个功能点 = 一个 T 或 T 的子任务，做完由用户验收
2. 用户会把会话回退到某个点位（git 回退 / 会话回退）后重新开始 → 靠本文件恢复
3. 本文件在每次功能点完成时更新，并随功能点一起提交
4. 用户验收命令：见「验证」节
5. 调研结论（Nexus↔KAI 交接现状、OKIA 概念对照）归档在下方「调研记录」节，不回退后重查代码可先读它

## 已定决策（2026-08-19）

| # | 决策 | 状态 |
|---|---|---|
| D1 | **持久化推倒重来**：Room schema 不写迁移，直接重建；`ChatTurnJsonCodec` 删除；旧会话数据可丢 | 已确认 |
| D2 | **systemPrompt 每回合传** `send(text, TurnOptions(systemPrompt))`（OKIA 的 config 无 systemPrompt 字段，唯一入口是 TurnOptions）替代现在 `update{ systemPrompt }` 会话级热更新 | 已确认 |
| D3 | **MCP 同步发现**：OKIA 缺首次 send 前同步 `refreshMcpTools` 的 ensure API → 在 `feat/okia-integration` 分支开 issue 提给 okia，未来补；接入期先接受异步发现或手动先 refresh | 已确认 |
| D4 | **fork/regenerate 截断** = 以目标 entry 为根沿 parentId 链回溯收集闭合子树 → 新 `SessionSnapshot(id=新, leafId=null, version, entries=子树)` → `open(restore)` 新实例。前提：截断点必须是 User 消息；fork 前无活跃回合（export 活跃回合抛异常）。`RealConversation` 构造只校验重复 id / 悬挂 leafId，不校验 parentId，子树必须自行保证链闭合 | 已确认 |
| D5 | **不写 UI thinking**：T1-T4 均不接入 thinking 块渲染 | 已确认 |
| D6 | **消息接驳**：`Okia.send(text, options, onEvent)` 是 callback + 返回 `TurnResult`（终态唯一权威 = 返回值）。`LLMController.stream` 内部把 onEvent 包成 `Flow<LlmStreamEvent>`，两个消费端（`AgentRuntimeService.executeTurn`、`HomeChatState.collectLlmStream`）接口不动 | 已确认 |
| D7 | **延迟消息不用 Hook**：`TerminalSessionPool.drainPendingNotifications()` 保持 host 侧拼进 send 文本（okia PRD §5.10 裁决），不进 beforeInput | 已确认 |
| D8 | **错误可重试维度进入 UI**：`TurnResult.Failed(error)` 的 LLMErrorCode 分类 → 可重试（RateLimit/Overloaded/Transport/RetryExhausted）保留"请稍后重试"；不可重试（Auth/Quota/ConfigRequired/UnknownTool/HookFailed）给具体文案 | 已确认 |
| D9 | **锁**：删 `LLMController.turnMutex`（OKIA 实例内 Mutex 承担）；adapter 捕获 OKIA 并发异常 → 转 `LlmStreamEvent.Error(TurnConflict)` 保持 UI 行为；`AgentRuntimeService.activeTurn`（Binder 层 CAS）保留 | 已确认 |
| D10 | **kill-then-stop 下沉**：PyRuntime.kill + TerminalSessionPool.closeAll 移入 `Hooks.beforeStop`；`stop()` 由 OKIA 统一协调 | 已确认 |
| D11 | **idle 显式配置**：`OkiaConfig.idleTimeoutSeconds`（默认 null），按 agent 事件活跃度，阈值接入时定 | 已确认 |
| D14 | **O2 澄清**：kill 资源动作放 `Hooks.beforeStop`（回合级杀资源时机，参数=本回合已派发 calls）；`ToolExecutor.onInterrupt` 是工具级中断语义契约，库 main 代码当前无调用者（§8.18 Q1），T2 实现方法体，与 stop 路径无关 | 已确认 |
| D15 | **O3 澄清**：replaceHistory 的 4 个场景（启动恢复/切会话/重新生成/分叉）全部由「持久化 + export/open」覆盖；`getHistory/replaceHistory` 这对 API 整体消失；唯一自定义段 = fork 截断子树构造（D4）；T1 桥接仅为过渡 | 已确认 |
| D16 | **库坑（实测发现）**：`RealConversation.project(null)` 返回空列表（RealConversation.kt:121），与 PRD §5.3「leafId null = 恢复为最后一条」的文档意图不一致。**T1 绕开**：`buildSnapshotFromChatTurns` 显式设 `leafId = entries.lastOrNull()?.id`。**待提 issue 给 okia**（与 D3 同步发现 API 同批） | 已确认（T1 落地绕开，issue 待提） |
| D17 | **T1 工具退化记录**：refresh 仍把 resolvedTools 传入 PromptComposer（技能/记忆段依赖工具段渲染），但 OKIA 注册表为空、不向请求注入 tool 定义——模型若调用未注册工具 → `LLMErrorCode.UnknownTool` 回合失败（T2 接入前为已知退化）；MCP 发现/指纹决策已从 refresh 删除 | 已确认 |
| D18 | **T2 拆分**：T2a=本地工具注册与执行（builtin+custom）；T2b=MCP 装配与发现时序。每块 ≤1000 行 | 已确认 |
| D19 | **内置工具描述迁移（方案 A）**：BuiltinTool 抽象改为 OKIA 声明式描述（description/inputSchemaJson/轻量参数声明），替换 kai `LocalToolConfig` DSL；15 个工具文件迁移；无 workaround；**描述文本原文照抄一个字符不差**（withCustomShellGuidance 英文段/各工具长 description/CreateCustomTool hint） | 已确认 |
| D20 | **CreateCustomTool**：默认 `enabled=true`；refresh 注册保持 `filter { it.enabled }`（enabled=false 不注册）；创建成功执行后**回合内注册**（同 Turn 下一轮可见，RealAgentLoop:170 每段现取 registry.snapshot() 支撑）；OKIA ToolRegistry 注释「活跃回合不得直接改 registry」与 update 路由矛盾 → 并 D3 issue 批（文档修复级，不阻塞），Nexus 直接 register | 已确认 |
| D21 | **custom tool schema**：`inputSchemaJson = null`（协议层省略 parameters），代码打 TODO（未来支持自定义字段） | 已确认 |
| D22 | **MCP 时序（方案 A）**：LLMController 保存「已 refresh 的服务器配置签名」（name/url/headers/enabled 序列化），签名变化才 `refreshMcpTools()`（该调用本身挂起至完成=同步一轮，无需 OKIA 新 API）；无变化不刷新 | 已确认 |
| D23 | **MCP 持久化本次不做**：删残链（gateway.listCachedTools / XRepo saveDiscoveredTools / McpCachedTool / mcpCacheKey / McpServerDefinition.cachedTools / refresh 的 mcpCachedTools）；记 TODO：未来 Nexus store 层持久化 OKIA McpDiscoverySnapshot（需先提 issue：McpDiscoveredTool 加 @Serializable，已核实当前无可序列化） | 已确认 |
| D24 | **hooks 不接**：T2 无 beforeToolCall/afterToolCall（读屏前置非 hook 用例 §5.12），T4 有需求再加 | 已确认 |
| D25 | **T2 测试策略**：不做逐工具描述 golden 断言（膨胀脆弱）。三档：①注册装配=refresh 后 registry 工具名集合正确（名字级）②描述合法性=schema JSON 可解析/name 合法/wireName 满足 ToolWireName 约束 ③执行行为=fake call→outcome 映射（Success/Failure/Interrupted/Unknown）。不写扫字符串式测试 | 已确认 |
| D26 | **T2c 未知工具行为修复（t2 验收暴露）**：模型调用未注册工具从「回合 Failed(UnknownTool)」改为「ToolCallOutcome.Failure 结果回喂，回合继续」（对齐 KAI ToolCallCoordinator 与 OKIA 内部 MCP server-not-found 一致；§1 哲学：不替产品决策 + 不伪造消息）。默认文案纯文本 `Unknown tool '<name>'`（message 与 content 同值：message 供 UI / content 回喂模型）。**不开下游定制口子**（无消费者，遵循延迟设计 API 哲学；ROI 低，不提 issue）。`LLMErrorCode.UnknownTool` 删除（不再产生，不留死代码，已扫全仓）。边界：仅「模型命名错误」走回喂（模型可自纠）；executor 违反契约（ToolExecutionFailed）/协议/认证等仍回合失败 | 已确认 |
| D-T2B-1 | **MCP 持久化彻底删除**（不作保留空实现）：缓存系统全部删除（网关 4 方法/XRepo McpApi 缓存 6 方法/McpSettingsCodec 缓存/McpCachedTool/RuntimeMcpTool/mcpCacheStoreId）。依据：服务器通→eager 预加载即得；不通→缓存了也不能执行（Codex 亦无跨启动持久化） | 已确认 |
| D-T2B-2 | **PromptComposer 删 `<mcp_servers>` 块 + mcpDiscoverySnapshot 参数**：线缆名 `mcp__server__tool` 已表达服务器归属；kai snapshot import 从 agent-runtime 消失 | 已确认 |
| D-T2B-3 | **MCP 时序 = 方案 B（后台不阻塞）**：启动 eager（首次 refresh 签名 null≠配置天然触发）+ turn 前签名变化起后台协程刷新（不 await）+ inFlight 防重 + 失败也更新签名防风暴 + 无保存点回调。Codex 实证：optional 服务器首回合可缺席（仅 required 被 turn 前 await）；Nexus/OKIA 无 required 概念 → 纯 optional 语义 | 已确认 |
| D-T2B-4 | **Okia 改动本次做**：`OkHttpEngine` internal→public + okhttp implementation→api + 1 测试（proxy/interceptor 注入点）；**proxy 使用本次不做**（llmConfig.proxy 死字段保留，TODO = Nexus 注入带 interceptor 的 client） | 已确认 |
| D-T2B-5 | custom schema null（D21）/ hooks 不接（D24）等既有项不在 T2b 重复 | 已确认 |
| D12 | **单测重写而非改**：接口全变处（LLMController/Mapper/持久化）测试重写；工具实现测试不动 | 已确认 |
| D13 | **删除概念**：SessionToolBinder、McpDiscoveryCacheStore、lastMcpServersFingerprint+shouldRefreshMcp、ChatTurnJsonCodec、turnMutex、`:libs:kai` 依赖。保留：LlmStreamEvent/ToolCallStatus/ToolCallKind、ConversationTurnState/ActiveTurnStore/TurnMode、RenderFrame、Room 表骨架、TerminalSessionPool.pendingNotifications | 已确认 |

## T 计划

| T | 范围 | 验收 | 状态 |
|---|---|---|---|
| T1 | 骨架替换：依赖切 okia；重写 LLMController + LlmStreamEventMapper；协议装配（apiType→protocol）；错误/并发/stop 适配；主 App 问答+停止+错误文案跑通 | 宿主与主 App 均一问一答、停止、错误事件正确；UI 消费端零改动 | **已完成**（2026-08-19 已提交 f842dbc/c87d630） |
| T2a | 本地工具：BuiltinTool 描述迁移（D19）+ ToolRegistry 装配（refresh 注册 enabled 工具）+ ToolCallDispatcher→ToolExecutor 适配（execute/onInterrupt）+ CreateCustomTool 回合内注册（D20）+ outcome→BuiltinToolResult 拆解 | 内置/自定义工具回合成功（memory/search_apps/python 等）；模型调用已注册工具不再 UnknownTool；工具失败 UI 显示 code/message | **已完成**（2026-08-19，待用户验收） |
| T2b | MCP：McpServer 配置→OkiaConfig.mcpServers 装配；签名变化触发后台 refreshMcpTools（D22 方案 B）；删 cachedTools 残链（D23）；PromptComposer 删 mcp 段（D-T2B-2）+ 脱离 kai | MCP 工具被发现、注册进 registry、可调用；禁用/失败不崩；后台刷新不阻塞回合；首回合按 eager 预取 | **已完成**（2026-08-19 验收：真机 13 工具 10 可用，禁用不可调用符合预期） |
| T2c | okia 库修复：未知工具 → Failure 结果回喂（RealAgentLoop.executeTools find-miss）；删 LLMErrorCode.UnknownTool（死代码扫描）；测试重写 unknownToolFailsTurn→feedsBackFailureAndContinues | okia/agent-runtime/app 全量测试绿；未知工具时不整轮失败、UI 显示工具失败卡片 | **已完成**（2026-08-19，待验收） |
| T3 | 持久化：Room 推倒重来（消息级增量 + 树形存储 + leafId）；restore/切会话/fork/regenerate（D4）；错误回合保留 + 切会话 stop 修复 | 见 `docs/T3.md` §5 验收 | **设计已定稿**（docs/T3.md），待开发 |
| T4 | 细节收口：idle 配置、beforeStop 正式接管 kill、可重试 UI 分支、并发→TurnConflict 回归、删余清尾（含删 :libs:kai 依赖）、全量测试 | 无 kai 引用、无死代码、全测试绿 | 待讨论 |

## T3 实现记录（2026-08-23，已完成，待验收）

**改动文件**（主代码 ±947 行、测试 +550 行，净增 ~1100 行，略超单点 1000 上限，接受）：
- `app/build.gradle.kts`：`:libs:kai` → `:libs:okia`（app 彻底脱离 kai）
- `ConversationEntities.kt`（重写）：`ConversationEntity` 加 `leaf_id` 列；`ConversationTurnEntity` → `ConversationEntryEntity`（复合主键 conversation_id+id、parent_id、timestamp、message_json，无 turn_index）；`ConversationRecord.history` → `snapshot: SessionSnapshot`
- `ConversationDatabase.kt`：version 2 + `fallbackToDestructiveMigration()`（推倒重来，D1）；schema 2.json 已生成（untracked，随提交入库）
- `ConversationDao.kt`：删 replaceTurnsAndMetadata/listTurns；新 insertEntries(@Insert IGNORE 幂等)/countEntries/updateLeafId/updateConversationMetadata
- `ConversationRepo.kt`：`createConversation(id, firstUserInput)`（显式 id = OKIA 树 id）；`getConversation` 组装 SessionSnapshot（leafId null → 最后一条，绕 D16）；删 saveHistory；新 insertEntries/updateLeafId/updateMetadata/countEntries；`forkConversation(sourceId, keepEntryCount, kind)` 截断子树复制 + Fork/Regenerate 标题（getString runCatching fallback 硬编码）
- `ConversationFormatter.kt`（重写）：消费 SessionSnapshot；`projectLeaf(entries, leafId)`（leafId null → 最后一条）；`toHomeTurns` 按 Message.User 分 turn、ToolCall/ToolResult 配对（outcome 5 态 → Succeeded/Failed）、Thinking 忽略（D5）；preview 从尾部找首个非空文本（修复 firstNotNullOfOrNull 取到 ToolResult 的 bug）
- `ConversationPersister.kt`（新，80 行）：观察 `LLMController.currentConversation` → `persistNow` 条数对比增量写；parentId = 投影前一条 id（线性树）；会话切换按 id 隔离；崩溃窗口=半句话可接受（D3-1）；`resetForTest`
- `LLMController.kt`（±189）：删 getHistory/replaceHistory/buildSnapshotFromChatTurns/toOkiaMessage/toChatTurn；`resetConversation` 改语义（kill 资源 + close + 置 null，不建实例）；新 `ensureSession()`（惰性建实例返回树 id）、`openSession(restore)`（恢复入口）、`currentConversation: StateFlow<Conversation?>`（统一快照流，实例切换重发射）、`historySnapshot()`
- `HomeChatState.kt`（±134）：ChatRuntime 接口换（删 getHistory/replaceHistory，加 ensureSession/openSession/historySnapshot）；删 persist 相关（持久化器接管）；`startNewConversation`/`loadConversation`/`deleteConversationNow` 先 stop 再关实例（D3-9）；restore/load 走 openSession + 新 Formatter；ensureCurrentConversation = ensureSession 树 id 建 Room 会话；fork/regen 走 historySnapshot + forkConversation(kind)
- `App.kt`：`ConversationPersister.start(applicationScope)`
- strings.xml ×3：`conversation_fork_title`/`conversation_regenerate_title`（英文值 "Fork · %1$s"/"Regenerate · %1$s"，D3-11）
- 删除：`ChatTurnJsonCodec.kt` + 其测试

**测试**（220 app + 350 agent-runtime 全绿；okia/store 回归绿）：
- `ConversationRepoTest` 重写（存储往返逐字段/fork 截断标题/幂等/leafId fallback/元信息）
- `ConversationFormatterTest` 重写（User 分组/工具成败/Thinking 忽略/孤儿 ToolCall 占位/projectLeaf/preview）
- `ConversationPersisterTest` 新（增量写/幂等/会话隔离/恢复不重插/错误回合 partial/元信息/重置）
- `HomeChatControllerTest` 更新（fakes 换接口 + fork/regen 端到端 + startupRestore/loadConversation/stop 时序）
- `LLMControllerOkiaTest` 更新（ensureSession/openSession restore/conversationFlow/resetConversation 弃实例）

**Robolectric 坑（实测）**：`context.getString(R.string.xxx)` 在 Robolectric 4.13 + AGP 9.1 下对**任意**资源 ID 报 `Bad identifier`（0x7f10xxxx 的 type 段不识别；T1-T2 测试从不 getString 所以未暴露；真机正常）。修复 = `ConversationRepo.init` 里 `runCatching { getString }.getOrDefault(硬编码)`。后续新增 getString 调用注意此坑。

**验收映射**（docs/T3.md §5）：
1. 存储往返 → RepoTest.insertEntries_roundTripsMessageTreeExactly ✅
2. bug 回归（错误回合最后一条不恢复）→ PersisterTest.errorTurn_partialAssistantIsPersisted + Manual ✅
3. 切会话丢数据 → 消息级增量天然覆盖 + HomeChatViewModelTest.loadConversation_stopsThenOpensSnapshot（stop 时序）✅
4. fork/regenerate → RepoTest.fork* + ViewModel fork/regen 端到端 ✅
5. Formatter OKIA 树渲染 → FormatterTest ✅
6. 桥接删除 + 无 kai import → grep app/src agent-runtime/src 为空（HomeChatTurn 除外）✅

**遗留（T4）**：`:libs:kai` 依赖仍在 agent-runtime/build.gradle.kts（代码零引用，仅注释提及）；`stopCurrentRound(keepCurrentTurn)` 参数；idle/beforeStop/可重试 UI 收口；app schemas/2.json 需入库。

## T1 功能定义

**一句话**：把 Nexus 的"提问 → 流式回答 → 停止 → 错误"骨架从 KAI 换到 OKIA，UI 消费端接口不变，历史/工具/持久化允许退化。

改动文件（估算合 ~1000 行）：
- `agent-runtime/build.gradle.kts`：+`implementation(project(":libs:okia"))`（保留 kai 依赖到 T4 删，或 T1 直接删？见开放问题 O1）
- `LLMController.kt`（重写 ~330 行）：
  - `Okia.open(protocol, restore, builder)` 按 `LlmApiType` → 协议实例（OpenAIChatCompletion / AnthropicMessages；DeepSeek 也走 OpenAI-Channel compat）
  - 实例管理：`obtainSession(apiType)` 等价物（apiType 变化 → close+重建）；replaceHistory/resetConversation 语义 → 重建实例（D4 变体：空 restore 或导出的子树）
  - `send(text, TurnOptions(systemPrompt = <per-turn 拼好的 final prompt>), onEvent)`；onEvent 经 `channelFlow` 包回 `Flow<LlmStreamEvent>`
  - 终态：`TurnResult.Completed/Failed/Aborted/IdleTimeout` → 流关闭语义
  - stop：`stop()`（beforeStop 接管 kill 在 T4，T1 先保留原位 kill 或直接接 beforeStop？见 O2）
- `LlmStreamEventMapper.kt`（重写 ~200 行）：`TurnEvent` → `LlmStreamEvent`；错误事件携带 code；终态事件映射为流结束
- `HomeChatState.kt`（小改 ~50 行）：getHistory/replaceHistory 的适配（Message ↔ ChatTurn 投影 / 重建）+ 错误 code 映射
- 测试（~450 行）：见下方测试边界

## T1 测试边界（怎么证明没问题）

**测试原则**：
- 纯函数（Mapper）穷举事件映射，不依赖任何 IO
- LLMController 装配用 OKIA 的 `open(dependencies)` 注入点（fake AgentLoop / fake ProtocolCompatMapper / fake McpClient，参考 `libs/okia/src/test/.../fake/Fakes.kt`），不回放真实网络
- 并发/停止/终态用注入的 TestDispatcher 控制时序
- 端到端真实链路（真机/真实 key）只做手动验收，不进单测

**边界矩阵（每个边界测什么、保证什么）**：
1. `Mapper：TurnEvent → LlmStreamEvent 一一映射`——TextStarted/Delta/Ended 累积成 fullText；ToolRunning/Succeeded/Failed 事件透传；TurnStarted/RoundStarted；不认识的终态事件不产生重复完成事件。保证：UI 渲染与事件序正确（不变量：TextEnded 的 content 与累积一致）
2. `Mapper：错误分类`——TurnFailed(message, error: LLMError) 的 code（Auth/Quota/RateLimit/Overloaded/Transport/Parse/RetryExhausted/HookFailed/UnknownTool/ToolExecutionFailed...）→ LlmErrorCode 结构映射；message 空时 fallback 默认文案。保证：app 的 toAssistantErrorUi 分支不回归
3. `终态语义`——TurnResult.Completed → 流正常关闭且最后一条被消费；Failed → 流内已发条目不丢（partial 保留）+ 流关闭；Aborted(UserStop) → 流关闭无错误事件；IdleTimeout → 流关闭。保证：两个消费端不依赖"完成事件的发射顺序"（OKIA 终态在返回值）
4. `并发`——活跃回合中第二次 send → OKIA 抛并发异常 → adapter 捕获 → 产出 TurnConflict 错误事件且不回环卡死。保证：UI 的"一个回合进行中再发消息"提示回归不丢
5. `装配`——apiType=DeepSeek/Anthropic/OpenAI → 对应协议实例；builder 传入 endpoint/apiKey/model 正确；TurnOptions.systemPrompt 进 RequestSnapshot（fake mapper 断言请求字段）。保证：配置与协议选择正确
6. `stop`——stop() 后流终止、无卡死；beforeStop（若 T1 接入）先于取消执行。保证：终止键不挂起
7. `重建实例`——replaceHistory/resetConversation → 旧实例 closed、新实例可 send。保证：会话切换不泄漏

**手动验收步骤**（真机，每次 T 完成）：
1. 语音助手宿主问答一轮（注入 LLM 回答呈现）
2. 主 App 问答一轮、终止键、配置错误（空 endpoint）→"请先填写配置"
3. 断网 → 错误事件文案出现

## 开放问题

| # | 问题 | 倾向 |
|---|---|---|
| O1 | `:libs:kai` 依赖删除 | T4 删；T2 期间 agent-runtime 的 kai 引用集中在 BuiltinTool(LocalToolConfig) 与 ChatTurn 桥接，随 D19 迁移与 T3 移除 |
| O3 | `TurnOptions.systemPrompt` 每回合拼装：PromptComposer 每轮 refresh 已产出 finalSystemPrompt，LLMController 持有 snapshot.config.finalSystemPrompt → 直接传入，无额外成本 | 已落地（D2） |
| O4 | 待提 issue 批（记入 D3 系）：① OKIA 无 send 前 ensure 的 MCP 同步发现 API；② McpDiscoveredTool 无可序列化（缓存持久化前置）；③ ToolRegistry 注释「活跃回合不得直接变更」与 update 路由矛盾（文档修复） | T2b 后一并提 |

## 验证

```bash
# 指定模块单测（T1 主要是 agent-runtime）
./gradlew :agent-runtime:testDebugUnitTest
# 主 App
./gradlew :app:testDebugUnitTest
# okia 库自身（不应有改动，回归确认）
./gradlew :libs:okia:testDebugUnitTest
# 真机手动：安装 debug 包 → 详情见 T1 手动验收
```

## T2a 功能定义（待开始）

**目标**：让内置/自定义工具真正注册进 OKIA 注册表并可执行，模型调用工具不再触发 UnknownTool。

改动文件（估算合 ~1000 行）：
- `BuiltinTool.kt`（抽象改造 ~50）：删 `configure(LocalToolConfig)`，改为 OKIA 声明式描述（description/inputSchemaJson + 轻量参数声明）；`LocalToolConfig`/kai 依赖从 builtin 包消失
- 15 个 builtin 工具迁移（~250）：configure 逻辑 → 新描述（**文本原文照抄，D19**）；CreateCustomTool 改默认 `enabled=true`（D20）
- 新增 Registry 装配（~100）：refresh 后按 `filter { it.enabled }` 注册 enabled 工具到持有的 DefaultToolRegistry（经 `OkiaConfig.toolRegistry` 注入）；移除旧工具的注销
- 新增 ToolExecutor 适配（~120）：`ToolCallDispatcher` 包成 `ToolExecutor`（execute: ToolCallContext→outcome；onInterrupt: 本地→Interrupted）；BuiltinToolResult/CustomToolResult → Success(content=json)/Failure(message=code+message, content=json)；CreateCustomTool 成功→回合内 register 回调（D20）
- `LLMController.kt` 注册/注销钩子（~40）
- 测试（~400）：D25 三档（注册名集合/描述合法性/执行行为）+ CreateCustomTool 回合内注册专项

**验收**：内置工具（memory/search_apps/python/terminal 任一）回合成功呈现；自定义工具回合成功；模型调用已注册工具不再 UnknownTool；工具失败 UI 显示 code/message；

## T2a 测试边界（D25）

1. 注册装配：refresh 后 registry.snapshot() 的工具名集合 == 启用的 builtin+custom 名（名字级）
2. 描述合法性：每个注册工具 schema JSON 可解析、name 合法、wireName 符合 ToolWireName 约束（长度/字符）
3. 执行行为：fake ToolCallContext → outcome（Success 携 content / Failure 携 message+content / 中断→Interrupted / 未注册名→UnknownTool 回合失败）；CreateCustomTool 执行成功 → registry 新增工具 + 下一段可见（用 OKIA TestDispatcher 时序）

## T2a 实现记录（2026-08-19，已完成，待验收）

**改动文件**（27 文件，+796/-585 行）：
- `BuiltinTool.kt`：删 `configure(LocalToolConfig)`（kai DSL），加 `open val inputSchemaJson: String?`（JSON Schema 常量）
- 14 个 builtin 工具迁移：`configure` → `override val inputSchemaJson`；文本一字未改（D19）；ScreenOperationAccessibility/Shell 原纯 kai DSL，转录为 JSON Schema（描述原文照抄）；CreateCustomTool schema + invoke 解析默认 `enabled=true`（D20）
- `LocalToolExecutor.kt`（新，182 行）：OKIA ToolExecutor 适配（execute→outcome、onInterrupt→Interrupted）；BuiltinToolResult/CustomTool JSON 按 `ok` 拆 Success/Failure；文本协议经 TextToolResultCodec；create_custom_tool 成功且 enabled → inline 注册 + 回调 host（D20 回合内注册）
- `ToolCallDispatcher.kt`（删，被 LocalToolExecutor 取代）
- `LLMController.kt`（+76）：`toolRegistry: DefaultToolRegistry`（持有、注入 OkiaConfig）+ `localToolExecutor` + `syncLocalTools`（refresh 全量重建 local 注册，kind=Local filter）+ `registerCustomToolNow`（回合内注册回调）+ openOkiaWithDefaultProtocol builder 注入 toolRegistry
- 测试：`LocalToolExecutorTest`（新 271 行：builtin/custom/unknown/textProtocol/onInterrupt/create_custom_tool 注册×3）；`BuiltinToolTest`（+D25 描述合法性：name/schema 可解析/wireName 约束）；`LLMControllerOkiaTest`（+refresh 注册 enabled 名集合、schema/kind）；其余 6 个测试文件去 LocalToolConfig/configure 引用

**已删除 kai 引用**：agent-runtime main 无 `LocalToolConfig`/`configure` 残留；测试已清。`:libs:kai` 依赖仍存在（ChatTurn 桥接 O1-A，T3 移除）。

**测试结果**：`:agent-runtime:testDebugUnitTest` 34x 全绿、`:app:testDebugUnitTest` 全绿、`:app:compileDebugKotlin` 通过；`:libs:okia` 未改动。

**踩坑记录**：`ShellCommandSafetyPolicy` 默认 `awaitSettingsGateway()` 挂起，测试未装 gateway 会挂死 → LocalToolExecutorTest 注入 `ShellCommandSafetyPolicy(listExecutionRules = { emptyList() })`（allowed 短路）。

## T2b 实现记录（2026-08-19，已完成，待验收）

**改动文件**（增 ~420 / 删 ~380，净 ~40，含测试）：
- `libs/okia`（D-T2B-4）：`OkHttpEngine` internal→public（构造接受 `OkHttpClient`，proxy/interceptor 注入点）；okhttp `implementation→api`（公开签名暴露）；`OkHttpEngineTest` +1 例（注入 client 经 interceptor 加头生效）
- `LLMController.kt`（+~130）：`toOkiaMcpServers`（McpServerDefinition.Http→okia McpServer 字段一一对应）；`update { mcpServers }`；`scheduleMcpRefresh`（后台 `mcpRefreshScope` = SupervisorJob+IO，**不 await**；签名变化才刷；inFlight 防重；成功/失败都更新签名防风暴）；`mcpServersSignature`（name/url/headers/enabled 确定性序列化）；删 `gateway.listCachedTools` + `mcpCachedTools` 关联；resetForTest 重置 MCP 状态。**踩坑**：lambda 内 `mcpServers` 被外层局部 `val mcpServers`（RuntimeMcpServer 列表）遮蔽 → 外层改名 `runtimeMcpServers`（D62 同类）
- `PromptComposer.kt`（-80）：删 `renderMcpServers`/`renderMcpStatus`/`PromptComposerInput.mcpDiscoverySnapshot`/kai 三个 snapshot import（D-T2B-2）——**agent-runtime 的 PromptComposer 脱离 kai**
- `ToolManager.kt`（-30）：删 `mcpCachedTools` 参数/`toCachedTool`/`McpCachedTool`
- `LlmModels.kt`（-25）：删 `McpCachedTool`/`mcpCacheKey`（无调用者）/`McpServerDefinition.cachedTools`
- `RuntimeSettingsGateway.kt`（-15）：删 `listCachedTools`/`saveDiscoveredTools`/`clearMcpCacheByServerNames`/`fingerprintMcpServers` 接口方法
- `RuntimeSettingsModels.kt`：删 `RuntimeMcpTool`
- app：`XRepo.kt` McpApi 删缓存 6 方法 + 2 私有 helper；`XRepoRuntimeGateway` 删 4 override；`McpSettingsCodec` 删 parseCache/encodeCache；`LocalSettingsCodec` 删 parseMcpCache/withMcpCache/withoutMcpCache + 死代码（asJsonObjectOrEmpty/mcpCacheKey/MCP_CACHE_KEY）；`SettingModels` 删 mcpDiscoveredToolsCache
- `store/StoreDescriptorRegistry.kt`：删 `mcpCacheStoreId`/`MCP_CACHE_PREFIX`/resolveDynamic 缓存分支
- 测试：新增 `LLMControllerMcpTest`（5 例：首次 eager 触发/签名未变不刷/配置变化重刷/失败不风暴/装配映射）；更新 ToolManagerTest/PromptComposerTest；删缓存用例（XRepoTest/XRepoDomainSettingsTest/LocalSettingsCodecTest/SettingsDomainCodecsTest/StoreDescriptorRegistryTest/RuntimeSettingsTestFakes/两个 gateway 测试）

**验证**：`:libs:okia`/`:store`/`:agent-runtime`/`:app` testDebugUnitTest 全绿（1078 测试）；MCP 测试用 fake RecordingMcpClient（无真实网络）；`refresh_mcpDiscoveryFailureStillUpdatesSignatureNoStorm` 验证失败后不重试

**手动验收环境**：server-everything @ 3001 已在跑（`curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:3001/mcp` → 400 即在线）+ `adb reverse tcp:3001 tcp:3001`（手机连电脑）

## T2c 实现记录（2026-08-19，已完成，待验收）

**背景**：T2 验收暴露——模型调用未注册工具时 OKIA 将其视为回合级异常（Failed(UnknownTool)），旧版 Nexus（KAI ToolCallCoordinator）与 OKIA 内部 MCP 路径（server not found → Failure 回喂）均为「错误结果回喂，loop 照常进行」。差异实证见前轮讨论。

**改动**（±72/-22 行，4 文件）：
- `libs/okia/.../loop/RealAgentLoop.kt`：`Plan.holder` 改 nullable；find-miss 分支从 `failTurn` 改为构造 Failure outcome 的 Plan（不执行、不走 afterToolCall，Phase 3 保序提交回喂，回合继续）；类注释 / Phase 1 注释 / ToolExecutionOutcome 注释同步
- `libs/okia/.../error/LLMError.kt`：删 `UnknownTool(false)`（不可达）+ 类注释更新（unknownTool 永不重试说明移除，注明未知工具走 Failure 回喂）
- `agent-runtime/.../LLMController.kt`：类注释（D17 里 UnknownTool 失败描述 → 回喂）仅文档更新，无逻辑改动
- `libs/okia/.../loop/RealAgentLoopToolLoopTest.kt`：`unknownToolFailsTurn` 重写为 `unknownToolFeedsBackFailureAndContinues`——断言 TurnResult.Completed、无 TurnFailed 事件、未执行真实工具、ToolFailed 事件携带纯文本 `Unknown tool 'missing'`（message==content）、三类 commit（Assistant/含 ToolCall → ToolResult 回喂 → 第二轮空 Assistant）、第二轮请求历史以该 ToolResult 结尾

**死代码扫描**（全仓）：UnknownTool 引用仅剩 `app/src/test/.../XRepoTest.kt:481 builtinSetEnabled_rejectsUnknownTool`（XRepo 设置枚举，同名函数无关，不动）

**验证**：`:libs:okia:testDebugUnitTest` 全量绿（含重写用例）；`:agent-runtime:testDebugUnitTest` + `:app:compileDebugKotlin` 绿。Nexus 侧零行为改动（UI 走既有 ToolFailed 渲染）

## T1 实现记录（2026-08-19，已完成，待验收）

**改动文件**（主代码 ±579 行、测试 +515 行、删 ~100 行）：
- `agent-runtime/build.gradle.kts`：+`:libs:okia`（`:libs:kai` 保留至 T4，O1-A）
- `LLMController.kt`（417→561 行）重写：Kai→Okia 门面；`okiaFactory` 内部注入点（测试用 `Okia.open(dependencies)` 装配 fake loop/mapper）；`TurnOptions(systemPrompt)` 每回合传；终态 `TurnResult` 判定；并发/closed 契约违例 → `Error(TurnConflict)`；`beforeStop` 迁走 kill（PyRuntime.kill + TerminalSessionPool.closeAll）；`resetForTest()`
- `LlmStreamEventMapper.kt`（118→119 行）重写：`TurnEvent`→`LlmStreamEvent` 全事件映射（Thinking/ToolCall*/Retry/Aborted/TextStarted/Ended → null）；终态只透传 Completed/Failed/IdleTimeout，Aborted 不产错事件
- **删除**：`SessionToolBinder.kt`、`McpDiscoveryCacheStore.kt` + 其测试（kai 绑定，T2 用 OKIA 形态重写）
- 测试：`LlmStreamEventMapperTest` 重写（206 行，穷举事件映射）；新增 `LLMControllerOkiaTest`（309 行：装配/终态/并发/历史桥接/重建）

**桥接保留（O1-A，T3 移除）**：`getHistory`（OKIA 树 → `List<ChatTurn>` 投影）、`replaceHistory`（`close + open(restore=由 ChatTurn 构造的 snapshot)`，leafId 显式=最后一条，D16 绕坑）、`resetConversation`（重建空实例）、`stopCurrentRound(keepCurrentTurn)`（参数 no-op，T4 清理）

**测试结果**：`:agent-runtime:testDebugUnitTest` 全绿（--rerun-tasks）；`:app:compileDebugKotlin` 通过（HomeChatState 零改动）；app 的 HomeChatController/ConversationFormatter/ChatTurnJsonCodec/ConversationRepo 测试通过。`LLMControllerRefreshSkillTest` 行为不变。

**遗留**：`LLMControllerRefreshSkillTest.stream_reusesPreviousSnapshotWhenSkillListFails` 会真实连 example.com（既有行为，1s 超时兜底，仅慢不挂）。

## 调研记录（2026-08-19，避免回退后重查代码）

### Nexus ↔ KAI 交接现状（已核实）

- 消费入口：`AgentRuntimeService.executeTurn`（宿主）+ `HomeChatState`（主 App），都经 `LLMController` 单例（agent-runtime/.../chat/LLMController.kt，417 行）
- 装配：`refresh()` 每轮重读配置/工具 → `obtainSession(apiType)`（apiType 变化 close+重建）→ `update { applyRuntimeConfig }` 热更新 endpoint/apiKey/model/systemPrompt/tools
- 协议：`Kai.open<KClass>`：Anthropic/DeepSeek/OpenAI
- 工具：`SessionToolBinder`（KaiConfig DSL localTools/mcp 全量差量）+ `hooks { when(kind) Local -> ok(...) ; Mcp -> delegate() }` + `ToolCallDispatcher` → BuiltinToolExecutor/CustomToolExecutor
- MCP：`mcpHooks.onToolsDiscovered` + Nexus 侧 `lastMcpServersFingerprint` + shouldRefreshMcp + `McpDiscoveryCacheStore`（cachedTools 经 XRepoRuntimeGateway 持久化）
- 事件：`KaiEvent`(RoundStarted/TextDelta/ToolRunning/Succeeded/Failed/Error/RoundCompleted) → `LlmStreamEventMapper` → `LlmStreamEvent` → RenderFrame（宿主）/ HomeChatBlock（主 App）
- 停止：`PyRuntime.kill()` + `TerminalSessionPool.closeAll()` + `kai.stop(keepCurrentTurn)`（kill-then-stop 是 KAI 缺陷 workaround）
- 历史：`getHistory()/replaceHistory()`（ChatTurn 平列表）+ `resetConversation()`；主 App 用 Room（conversation + conversation_turn 两表，payload_json=ChatTurnJsonCodec）
- 错误：`LlmErrorCode` 仅 ConfigRequired/TurnConflict；`toAssistantErrorUi` 三个分支，无重试维度
- 延迟消息：`TerminalSessionPool.drainPendingNotifications()` 拼进 effectiveQuery 前缀（LLMController:241）
- 锁：`AgentRuntimeService.activeTurn`（Binder CAS）+ `LLMController.turnMutex.tryLock`（→TurnConflict）

### OKIA 关键契约（已核实源码）

- 门面：`Okia.open(protocol, restore, builder)` / `open(restore, builder)` / `open(dependencies, restore, builder)`（测试注入点）
- send：`suspend fun send(text, options: TurnOptions? = null, onEvent: suspend (TurnEvent) -> Unit): TurnResult`（**callback，非 Flow**；终态唯一权威 = 返回值）
- 流：`conversation: StateFlow<Conversation>`（history 投影 + live）+ `events: SharedFlow<TurnEvent>`（replay=0）
- 并发：活跃回合中 send/rewind/update/export/refreshMcpTools/close 抛异常；`stop()` 唯一例外
- TurnResult：Completed(reason: Stop/Length) / Failed(error: LLMError) / Aborted(cause: UserStop/External) / IdleTimeout
- TurnEvent：TurnStarted / TextStarted/Delta/Ended（partial）/ ThinkingStarted/Delta/Ended / ToolCallStarted/Delta/Ready / ToolRunning/Succeeded/Failed / RetryScheduled / TurnCompleted/Failed/Aborted/IdleTimeout
- 工具：ToolRegistry（注册）+ ToolExecutor（execute/onInterrupt）+ Hooks.before/afterToolCall（mutation holder，writeOutcome 短路）；ToolCallOutcome 5 态：Success/Failure/Intercepted(isError)/Interrupted/Unknown（onInterrupt 当前库 main 代码无调用者）
- Hooks：beforeInput/afterInput（不进树）、beforeSerialization/afterSerialization（脱敏）、beforeRequest/afterRequest（只读）、beforeStop/afterStop（kill-then-stop）
- 重试：传输层 config.retryPolicy（Retry-After+退避 jitter，默认开）+ 回合层 LoopOptions.turnRetryPolicy
- idle：idleTimeoutSeconds 默认 null；agent 事件活跃度（thinking 不误杀、keep-alive 不重置）
- MCP：OkiaConfig.mcpServers → AutoDetectMcpClient → McpDiscovery 状态机（Idle→Discovering→Available/Failed/UsingStaleCache）注册进 registry；无同步 ensure API（D3）
- 持久化：export(): SessionSnapshot（树+leafId）/ open(restore)；Message 全 @Serializable；RealConversation 构造只校验重复 id、悬挂 leafId（不校验 parentId）
- 错误码：Auth/Quota/RateLimit/Overloaded/Transport/Parse/RetryExhausted/UnknownTool/HookFailed/ToolExecutionFailed（可重试子集见源码 Compat.retryableStatusCodes）
- systemPrompt：唯一入口 TurnOptions（config 无该字段）
## 双份消息 bug（2026-08-23，已修复）

**现象**：运行时最终回答显示双份（"！有什么我可以帮你的吗？你好！有什么我可以帮你的吗？"）；冷启动恢复正常（走 ConversationFormatter 全量渲染，不经事件流）。

**根因**：OKIA 把每个文本块的首个 delta 发在 `TurnEvent.TextStarted`（不携带增量文本，内容只在 partial）；`LlmStreamEventMapper` 丢弃 TextStarted → UI 逐 delta 累积缺首 delta → `appendFinalText` 的 `removePrefix(displayedText)` 失败 → 全量追加 → 双份。T1 起就存在，T3 验收才暴露。

**修复（34bd1bb 根因 + cc01488 清理）**：
- `LlmStreamEventMapper` 状态化：`accumulatedText` 基线，TextStarted 发全量 delta、TextDelta 发增量（partial − 累积）；TextEnded/TurnCompleted/TurnFailed/TurnAborted/TurnStarted 重置。宿主 FullText/Chunk projector 同一 delta 流同步受益。
- 不保留 UI 防御分支（用户裁定：workaround，掩盖未来问题；违背明确失败优于自动修复）。`appendFinalText` 保持简单形式。
- 测试：Mapper 增量序列（TextStarted 全量 + TextDelta 增量累积 == fullText）/ 跨块重置 / 跨回合重置；`TextDelta maps with delta` 改为真实序列（先 TextStarted 建基线）。

**教训**：T1 Mapper 注释声称"TextDelta 已携带累积 partial 文本，逐 delta 追加即得完整结果"——断言了 delta 序列完整性，但未验证首 delta 的去向。根因修复在数据源（delta 序列），不在消费端打补丁。
