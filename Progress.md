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
- 已完成：日志系统引入（`libs/logging`）、`LOG.md` 定稿、任务拆解 6 份、**Chunk 1-6 全部完成**（日志统一、对话功能、对话切换/加载耗时、设置页配置、Hook 健康度、渲染管线/Takeover/工具）
- 进行中：无（6 份全部落地，待提交）
- 工作区：待提交（Chunk 6）

## 任务拆解（6 份）

| # | 份 | 文件数 | 状态 |
|---|---|---|---|
| 1 | 日志统一（地基，必须最先做） | 27（含 XEvent 整包移除，原子操作超预估） | ✅ `79556b1` |
| 2 | 对话功能 | 8（含 SilentLoggerRule） | ✅ `1ad8014` |
| 3 | 对话切换 + 加载耗时 | 8（含 app 测试 SilentLoggerRule） | ✅ `5d98d58` |
| 4 | 设置页配置 | 27（含 store 依赖/测试补漏） | ✅ `684f919` |
| 5 | Hook 健康度 | 8 | ✅ `2298e50` |
| 6 | 渲染管线 + Takeover + 工具 | 20（含 5 个测试补 SilentLoggerRule） | ✅ 本次提交 |

每份规模约 1000 行改动 / ≤20 个文件，超出即拆。

## 检查点记录

| # | 内容 | commit |
|---|---|---|
| 0 | 引入 `libs/logging` 分级日志门面 | `bf6d95f` |
| 1 | `LOG.md` 埋点清单草稿 + `LOG_TASK.md` 前因/现状 | `87865b6` |
| 2 | `LOG.md` 定稿 + `Progress.md` 建立 | `5a42fd0` |
| 3 | **Chunk 1 日志统一**：4 模块加 `libs:logging` 依赖；`xlog`/`xtlog`/`Log.e` 全部迁到 `Logger`；删除 `Xlogging.kt` 与 `xevent` 整包（含 8 处调用点收编，其中 LLMController/RenderTextStreamCard/AbstractAssistantHook 的 XEvent 事件已等价替换为 Logger 调用） | `79556b1` |
| 4 | **Chunk 2 对话功能**：`LLMController`（refresh 配置/工具/MCP 耗时与失败列表，stream 锁/首帧/错误码）；`AgentRuntimeService`（submit/cancel/resetConversation/executeTurn）；`AbstractAssistantHook.dispatchQueryToLLM`；`HomeChatState`（sendCurrentInput/collectLlmStream/applyEvent）；`LlmStreamEventMapper.map`；新增 `SilentLoggerRule` 修复纯 JVM 单测 LogcatBackend 崩溃 | `1ad8014` |
| 5 | **Chunk 3 对话切换 + 加载耗时**：`HomeChatState`（startNew/loadConversation/delete/ensure/fork/reGenerate/restore 冷启动耗时）；`ConversationRepo`（create/fork/get/list）；`ConversationDao` 三条 Room 查询包耗时包装（抽象查询改 `XxxQuery` 后缀 + 默认方法同名包装）；`ConversationFormatter.toHomeTurns`；`LLMController.replaceHistory/getHistory`；`XRepo.lastOpenedConversationId/set`；app 测试源新增 `SilentLoggerRule`（`ConversationFormatterTest` 纯 JVM 需要） | `5d98d58` |
| 6 | **Chunk 4 设置页配置**：`XRepo`（read/write/update/updateJsonOrFalse/tryPutDefaultSettings/setOnboardingCompleted，读写耗时+结果）；`XIpcDomainSettingsStore` 三方法；`XIpcBridge`（read/write/mutate + resolveTransport 各态日志，store 模块补 `libs:logging` 依赖）；`AgentRuntimeService.StoreStubImpl` 三方法；`AgentRuntimeClient` read/write/mutate（含 DeadObject/RemoteException 分支）；9 个设置页 ViewModel（load 带行数耗时 d、save/toggle/delete i、校验失败 w）；10 个纯 JVM 测试挂 `SilentLoggerRule`；**补 store 模块 `SilentLoggerRule` 修复 Chunk 1 遗留的 `IpcJsonMutatorTest` 崩溃（`XTry.xTry`→`Logger.w` 在纯 JVM 撞 LogcatBackend，store 单测此前从未跑过）** | `684f919` |
| 7 | **Chunk 5 Hook 健康度**：`Entrance`（onLoad/web config 结果/网络与版本分支/hook 路由）；`Runtime.attach`（sync/dexkit 每 hook 耗时+ok，DexKit 扫描耗时；`RuntimeBootstrap` 已有点位不动）；`HookExtensions`（hookMethod/hookConstructor class 未找到 w + 注册成功 d）；`SubHook.onHook`（hookTarget/paramTypes 解析失败 w）；`AbstractAssistantHook.onHook`（四阶段安装 i）；`BaseConfigProvider`（config path 解析失败 w）；`FloatScreenResetDetector`（install/detach/resume/reset 判定，diffMs）；`AgentRuntimeClient`（connect/connectAndAwait/onServiceConnected/Disconnected/binder death/scheduleReconnect/doReconnect，重试次数与状态） | `2298e50` |
| 8 | **Chunk 6 渲染管线 + Takeover + 工具**：`BreenoChatHook`（render entry/首帧/终帧/session create-reuse-clear）；`XiaoaiChatHook`（dispatch 起止/首帧/终帧/失败 + render entry）；`RenderTextStreamCardHook`（render entry delta/chunk injected/reset）；`CaptureResponseTargetHook`（captured i / dialogId blank d）；`BlockNativeCard`/`BlockNativeInstruction`/`BlockNativeTts`（放行分支 d，拦截已有 i）；`SuppressCleanupHook`（suppressed i / pass 分支 d）；`TakeoverResolver.resolve`（命中 i / 默认 d）；`AbstractAssistantHook.resolveTakeover`（规则数+默认目标 d）；`XRepo.TakeoverRulesApi`（list/defaultTarget 耗时 d）；`ToolCallDispatcher`（builtin/custom 起止+结果长度 i，不可执行 w）；`ToolManager.resolve`（输入输出计数 d）；`PyRuntime`（exec 起止/失败、kill、kill&reconnect、terminate pending）；`TerminalSessionPool`（open/reuse/fail、command start/done/timeout/fail、async start/accepted/busy、close/closeAll）；**5 个纯 JVM 测试补 `SilentLoggerRule`（ToolManagerTest/ToolCallDispatcherTest/TerminalSessionPoolTest/TerminalBuiltinTest/PyRuntimeTest）** | 本次提交 |

