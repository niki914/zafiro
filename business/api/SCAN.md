# 消费侧契约：扫描结论

本目录的类型由一次全仓脚本扫描得出，不是照抄现有类型。
扫描脚本：`/tmp/zafiro-scan/scan.py`（覆盖 `app`、`agent-runtime`、`xposed-*`、
`ui-kit`、`store`、`libs:okia` 的 main 源码）。删除项的逐条核对在第三节，
逐条都经过 `grep` 确认。

本文记录扫描出的消费方与结论，供评审核对。

## 一、LLMController 的生产调用面 → `Agent` 命令

`Agent` 继承 `AgentControl`。窄命令在 `AgentControl` 上，宽命令直接在 `Agent` 上。

| 调用方 | LLMController 成员 | 契约中的去处 |
|---|---|---|
| `HomeChatViewModel`（Compose） | `stream` | `Agent.stream()` |
| | `stopCurrentRound` | `AgentControl.stop()` |
| | `resetConversation` | `Agent.discard()` |
| | `ensureSession` | 实现内部（首次 `stream()` 时建档） |
| | `openSession(restore)` | `Agent.load(id)`（浸出 Room 的一步由 app 侧适配器完成） |
| | `historySnapshot` | fork 三件套定位用（见第四节 1，本轮不进契约） |
| | `ingestUserImage` | `DraftImage.Pending` → 实现侧归约成 `Ready` |
| | `currentConversation` | 流式归约的输入（见第四节 6，本轮折叠不动） |
| | `keepScreenOn` | `AgentControl.status.phase != Idle` |
| `MainActivity` | `keepScreenOn` | 同上 |
| `ConversationPersister` | `currentConversation` | 不经契约，本轮不动（见第四节 2） |
| `AgentRuntimeService` | `stream` / `stopCurrentRound` / `resetConversation` | `Agent.stream()` / `AgentControl.stop()` / `Agent.discard()`（宿主经 Binder；宿主本轮不动，见第四节 3） |
| 工具确认（Compose / overlay / 通知） | `ToolPermissionCoordinator.confirm` | `Approver.decide`（按注册顺序询问，弃权交给下一个） |
| `AgentStatusHolder`（实现侧） | `currentConversation` / `stream` 的回合边界 | `AgentControl.status` 的投影来源 |

无生产调用方的成员（只在单测里出现）：`refresh`、`refreshFromHookContext`、
`snapshot`、`toolRegistry`、`okia`、`okiaFactory`、`resetForTest`。这些随
`ResolvedLlmConfig`、`ResolvedTools`、`LlmRuntimeSnapshot` 一起留在实现侧。

## 二、各消费方依赖的类型

| 消费方 | 现在依赖 | 契约中读到什么 |
|---|---|---|
| `HomeChatState`（ViewModel） | `LlmStreamEvent`、`LlmErrorCode`、`ToolCallStatus`、okia `Conversation` / `Message` / `SessionSnapshot` / `ContentBlock`、`HomeChat*` | `Agent.conversation` / `Agent.draft` / `AgentControl.status` |
| `HomePageContent` / `HomeChatComponents`（Compose UI） | 只有 `HomeChat*` 与 `LlmErrorCode` | 不触执行侧；继续读 ViewModel 产出的 UI 状态 |
| `ConversationFormatter`（历史恢复） | okia `SessionSnapshot` / `MessageEntry` / `AssistantMessage` / `ContentBlock` / `ToolCallOutcome` + `HomeChat*` | 将来产出 `ConversationTurn` / `TurnBlock`（本轮不动） |
| `ConversationRepo` / `ConversationPersister`（持久化） | okia `Conversation` / `ConversationEntry` / `SessionSnapshot`、Room 实体 | 不读契约：继续现在的操作，本轮不动（见第四节 2） |
| `ConversationHistoryPageContent`（列表） | `ConversationSummary`（app 自己的类型） | 本轮无契约成员；列表页本轮不进契约 |
| `AgentRuntimeService` + `LlmStreamCollectors`（宿主渲染） | `LlmStreamEvent`、`LlmErrorCode`、`ToolCallStatus` | 渲染帧只含文本与工具行，**不消费图片**；切换到状态投影是后期的活（见第四节 3） |
| `AgentStatusHolder`（实现侧） | okia `Conversation` / `MessageEntry` | `AgentControl.status`（`phase` / `preview` / `outcome`）；生产代码暂无读者 |
| `MainActivity` | 仅 `keepScreenOn` | 派生自 `AgentControl.status.phase` |
| 常驻通知 / 悬浮窗（待做） | — | `AgentControl.status`；允许 / 拒绝经注册 `Approver` |

