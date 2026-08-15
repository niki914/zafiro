# LOG.md

日志修补分支的埋点清单。按业务域分组，列出需要补日志的函数/位置，后续逐一落实后可在此增删。

约定：
- 统一走 `com.niki914.logging.Logger`（`Logger.d/i/w/e`），tag 建议用类名或固定业务 tag。
- 普通调试日志用 `Logger.d`（debug 构建才输出）；关键链路/耗时用 `Logger.i` 常开记录。
- 耗时统一在起止点记录 `elapsedMs`。

### 对话功能 (Conversation)
- `LLMController.stream` — turn 加锁、query 长度、refresh 结果、首帧/完成、错误码与总耗时
- `LLMController.refresh` — 配置读取、工具 resolve、MCP refresh（耗时 / 失败 server 列表）
- `LLMController.resetConversation` / `stopCurrentRound`
- `AgentRuntimeService.submit` / `executeTurn` / `cancel` / `resetConversation`
- `AbstractAssistantHook.handleCapturedQuery` / `dispatchQueryToLLM`
- `HomeChatViewModel.sendCurrentInput` / `collectLlmStream` / `applyEvent`
- `LlmStreamEventMapper.map` — 流事件映射结果

### 对话切换 (Conversation switching)
- `HomeChatViewModel.startNewConversation` / `loadConversation` / `deleteConversationNow`
- `HomeChatViewModel.ensureCurrentConversation` / `forkAt` / `reGenerateAt`
- `ConversationRepo.createConversation` / `forkConversation`
- `LLMController.replaceHistory` / `getHistory`
- `XRepo.lastOpenedConversationId` / `setLastOpenedConversationId`

### 对话加载耗时 (Conversation loading time)
- `HomeChatViewModel.restoreLastConversationOnStartup` — 冷启动恢复耗时
- `HomeChatViewModel.loadConversation` — 切换会话加载耗时
- `ConversationRepo.getConversation` / `listConversations`
- `ConversationDao.listConversations` / `getConversation` / `listTurns`（Room 查询耗时）
- `ConversationFormatter.toHomeTurns`
- `LLMController.replaceHistory` — 历史注入耗时

### 设置页配置 (Settings config)
- `XRepo.readJson` / `writeJson` / `updateJson` / `tryPutDefaultSettings` / `setOnboardingCompleted`
- `XIpcDomainSettingsStore.readJson` / `writeJsonFromOwner` / `mutateJson`
- `XIpcBridge.readStoreJson` / `writeStoreJsonFromOwner` / `mutateStoreJson` / `resolveTransport`
- `AgentRuntimeService.StoreStubImpl.readStore` / `writeStore` / `mutateStore`
- `AgentRuntimeClient.readStore` / `writeStore` / `mutateStore`
- 各设置 ViewModel：`SettingsViewModel` / `ConfigureViewModel` / `TakeoverSettingsState` / `McpSettingsState` / `MemorySettingsState` / `SkillSettingsState` / `CustomToolSettingsState` / `ExecutionRulesSettingsState` / `BuiltinToolSettingsState`（读/写结果与校验失败原因）

### 模块 Hook 点健康度 (Hook health)
- `Entrance.onLoad` / `onSettingsFetched` — 进程、targetPkg、web 配置结果、hook 路由结果
- `RuntimeBootstrap.installIfNeeded` — runtime 安装 / 重复安装
- `Runtime.attach` — sync/dexkit hook 执行、DexKit 扫描耗时
- `HookExtensions.hookMethod` / `hookConstructor` / `hookExtensionTry` — hook 注册失败与异常
- `SubHook.onHook` — hook target / paramTypes 解析失败
- `AbstractAssistantHook.onHook` / `installSessionHooks` / `installResponseHooks` / `installInputHooks`
- `BaseConfigProvider.parseHookTarget` / `getString` / `getBoolean` / `getInt` — 配置路径解析失败
- `FloatScreenResetDetector.install` / detach / resume — 悬浮屏复位判定
- `AgentRuntimeClient.connect` / `connectAndAwait` / `onBinderUnreachable` / `scheduleReconnect`

### 渲染管线 (Render pipeline)
- `BreenoChatHook.renderStreamCard` / `obtainRenderSession` / `clearRenderSession`
- `XiaoaiChatHook.dispatchQueryToLLM` / `renderStreamCard`
- `RenderTextStreamCardHook.render` / `injectChunk` / `reset`
- `CaptureResponseTargetHook.beforeHook` — 响应目标捕获
- `BlockNativeCardHook` / `BlockNativeInstructionByWhitelistHook` / `BlockNativeTtsPlaybackHook` / `SuppressCleanupHook`

### Takeover 决策 (Takeover decision)
- `TakeoverResolver.resolve` — 命中规则 / 默认目标
- `AbstractAssistantHook.resolveTakeover`
- `XRepo.takeoverRules.list` / `getDefaultTarget`

### 工具执行 (Tool execution)
- `ToolCallDispatcher.executeLocalTool` / `executeCustomTool` — 本地/自定义工具执行与结果
- `ToolManager.resolve` — 工具解析结果
- `ToolCallDispatcher` 经 `LLMController.openSession` 的 hook 回调
- `PyRuntime` / `TerminalSessionPool` — python / terminal 工具生命周期

### 日志清理 / 统一 (Cleanup & unify)
- `xposed-api/.../util/Xlogging.kt` — `xlog` / `xtlog`（`Log.e("nexus-x-log")`）迁移到 `Logger`
- `xposed-runtime` 各处 `xlog` / `xtlog` 调用点随 Xlogging 统一
- `ui-kit/.../base/ComposeMVIViewModel.onError` — `Log.e` 改 `Logger.e`
- `XEvent.emit` 内部 `xtlog` 调用点替换