## 下一步行动

✅ **全部 6 份已完成**（Chunk 1-6），分支日志修补任务收官：

- Chunk 6 已按 `LOG.md` 三组（渲染管线 / Takeover 决策 / 工具执行）逐点补 Logger，验证编译 + 全模块单测全绿（agent-runtime 319 + app 216 + store 19 = 554）。
- 剩余：提交 Chunk 6（含 Progress.md 更新）后，可在 `LOG.md` 按需增删埋点清单，或开新分支收尾。

## 关键技术事实（回退后不必重新发现）

- 统一走 `com.niki914.logging.Logger`（`d/i/w/e`）；`d(tag, msg, logInRelease=false)` 默认 debug 构建才输出，关键链路/耗时用 `i` 常开。
- 耗时在起止点记录 `elapsedMs`；代码注释一律英语；TAG 格式 `niki914_nexus_XXX`（XXX 为业务名，如 `LLMController`）。
- **依赖现状**：`xposed-api` / `xposed-runtime` / `ui-kit` / `agent-runtime` / `:app` / `:store` 均已依赖 `libs:logging`（Chunk 1 + Chunk 4）。
- **纯 JVM 单测坑**：`agent-runtime`、`app`、`store` 的纯 JVM 单测环境 `android.util.Log` 未 mock，Logger 默认 LogcatBackend 会崩。已有三份 `SilentLoggerRule`：`agent-runtime/src/test/.../chat/util/`、`app/src/test/.../app/util/`、`store/src/test/.../store/`，谁撞谁挂 `@get:Rule`。注意 `XTry.xTry`（xposed-api）内部已走 `Logger.w`，任何走 xTry 失败路径的纯 JVM 测试都需要规则。
- 旧日志残留：`Xlogging.kt`（`xlog`/`xtlog` → `Log.e("nexus-x-log")`）、`ComposeMVIViewModel.onError`（直接 `Log.e`）、`XEvent.emit`（内部 xtlog，且 `emit` 当前 `return // TODO` 整体关闭，直接移除）——均已在 Chunk 1 清理完毕。
- 模块归属：主 App UI/仓库在 `app/src/main`，LLM 运行时在 `agent-runtime`，Hook 宿主侧在 `xposed-runtime`（公共 API 在 `xposed-api`），UI 组件在 `ui-kit`，跨进程 IPC 在 `store`（`XIpcBridge`）。
