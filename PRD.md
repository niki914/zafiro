# PRD：搜索工具 / 记忆内容 / 权限拦截 / 工具上下文

分支：`wip/tool-memory-permission`（基于 origin/main）

四个独立任务，可分批实施，互不阻塞。

---

## 1. Web Search 多引擎（DuckDuckGo / 百度 / 搜狗）

### 现状
- 默认工具 `py_web_search` 在 `XRepo.kt` 种子化（`CODE_WEB_SEARCH`，DuckDuckGo HTML endpoint，requests + BeautifulSoup，输出 `{title, url, snippet}`）。
- 自定义 Py 工具的载体是 `CustomPyTool`（name / description / schemaJson / code），每个工具一个 code blob。

### 方案（已定）
- 保持**单个 `py_web_search` 工具**，schema 增加 `engine` 枚举参数：`ddg`（默认）/ `baidu` / `sogou`。
- 理由：三个引擎输出结构相同（{title, url, snippet}），单工具 + 枚举让模型一次学会；拆成三个工具只会膨胀工具列表、增加选择成本。
- 代码组织：code blob 内部按 engine 分发到三个解析函数；description 列出引擎枚举。

### 附带需求：Agent 自更新搜索脚本
- DDG HTML endpoint 等 workaround 脚本会随站点改版失效。Agent 本身具备网络能力（requests），可通过既有 py_meta_tools 写工具**自行改写 `py_web_search` 的 code** 来修复失效的解析逻辑。
- 记忆条目见 §2，需验证：py_meta_tools 能否覆盖已存在的同名 CustomPyTool（调研点）。

---

## 2. 记忆内容

### 现状（调研结论）
- 默认记忆 4 条在 `LocalSettingsDefaults.defaultMemories`（中文），onboarding 时种子化写入 `agent.main.memory` store。
- Agent 侧记忆能力已齐备：`memory` builtin（add/replace/remove）+ `py_meta_tools`（list/read/write/delete/test）。
- **py_meta_tools 自修复闭环完整**：`read` 拿到工具全文 → `test` 免保存试跑草稿 code → `write` 覆盖替换（签名反射校验，错误信息可直接修复）。Agent 还能通过 requests 调任意外部 API——自定义 Py 工具本质上就是编程能力。
- 记忆注入：`buildMemoryItems` 把每条记忆注入每轮 system prompt，所以条目必须短、高信号。

### 修改方案
1. 删除：「如果用户需要备份设置…」与「不要随意修改 settings 内容…」两条。后者与新能力冲突——Agent 正是要通过 py_meta_tools 改写工具（走 settings gateway 写 tools_py store）。
2. 全部默认记忆改为**英文**（用户明确要求；模型对英文指令的遵循也更稳）。
3. 新增一条，覆盖「工具自维护」能力，要点：
   - 自定义 Py 工具损坏/失效时可自修复：py_meta_tools read → test → write。
   - 工具可自由调用外部 HTTP API（requests 已可用），即 Agent 具备编程扩展能力。
   - 已知失效场景：web search 各引擎解析器随站点改版失效，应先 read 诊断再改写。

### 草稿（英文，供实施时直接用）
- "Zafiro's settings root is /data/user/0/com.niki914.zafiro/files/settings/."
- "Zafiro's package is com.niki914.zafiro; GitHub repo: https://github.com/niki914/zafiro."
- "Custom Python tools are programmable and self-repairable: a broken tool (e.g. a web-search parser after an engine redesign) can be diagnosed with py_meta_tools read, fixed via test, and replaced via write; tools may call external HTTP APIs with requests."

### 注意
- `defaultMemories` 只在 onboarding 未完成时种子化。已完成的安装不会拿到新默认值——按仓库原则不加迁移，改完后需要清数据或手动 replace 验证。

---

## 3. 权限管理：执行规则拦截器（UI 确认对话框）

