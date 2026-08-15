# Progress.md

日志修补分支（`worktree-feat+logging`）的进度与接力文档。

## 用途与工作流（先读这段）

我们之间的对话**可以回退**（rollback）。每次完成一个功能点，你会让我提交代码，然后回退对话到某个点位、开新会话继续。

```
·-·conversation·-·--feat-a-commit
                   <--rollback
                   --feat-b-commit
```

为了让回退后的新会话无缝接上，遵守两条约定：

1. **每次提交前**，把「当前状态」「检查点记录」「下一步行动」更新到本文件，随代码一起提交。
2. **新会话恢复后**：先读 `LOG.md`（埋点规格）+ `LOG_TASK.md`（前因）+ 本文件（进度），从「下一步行动」继续，不要重新盘点仓库。

## 当前状态

- 分支：`worktree-feat+logging`
- 已完成：日志系统引入（`libs/logging`）、`LOG.md` 定稿、任务拆解 6 份、Chunk 1 日志统一（含 XEvent 移除）、**Chunk 2 对话功能埋点**
- 进行中：Chunk 3（对话切换 + 加载耗时）
- 工作区：干净

## 任务拆解（6 份）

| # | 份 | 文件数 | 状态 |
|---|---|---|---|
| 1 | 日志统一（地基，必须最先做） | 27（含 XEvent 整包移除，原子操作超预估） | ✅ `79556b1` |
| 2 | 对话功能 | 8（含 SilentLoggerRule） | ✅ 本次提交 |
| 3 | 对话切换 + 加载耗时 | ~7 | ⬜ |
| 4 | 设置页配置 | ~15 | ⬜ |
| 5 | Hook 健康度 | ~12 | ⬜ |
| 6 | 渲染管线 + Takeover + 工具 | ~14 | ⬜ |

每份规模约 1000 行改动 / ≤20 个文件，超出即拆。

## 检查点记录

| # | 内容 | commit |
|---|---|---|
| 0 | 引入 `libs/logging` 分级日志门面 | `bf6d95f` |
| 1 | `LOG.md` 埋点清单草稿 + `LOG_TASK.md` 前因/现状 | `87865b6` |
| 2 | `LOG.md` 定稿 + `Progress.md` 建立 | `5a42fd0` |
| 3 | **Chunk 1 日志统一**：4 模块加 `libs:logging` 依赖；`xlog`/`xtlog`/`Log.e` 全部迁到 `Logger`；删除 `Xlogging.kt` 与 `xevent` 整包（含 8 处调用点收编，其中 LLMController/RenderTextStreamCard/AbstractAssistantHook 的 XEvent 事件已等价替换为 Logger 调用） | `79556b1` |
| 4 | **Chunk 2 对话功能**：`LLMController`（refresh 配置/工具/MCP 耗时与失败列表，stream 锁/首帧/错误码）；`AgentRuntimeService`（submit/cancel/resetConversation/executeTurn）；`AbstractAssistantHook.dispatchQueryToLLM`；`HomeChatState`（sendCurrentInput/collectLlmStream/applyEvent）；`LlmStreamEventMapper.map`；新增 `SilentLoggerRule` 修复纯 JVM 单测 LogcatBackend 崩溃 | 本次提交 |

## 下一步行动

⬜ **Chunk 3 对话切换 + 加载耗时**，按 `LOG.md` 两小组逐点补 Logger：

1. `HomeChatState`（app）：`startNewConversation` / `loadConversation` / `deleteConversationNow` / `ensureCurrentConversation` / `forkAt` / `reGenerateAt` / `restoreLastConversationOnStartup`（冷启动恢复耗时）。
2. `ConversationRepo`（app）：`createConversation` / `forkConversation` / `getConversation` / `listConversations`。
3. `ConversationDao`（app）：`listConversations` / `getConversation` / `listTurns`（Room 查询耗时）。
4. `ConversationFormatter.toHomeTurns`（app）。
5. `LLMController.replaceHistory` / `getHistory`（agent-runtime，历史注入耗时）。
6. `XRepo.lastOpenedConversationId` / `setLastOpenedConversationId`（app）。
7. 验证：编译 + 两个模块单测（app 测试走 Robolectric，无需 SilentLoggerRule）；通过后提交并更新本文件。

## 关键技术事实（回退后不必重新发现）

- 统一走 `com.niki914.logging.Logger`（`d/i/w/e`）；`d(tag, msg, logInRelease=false)` 默认 debug 构建才输出，关键链路/耗时用 `i` 常开。
- 耗时在起止点记录 `elapsedMs`；代码注释一律英语；TAG 格式 `niki914_nexus_XXX`（XXX 为业务名，如 `LLMController`）。
- **依赖现状**：`xposed-api` / `xposed-runtime` / `ui-kit` / `agent-runtime` / `:app` 均已依赖 `libs:logging`（Chunk 1 完成）。
- **纯 JVM 单测坑**：`agent-runtime` 单测环境 `android.util.Log` 未 mock，Logger 默认 LogcatBackend 会崩；用 `agent-runtime/src/test/.../chat/util/SilentLoggerRule.kt` 挂 `@get:Rule` 解决（app 测试走 Robolectric，无此问题）。
- 旧日志残留：`Xlogging.kt`（`xlog`/`xtlog` → `Log.e("nexus-x-log")`）、`ComposeMVIViewModel.onError`（直接 `Log.e`）、`XEvent.emit`（内部 xtlog，且 `emit` 当前 `return // TODO` 整体关闭，直接移除）。
- 模块归属：主 App UI/仓库在 `app/src/main`，LLM 运行时在 `agent-runtime`，Hook 宿主侧在 `xposed-runtime`（公共 API 在 `xposed-api`），UI 组件在 `ui-kit`，跨进程 IPC 在 `store`（`XIpcBridge`）。
