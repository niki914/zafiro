# ISSUES — okia 整合待提清单

> Nexus × OKIA 接入过程中发现、需要提给 `libs:okia` 的 issue 批。
> 维护：T 链推进时增删；标「作废」= 因方案变化不再需要，不提交。
> 状态：`待提` / `已提(#xx)` / `作废`

## 主议题

### OKIA-1（已提 #125）MCP 发现与 LLM loop 互不干涉：refreshMcpTools 不得与 send 互相阻塞

- **问题**：`RealOkia.refreshMcpTools()` 在 `mutex.withLock` 内持锁整个网络往返（`RealOkia.kt:221-231`）；`RealOkia.send` 也走同一把 mutex（`RealOkia.kt:128-141`，短临界区：check + append User + 启动 job）。后台刷新进行中用户发问 → `send` 排队等刷新结束。
- **矛盾点**：`ToolRegistry` 契约本身允许活跃回合内注册工具（T2a `registerCustomToolNow` 已验证、`RealAgentLoop` 每段 buildRequest 前现取 `registry.snapshot()`），因此 `refreshMcpTools` 的 `check(activeTurn == null)` 拦截（`RealOkia.kt:226`）缺乏现实理由——MCP 发现/注册在回合内发生语义安全。
- **期望**：MCP 发现状态与 LLM loop 独立；`refreshMcpTools` 不与 `send` 争同一把锁（发现应可后台执行，不阻塞回合启动）；回合内刷新不再抛异常（或至少不因锁排队而阻塞 send）。
- **关联**：见 OKIA-4（ToolRegistry 注释矛盾，同一处设计）。

### OKIA-2（作废）McpDiscoveredTool 可序列化（@Serializable）

- **作废原因**：T2B 决策 D-T2B-1 彻底删除 MCP 工具缓存持久化（对齐 Codex：无跨启动磁盘持久化；服务器不可达时缓存了也不能执行）。不再有持久化 McpDiscoverySnapshot 的需求。
- 状态：`作废`（2026-08-19）

### OKIA-3（已提 #126）`RealConversation.project(null)` 语义与文档不一致

- **问题**：`RealConversation.project(null)` 返回空列表（`RealConversation.kt:121`），与 `docs/okia.md` §5.3「leafId = null 恢复为最后一条」的文档意图不符。
- **现状**：T1 接入已绕开（`buildSnapshotFromChatTurns` 显式设 `leafId = entries.lastOrNull()?.id`）。
- **期望**：确认语义（修复实现或修正文档，二选一）。

### OKIA-4（已提 #127）ToolRegistry「活跃回合不得直接变更 registry」注释与 update 路由矛盾

- **问题**：`ToolRegistry.kt` 注释声明"活跃回合期间不得直接变更 registry，须经 Okia.update"；但 `OkiaConfig.toolRegistry` 是 host 持有并注入的**同一对象引用**，`register/remove` 不经 `Okia.update` 也即时生效（`effectiveRegistry(config)` 直接引用），`RealAgentLoop` 每段现取 snapshot。注释与实际数据流矛盾。
- **期望**：修正注释（文档修复级），或明确 contract。

### OKIA-5（暂缓，低优先）send 前「确保 MCP 工具就绪」的统一入口

- **问题**：`Okia` 无「先确保发现完成再 send」的 ensure API；host 只能手动调 `refreshMcpTools()` 再测工具是否注册。
- **现状**：T2B 改为回合前签名变化时同步 `refreshMcpTools()`（host 侧手动标脏），本 issue 降为低优先；若未来做 lazy-when-cached 需要再提。

## 作废理由汇总

| # | 议题 | 作废理由 |
|---|---|---|
| OKIA-2 | McpDiscoveredTool @Serializable | T2B D-T2B-1 删除 MCP 持久化，无消费方 |
