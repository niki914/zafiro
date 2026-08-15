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
- 已完成：日志系统引入（`libs/logging`）、`LOG.md` 定稿、任务拆解 6 份、**Chunk 1 日志统一（含 XEvent 移除）**
- 进行中：Chunk 2（对话功能）
- 工作区：干净

## 任务拆解（6 份）

| # | 份 | 文件数 | 状态 |
|---|---|---|---|
| 1 | 日志统一（地基，必须最先做） | 27（含 XEvent 整包移除，原子操作超预估） | ✅ |
| 2 | 对话功能 | ~8 | ⬜ 未开始 |
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
| 3 | **Chunk 1 日志统一**：4 模块加 `libs:logging` 依赖；`xlog`/`xtlog`/`Log.e` 全部迁到 `Logger`；删除 `Xlogging.kt` 与 `xevent` 整包（含 8 处调用点收编，其中 LLMController/RenderTextStreamCard/AbstractAssistantHook 的 XEvent 事件已等价替换为 Logger 调用） | 本次提交 |

## 下一步行动

⬜ **Chunk 2 对话功能**，按 `LOG.md`「对话功能」组逐点补 Logger：

1. `LLMController`（agent-runtime）：`stream` 补 turn 加锁/refresh 结果/首帧/错误码（round started/completed/error 已就位）；`refresh` 补配置读取、工具 resolve、MCP refresh 耗时与失败 server 列表；`resetConversation` / `stopCurrentRound`。
2. `AgentRuntimeService`（app）：`submit` / `executeTurn` / `cancel` / `resetConversation`。
3. `AbstractAssistantHook`（app）：`handleCapturedQuery` 的 input captured / turn decided 已在 Chunk 1 埋好，补 `dispatchQueryToLLM` 的提交与渲染结果。
4. `HomeChatViewModel`（app，实际在 `ui/nexus/model/HomeChatState.kt` 等）：`sendCurrentInput` / `collectLlmStream` / `applyEvent`。
5. `LlmStreamEventMapper`（agent-runtime）：`map` 流事件映射结果。
6. 编译 + 单测（`:agent-runtime:testDebugUnitTest` / `:app:testDebugUnitTest`），通过后提交并更新本文件。

## 关键技术事实（回退后不必重新发现）

- 统一走 `com.niki914.logging.Logger`（`d/i/w/e`）；`d(tag, msg, logInRelease=false)` 默认 debug 构建才输出，关键链路/耗时用 `i` 常开。
- 耗时在起止点记录 `elapsedMs`；代码注释一律英语；TAG 格式 `niki914_nexus_XXX`（XXX 为业务名，如 `LLMController`）。
- **依赖现状**：目前**只有 `:app`** 依赖 `libs:logging`；`xposed-api` / `xposed-runtime` / `ui-kit` / `agent-runtime` 均未依赖，Chunk 1 需先加。
- 旧日志残留：`Xlogging.kt`（`xlog`/`xtlog` → `Log.e("nexus-x-log")`）、`ComposeMVIViewModel.onError`（直接 `Log.e`）、`XEvent.emit`（内部 xtlog，且 `emit` 当前 `return // TODO` 整体关闭，直接移除）。
- 模块归属：主 App UI/仓库在 `app/src/main`，LLM 运行时在 `agent-runtime`，Hook 宿主侧在 `xposed-runtime`（公共 API 在 `xposed-api`），UI 组件在 `ui-kit`，跨进程 IPC 在 `store`（`XIpcBridge`）。