## 二半、接口划分（按客户端实际需要）

| 档位 | 接口 | 消费方 |
|---|---|---|
| 窄 | `AgentControl`：`status` / `stop` / `addApprover` / `removeApprover` | 常驻通知、悬浮窗、MainActivity |
| 宽 | `Agent`（继承 `AgentControl`）：+ `conversation` / `draft` / `updateDraft` / `clearDraft` / `stream` / `load` / `discard` | Compose、宿主 |
| 消费方实现 | `Approver`：`decide(request)` | Compose 对话框、overlay 弹窗、通知按钮 |

`Agent` 与 `AgentControl` 是同一个对象登记的两个接口键（按精确类型查找的
注册表，组合根登记两次）。

## 三、扫描得出的删除项

逐条已核对：

| 现有字段 / 类型 | 删除理由 |
|---|---|
| `LlmStreamEvent`（11 个事件） | 事件是第二条输出通道（谁发起谁收），退出契约；折叠收进实现侧做一次（本轮不动） |
| `ToolCallStatus.kind`（`ToolCallKind`） | 生产代码只写 `Unknown`（`LlmStreamEventMapper.kt:276`），没有读取方 |
| `HomeToolStatus.state` | 可由 `TurnBlock.Tool.outcome == null` 与 `invocation.argumentsJson == null` 派生 |
| `HomeChatImage.id` | 消费方派生值；现有实现两套口径（`path.hashCode()` / UUID）。契约侧按 `Pending.uri` / `Ready.attachment.path` 去重与移除，不另设 id |
| `AgentStatus.statusText` / `title` / `logLine` | 中文文案属于消费方 |
| `keepScreenOn` 通道 | 派生自 `status.phase` |
| `TextDelta.charsPerSecond` | 只进日志（首帧速率）与单测 |
| `LlmStreamEvent.Error.throwable` | 异常对象不进契约 |
| `ToolCallStatus.callId` 的可空性 | 实现侧生成 id 后删掉无名匹配兜底（`findToolBlockIndex` 第二条分支） |
| `ConversationSummary.titleEdited` / `turnCount` / `createdAt` | 生产代码只写不读 |
| `IngestedImage` | 单字段包装（`ImageIO.kt:36`），并入 `Attachment` |
| 草稿的通用 `Part` 列表 | 输入框的形状是「一段文本 + N 张图」，列表表达不了这个不变量 |
| `attachImage(uri)` | 草稿的写入口收敛到 `updateDraft` 一处；异步落盘用 `DraftImage.Pending` 表达 |
| `respondApproval(id, verdict)` | 授权改为裁决者注册（`Approver`），多个来源按顺序参与、可弃权 |

契约侧本轮未纳入的字段（无生产消费方，属有意取舍）：

| 字段 / 类型 | 不纳入的理由 |
|---|---|
| `ConversationTurn.isComplete` | 落盘不读契约。落盘口径改成本类型时再补提交边界 |
| `ConversationSummary` | 没有契约成员返回它；列表页本轮继续读 `ConversationRepo` 自己的同名类型 |

## 四、不在本期范围（需要决策，改动前先定）

1. **消息派生操作（regenerate / fork / rewind）**：现在由 `HomeChatViewModel`
   自己算用户消息下标（`findUserTurnIndex` / `findNextUserIndex`），再调
   `ConversationRepo.forkConversation`。这段下标算术属业务，但落点未定（进 `Agent`
   命令，还是留给会话仓储）。未进本轮 `Agent`。FIXME，先留。
2. **会话内容的持久化观察**：`ConversationPersister` 继续观察引擎的会话树，
   Room schema 与存量数据不变。契约的 `conversation` 不参与落盘，因此不承载
   提交边界，也不为落盘预留字段。本轮不动。
3. **宿主**：`AgentRuntimeService` 与 `LlmStreamCollectors` 本轮不动。
   宿主的一次性 query 与草稿的关系未定，本轮不碰。
4. **草稿的持久化**：仍由 app 侧按按键节流写 Room 的 `draft_text` 列，
   草稿图片不进 Room。契约的 `draft` 只描述输入框内容。
5. **授权多来源的命中策略**：优先级询问 + 弃权（本契约按此设计），还是并发询问
   + 先到先得。
6. **事件折叠与节流**：`applyEvent` 与 `TextPacer` 本轮留在 ViewModel。
   折叠进实现侧时，`conversation` 的发射频率由实现决定（打字机靠「最后一轮的
   文本块在变」，不是「整轮结束才发一次」）。
7. **通知文案的归属**：`FAILED_REASON_INTERRUPTED` 这类失败文案进
   `ToolOutcome.Failed.message` 后的多语言归属未定，本轮不动。