### 现状（已确认）
- `RuntimeExecutionRule`（agent-runtime `RuntimeSettingsModels.kt`）+ `ShellCommandSafetyPolicy.evaluate()`：规则命中 → 直接阻断（或 LOCKED_ONLY 模式下按锁状态放行）。`enabledMode` 取值：`ALWAYS` / `LOCKED_ONLY` / `DISABLED`。
- Okia hook 面：`Hooks.beforeToolCall(ToolCallHolder)`，`ToolCallHolder.writeOutcome(ToolCallOutcome)` 写入后短路执行（阻断机制现成）。
- 拒绝语义：`ToolCallOutcome.Intercepted(reason, isError = true)`，reason 会作为消息回给 Agent。

### 架构事实（调研结论）
- **无 IPC 问题**：agent loop（`LLMController`）运行在主 App 进程的 `AgentRuntimeService` 内，与 Compose UI 同进程。对话框 = runtime 暴露一个「待确认请求」的 StateFlow，UI collect 后渲染，用户点击后回调解除挂起。
- **宿主路径**：`RenderFrame` 是单向文本流（text/isFirst/isFinal），无回传通道。宿主发起的会话触发确认型规则时**默认拒绝**，`writeOutcome(Intercepted(reason="当前模式无法向用户请求权限", isError=true))`，Agent 收到错误消息。
- 区分来源：`LLMController.stream(query, context)` 的调用方可区分 UI 路径（`HomeChatState` 直连）与宿主路径（service Binder 入口），确认请求带来源标记。

### 方案
- 规则新增 `enabledMode = CONFIRM`（ASK）：命中后不阻断，挂起等待用户确认；UI 路径弹对话框，宿主路径直接拒绝。
- 注册一个 `Hooks` 实现（参照现有 `killToolResourcesHook` 的注册方式），在 `beforeToolCall` 里对 shell 命令跑 `ShellCommandSafetyPolicy.evaluate()`；`allowed=true` 放行，否则按模式阻断或发起确认。
- 超时策略：确认请求超时（数值待定）后默认拒绝，防 Agent 永久挂起。

### UI 设计（Liquid Dialog）
- 复用 `ui-kit` 的 `LiquidDialog` 容器，新建专用内容组件（现有 `ConfirmationLiquidDialog` 只有 title + 一段 text + 双按钮，信息量不够）。
- 内容必须包含，让用户不用猜：
  1. **AI 想干什么**：工具名 + 待执行命令（等宽字体块，完整展示 argumentsJson / 命令原文）。
  2. **为什么被拦**：命中规则名（如「危险删改」）+ 规则 reason（来自 `ShellCommandPolicyDecision.matchedRuleName` / `reason`），危险等级通过规则名 + 醒目样式传达。
  3. **明确的决策按钮**：允许（执行一次）/ 拒绝；拒绝时 reason 回传给 Agent。
- 待确认信息结构（建议）：`PendingToolConfirmation(toolName, argumentsJson, matchedRuleName, reason, source)`，source 决定是否可弹 UI。

### 调研点（已解决）
- ~~ToolCallHolder mutation 形态~~ → 未走 okia hook，拦截下沉到 `ShellCommandSafetyPolicy`（三个内置工具的唯一收口），零重复实现。
- ~~跨进程弹窗~~ → 不存在，同进程；宿主路径默认拒绝，已确认。
- ~~超时策略~~ → CONFIRM 永不超时（用户明确决策）。
- ~~后台通知~~ → 纯通知（无决策入口），复用 `XIpcBridge.postNotification`（Transport.Local），点通知回主 App。

### 实现落点（已完成）
- `RuntimeExecutionRuleEnabledMode` 新增 `CONFIRM`（codec 按枚举名序列化，自动兼容）。
- `ToolPermissionCoordinator`（agent-runtime）：`pendingConfirmation: StateFlow<ToolPermissionRequest?>` + `respond(id, allowed)`；`canRequestUserConfirmation` 由 `LLMController.stream(fromUserInterface)` 按来源设置（HomeChatState = true，service Binder = 默认 false）。
- `ShellCommandSafetyPolicy.evaluate(command, toolName)`：CONFIRM 命中 → 协调器挂起等待；允许后继续评估后续规则；拒绝 → `CONFIRM_DENIED`（"The user denied this operation."）；无确认渠道 → `CONFIRM_UNAVAILABLE`（"...cannot request permission from the user."），均英文。
- UI：`HomePageContent.ToolPermissionDialog()`，LiquidDialog 三段式（工具名 intro / 等宽命令块 / 命中规则红字），后台 dismiss = 拒绝；后台时发纯通知。
- 默认规则（LocalSettingsDefaults）：原三条（危险删改 / 卸载相关 / 高危提权）合并为单条「高危操作」id=builtin-dangerous，enabledMode = CONFIRM，patterns 合并三组；仅在首次 onboarding 种子生效，已装设备需手动改或重装。
- 生效时机选择 UI：SplitButton 改为 `SingleChoiceLiquidDialog`（协议/语言弹窗同款模板：`SettingsListItem` 值行 + 单选弹窗，选中即应用）。
- i18n：5 locale（values / en / es / ja / zh-Hant）新增 8 条文案 + `execution_rules_enabled_mode_confirm`。
- 测试：`ShellCommandSafetyPolicyTest` 新增 3 个 CONFIRM 用例（无渠道拒绝 / 用户拒绝 / 允许后仍被后续 ALWAYS 规则拦截）。

---

## 4. 工具上下文限制

### 需求
1. **max turns**：单次会话的工具循环轮数上限。
2. **工具输出上限**：单次工具调用结果的截断。

### 调研：pi 的实现（源码 /tmp/pi-src）

**工具输出截断（pi 有，且是每工具自截断）**
- `coding-agent/src/core/tools/truncate.ts`：`truncateHead / truncateTail(content, { maxLines, maxBytes })`。
- 默认值：`DEFAULT_MAX_LINES = 2000`，`DEFAULT_MAX_BYTES = 50KB`，两个限制先到先生效。
- 规则：永不返回不完整行（head 截断时首行超限则返回空 + `firstLineExceedsLimit`）；UTF-8 安全字节边界；`TruncationResult` 带 `truncated / truncatedBy / totalLines / totalBytes / outputLines`。
- 每个工具自己调 truncate（read 用 truncateHead、bash 用 truncateTail），截断时在输出里附一行 warning 提示模型（`[Truncated: showing N of M lines (limit)]`）。

**max turns（pi 实际未做硬上限）**
- agent 库有 `shouldStopAfterTurn` 回调（`agent-loop.ts:252`，每轮 turn_end 后检查，返回 true 则终止循环）——语义即「轮次上限」的挂点。
- 但 coding-agent 未接线任何 maxTurns 配置，靠 compaction 管理上下文长度。

**Okia 现状**：`LoopOptions` 无 maxTurns；无通用工具输出截断（`TerminalSessionPool.truncateOutput` 只覆盖 shell）。

### 方案结论：Hook 与 loop 分层

**工具输出截断 → 用 Hook，且是最优解。**
- `Hooks.afterToolCall(call, result: ToolResultHolder)` 的 `result.write(outcome, signature)` 就是为改写工具结果设计的，注释明写「hook 可替换结果负载，对齐 pi tool_result 改写能力」。
- 注册一个 hook 对所有工具生效，不动 loop；截断逻辑复用/对齐 `TerminalSessionPool.truncateOutput` 与 pi 的 2000 行 / 50KB 默认。

**max turns → 不用 Hook，放 loop 层。**
- Hook 是事件面，没有「每轮结束强制中止循环」的时机：`beforeToolCall` 只能阻断单次调用，结果回喂后模型还能继续调；`beforeStop` 只在停止时触发一次。
- 用 `beforeSerialization` 改历史来数轮次不可靠（历史会被压缩/截断，计数失真）。
- 正确位置：`RealAgentLoop` 的 `while(true)` 中 `continue` 前计数检查，`LoopOptions` 加 `maxTurns` 字段——语义等同 pi 的 `shouldStopAfterTurn`（agent-loop.ts:252）。

---

## 任务拆分建议（实施顺序）

| # | 任务 | 触及范围 |
|---|------|----------|
| 1 | 记忆条目增删 | LocalSettingsDefaults.kt |
| 2 | 多引擎 web search | XRepo.kt 种子 code/schema |
| 3 | max turns + 输出截断 | okia loop、agent-runtime 接线 |
| 4 | 执行规则确认拦截器 | okia hooks、agent-runtime、app UI、IPC |
