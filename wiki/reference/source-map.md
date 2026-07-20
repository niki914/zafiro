# Source Map

按共享前缀压缩的项目根相对路径地图，只保留当前仓库里真实存在的源码与配置入口。

## 构建与入口

### `app/src/main/`

- `AndroidManifest.xml`: App 与 Xposed 清单入口

### `app/src/main/java/a0/a0/a0/a0/a0/a0/`

- `Entrance.kt`: Xposed 入口与宿主路由

## Hook 与宿主链路

### `app/src/main/java/com/niki914/nexus/agentic/mod/`

- `HookLocalSettings.kt`: Hook 侧本地配置读取
- `SettingModels.kt`: Hook 侧配置模型
- `XService.kt`: 本地与远程配置门面
- `feat/AbstractAssistantHook.kt`: 共用 Hook 主流程
- `feat/BaseConfigProvider.kt`: 宿主配置提供者基类
- `feat/FloatScreenResetDetector.kt`: 浮窗重置检测
- `feat/HookTarget.kt`: 宿主目标枚举
- `feat/NexusAccessibilityService.kt`: AccessibilityService 实现
- `feat/SubHook.kt`: 子 Hook 协议
- `feat/oppo/BreenoChatHook.kt`: Breeno 业务 Hook 入口
- `feat/oppo/BreenoConfigProvider.kt`: Breeno 配置提供者
- `feat/oppo/BreenoFeedbackAssembler.kt`: Breeno UI 反馈组装
- `feat/oppo/subhooks/BlockNativeCardHook.kt`: 拦截原生卡片
- `feat/oppo/subhooks/CaptureInputHook.kt`: 捕获输入
- `feat/oppo/subhooks/ResetConversationSignalHook.kt`: 会话重置信号
- `feat/oppo/subhooks/SuppressCleanupHook.kt`: 阻止清理逻辑
- `feat/hyper/XiaoaiChatHook.kt`: XiaoAi 业务 Hook 入口
- `feat/hyper/XiaoaiConfigProvider.kt`: XiaoAi 配置提供者
- `feat/hyper/XiaoaiRenderSession.kt`: XiaoAi 渲染会话
- `feat/hyper/subhooks/BlockNativeInstructionByWhitelistHook.kt`: 指令白名单拦截
- `feat/hyper/subhooks/BlockNativeTtsPlaybackHook.kt`: 原生 TTS 拦截
- `feat/hyper/subhooks/CaptureInputHook.kt`: 捕获输入
- `feat/hyper/subhooks/CaptureResponseTargetHook.kt`: 捕获响应目标
- `feat/hyper/subhooks/EXT.kt`: Hyper 子 Hook 扩展
- `feat/hyper/subhooks/RenderTextStreamCardHook.kt`: 文本流卡片渲染

## h 模块

### `h/src/main/java/com/niki914/nexus/h/`

- `IXposed.kt`: Xposed 入口接口定义

### `h/src/main/java/com/niki914/nexus/h/util/`

- `ActivityHook.kt`: Activity 生命周期 Hook
- `ContextHook.kt`: 宿主 Context 捕获
- `ContextProvider.kt`: Context 提供
- `FloatWindowHook.kt`: 浮窗 Hook
- `HookExtensions.kt`: Hook 扩展函数
- `HookSideLoader.kt`: Hook 侧加载器
- `InspectExtensions.kt`: 反射检查扩展
- `Inspector.kt`: 反射检查器
- `LockState.kt`: 锁状态检测
- `OsUtils.kt`: 系统工具函数
- `ReflectionExtensions.kt`: 反射扩展函数
- `RootUtils.kt`: Root 权限工具
- `XProvider.kt`: Xposed 内容提供者
- `XTry.kt`: 异常捕获工具
- `Xlogging.kt`: 日志工具

### `h/src/main/java/com/niki914/nexus/h/core/runtime/`

- `Hook.kt`: Hook 核心抽象
- `Runtime.kt`: Runtime 核心抽象
- `RuntimeBootstrap.kt`: 宿主进程 runtime 安装

### `h/src/main/java/com/niki914/nexus/h/xevent/`

- `XEvent.kt`: 交叉事件定义
- `XEventContext.kt`: 事件上下文
- `XEventEnvelope.kt`: 事件信封
- `XEventType.kt`: 事件类型枚举
- `XEventUtils.kt`: 事件工具函数

## Runtime 与工具系统

### `agent-runtime/src/main/java/com/niki914/nexus/agentic/`

- `chat/ActiveTurnStore.kt`: 活跃 turn 状态缓存
- `chat/ConversationTurnState.kt`: 会话状态流转
- `chat/LLMController.kt`: LLM 运行时入口
- `chat/LlmModels.kt`: 运行时模型定义
- `chat/LlmStreamEvent.kt`: 流事件定义
- `chat/TurnMode.kt`: turn 模式枚举
- `chat/agentic/PromptComposer.kt`: 提示词组装
- `chat/agentic/SessionToolBinder.kt`: tool 与会话绑定
- `chat/agentic/ToolCallDispatcher.kt`: local tool 调度
- `chat/agentic/ToolManager.kt`: 工具列表与 prompt lines 生成
- `chat/agentic/accessibility/AccessibilityController.kt`: 无障碍服务控制器
- `chat/agentic/accessibility/IPointerOverlay.kt`: 指针叠加层接口
- `chat/agentic/accessibility/NodeModel.kt`: 无障碍节点模型
- `chat/agentic/accessibility/PruningRules.kt`: 节点剪枝规则
- `chat/agentic/accessibility/TreeFormatter.kt`: 节点树格式化
- `chat/agentic/buildin/BuiltinTool.kt`: builtin tool 协议
- `chat/agentic/buildin/BuiltinToolExecutor.kt`: builtin tool 执行器
- `chat/agentic/buildin/BuiltinToolRegistry.kt`: builtin tool 注册表
- `chat/agentic/buildin/BuiltinToolSettingsManager.kt`: builtin tool 设置读写
- `chat/agentic/buildin/RawBuiltinTool.kt`: 原生 builtin tool 封装
- `chat/agentic/buildin/impl/CreateCustomToolBuiltin.kt`: 内建创建 custom tool
- `chat/agentic/buildin/impl/GestureBuiltin.kt`: 手势操作工具
- `chat/agentic/buildin/impl/KeyEventBuiltin.kt`: 按键事件工具
- `chat/agentic/buildin/impl/LaunchAppBuiltin.kt`: 启动应用
- `chat/agentic/buildin/impl/LoadSkillBuiltin.kt`: 加载技能工具
- `chat/agentic/buildin/impl/MemorizeBuiltin.kt`: 记忆写入工具
- `chat/agentic/buildin/impl/NodeActionBuiltin.kt`: 节点交互动作工具
- `chat/agentic/buildin/impl/NotifyBuiltin.kt`: 通知工具
- `chat/agentic/buildin/impl/OpenUriBuiltin.kt`: 打开 URI
- `chat/agentic/buildin/impl/ReadCustomToolBuiltin.kt`: 读取 custom tool 定义
- `chat/agentic/buildin/impl/ScreenContentBuiltin.kt`: 屏幕内容读取工具
- `chat/agentic/buildin/impl/SearchAppsBuiltin.kt`: 搜索已安装应用
- `chat/agentic/buildin/impl/SearchNodesBuiltin.kt`: 无障碍节点搜索工具
- `chat/agentic/buildin/impl/SshTerminalBuiltin.kt`: SSH 终端工具
- `chat/agentic/buildin/impl/TerminalAction.kt`: terminal 动作与参数约束
- `chat/agentic/buildin/impl/TerminalBuiltin.kt`: 本地终端工具
- `chat/agentic/custom/CustomToolExecutor.kt`: custom tool 执行器
- `chat/agentic/custom/CustomToolManager.kt`: custom tool 配置与校验
- `chat/agentic/device/AppInfoCache.kt`: 应用信息缓存
- `chat/agentic/device/AppInfoProvider.kt`: 已安装应用信息提供者
- `chat/agentic/mcp/McpDiscoveryCacheStore.kt`: MCP discovered tools 缓存
- `chat/agentic/shell/ShellCommandSafetyPolicy.kt`: shell 命令安全策略
- `chat/agentic/shell/TerminalSessionPool.kt`: 终端 session 池
- `chat/agentic/shell/TerminalToolResponse.kt`: 终端工具响应格式
- `chat/agentic/stream/LlmStreamEventMapper.kt`: 流事件映射
- `chat/agentic/stream/LocalToolResultClassifier.kt`: local tool 结果分类
- `chat/agentic/stream/ToolEventFormatter.kt`: 工具事件格式化
- `runtime/settings/RuntimeBridge.kt`: runtime bridge 组合入口
- `runtime/settings/RuntimeEnvironment.kt`: runtime bridge 安装与获取
- `runtime/settings/RuntimeHostGateway.kt`: 宿主侧能力接口
- `runtime/settings/RuntimeSettingsGateway.kt`: runtime 设置读写门面
- `runtime/settings/model/LlmApiType.kt`: LLM API 类型枚举
- `runtime/settings/model/RuntimeSettingsModels.kt`: runtime 设置模型
- `util/TextPatternMatcher.kt`: 文本模式匹配工具

## Runtime 进程边界

### `app/src/main/java/com/niki914/nexus/agentic/runtime/`

- `AppRuntimeBridge.kt`: App 侧 runtime 桥接
- `IpcRuntimeHostGateway.kt`: 通过 IPC 访问宿主 runtime
- `service/AgentRuntimeService.kt`: 后台 Runtime Service
- `client/AgentRuntimeClient.kt`: Runtime Service 客户端
- `client/AssistantTextSource.kt`: 助手文本源 IPC 封装
- `ipc/IAgentRuntimeService.kt`: Runtime Service AIDL 接口
- `ipc/IRenderFrameCallback.kt`: 渲染帧回调 AIDL 接口
- `ipc/RenderFrame.kt`: 渲染帧 IPC 数据模型

## App 与设置

### `app/src/main/java/com/niki914/nexus/agentic/`

- `app/App.kt`: Application 入口
- `app/EXT.kt`: App 扩展函数
- `app/MainActivity.kt`: 主 Activity
- `app/conversation/ChatTurnJsonCodec.kt`: 对话 turn JSON 编解码
- `app/conversation/ConversationDao.kt`: 会话数据访问
- `app/conversation/ConversationDatabase.kt`: 会话数据库定义
- `app/conversation/ConversationEntities.kt`: 会话实体
- `app/conversation/ConversationFormatter.kt`: 会话格式化
- `app/conversation/ConversationRepo.kt`: 会话仓库
- `app/overlay/PointerOverlay.kt`: 指针叠加层实现
- `app/ui/nexus/NexusApp.kt`: 顶层 App Shell
- `app/ui/nexus/NexusPages.kt`: 页面装配与路由分发
- `app/ui/nexus/PageChrome.kt`: 页面 chrome 装配
- `app/ui/nexus/model/AboutSettingsState.kt`: About 设置页状态
- `app/ui/nexus/model/AppLaunchDecision.kt`: 启动页决策
- `app/ui/nexus/model/BuiltinToolSettingsState.kt`: builtin tools 设置状态
- `app/ui/nexus/model/ConfigureState.kt`: 配置页状态
- `app/ui/nexus/model/CustomToolSettingsState.kt`: 自定义工具设置状态
- `app/ui/nexus/model/ExecutionRulesSettingsState.kt`: 执行规则设置状态
- `app/ui/nexus/model/HomeChatState.kt`: 首页对话状态
- `app/ui/nexus/model/McpSettingsState.kt`: MCP 设置状态
- `app/ui/nexus/model/MemorySettingsState.kt`: Memory 设置状态
- `app/ui/nexus/model/ProviderSpec.kt`: provider 规格定义
- `app/ui/nexus/model/SettingsState.kt`: 设置页状态
- `app/ui/nexus/model/SkillSettingsState.kt`: 技能设置页状态
- `app/ui/nexus/model/StartupAssistantUi.kt`: 启动宿主 UI 类型
- `app/ui/nexus/model/TakeoverSettingsState.kt`: takeover 设置状态
- `app/ui/nexus/nav/NexusPage.kt`: 页面定义
- `app/ui/nexus/nav/NexusSettingsGroup.kt`: 设置组模型
- `app/ui/nexus/route/ConfigurePageRoute.kt`: 配置页路由
- `app/ui/nexus/route/ConversationHistoryPageRoute.kt`: 会话历史路由
- `app/ui/nexus/route/CustomToolDetailRoute.kt`: custom tool 详情路由
- `app/ui/nexus/route/DonePageRoute.kt`: 完成页路由
- `app/ui/nexus/route/ExecutionRuleDetailRoute.kt`: 执行规则详情路由
- `app/ui/nexus/route/HomePageRoute.kt`: 首页路由
- `app/ui/nexus/route/McpServerDetailRoute.kt`: MCP server 详情路由
- `app/ui/nexus/route/ProviderPickPageRoute.kt`: provider 选择页路由
- `app/ui/nexus/route/ProviderRouteColors.kt`: provider 路由配色
- `app/ui/nexus/route/SettingsConfigurePageRoute.kt`: 设置项配置页路由
- `app/ui/nexus/route/SettingsDetailPageRoute.kt`: 设置详情页路由
- `app/ui/nexus/route/SettingsHomePageRoute.kt`: 设置首页路由
- `app/ui/nexus/route/SettingsProviderPickPageRoute.kt`: 设置项 provider 选择页路由
- `app/ui/nexus/route/SkillDetailRoute.kt`: 技能详情路由
- `app/ui/nexus/route/StartupPageRoute.kt`: 启动页路由
- `app/ui/nexus/route/TakeoverRuleDetailRoute.kt`: takeover 规则详情路由
- `app/ui/nexus/content/AboutSettingsContent.kt`: About 设置页
- `app/ui/nexus/content/BuiltinToolsSettingsContent.kt`: Builtin Tools 设置页
- `app/ui/nexus/content/ConfigureEditableField.kt`: 配置字段编辑块
- `app/ui/nexus/content/ConfigurePageContent.kt`: 配置页内容
- `app/ui/nexus/content/ConfigurePagePolicy.kt`: 配置页策略
- `app/ui/nexus/content/ConversationHistoryPageContent.kt`: 会话历史页内容
- `app/ui/nexus/content/CustomToolDetailContent.kt`: 自定义工具详情页
- `app/ui/nexus/content/CustomToolsSettingsContent.kt`: 自定义工具设置页
- `app/ui/nexus/content/DonePageContent.kt`: 完成页内容
- `app/ui/nexus/content/EditableSettingsDetailScaffold.kt`: 可编辑详情页壳
- `app/ui/nexus/content/ExecutionRuleDetailContent.kt`: 执行规则详情编辑页
- `app/ui/nexus/content/ExecutionRulesSettingsContent.kt`: 执行规则设置页
- `app/ui/nexus/content/HomeChatComponents.kt`: 首页对话组件
- `app/ui/nexus/content/HomePageContent.kt`: 首页内容
- `app/ui/nexus/content/MemorySettingsContent.kt`: Memory 设置页
- `app/ui/nexus/content/ModelConfigSettingsContent.kt`: Model Config 设置页
- `app/ui/nexus/content/ProviderAccessSettingsBlock.kt`: provider 接入设置块
- `app/ui/nexus/content/ProviderAdvancedSettingsBlock.kt`: provider 高级设置块
- `app/ui/nexus/content/SelectionPageContent.kt`: provider 选择页内容
- `app/ui/nexus/content/SettingsDetailPageContent.kt`: 设置详情分发入口
- `app/ui/nexus/content/SettingsHomePageContent.kt`: 设置首页内容
- `app/ui/nexus/content/SkillDetailContent.kt`: 技能详情页
- `app/ui/nexus/content/SkillsSettingsContent.kt`: 技能设置页
- `app/ui/nexus/content/StartupPageContent.kt`: 启动页内容
- `app/ui/nexus/content/StartupPosterBackground.kt`: 启动页背景
- `app/ui/nexus/content/TODOPageContent.kt`: TODO 页面内容
- `app/ui/nexus/content/TakeoverRuleDetailContent.kt`: takeover 规则详情编辑页
- `app/ui/nexus/content/TakeoverSettingsContent.kt`: takeover 设置页
- `app/ui/nexus/content/mcp/McpServerDetailContent.kt`: MCP server 详情页
- `app/ui/nexus/content/mcp/McpSettingsContent.kt`: MCP 设置页
- `chat/LlmStreamCollectors.kt`: App 侧流事件收集
- `repo/AgentSettingsCodec.kt`: 主 agent 配置编解码
- `repo/AppStateSettingsCodec.kt`: app 状态编解码
- `repo/DomainSettingsStore.kt`: 域设置存储
- `repo/LocalSettingsCodec.kt`: 本地设置编解码
- `repo/LocalSettingsDefaults.kt`: 本地设置默认值
- `repo/McpSettingsCodec.kt`: MCP 设置编解码
- `repo/MemorySettingsCodec.kt`: memory store 编解码
- `repo/RuleSettingsCodec.kt`: execution 与 takeover 规则编解码
- `repo/SettingsJsonCodecUtils.kt`: 设置 JSON 编解码工具
- `repo/SkillApi.kt`: 技能 API 接口
- `repo/SkillEnabledStateStore.kt`: 技能启用状态存储
- `repo/SkillFileRepository.kt`: 技能文件仓库
- `repo/SkillFrontmatterParser.kt`: 技能 frontmatter 解析器
- `repo/SkillPathResolver.kt`: 技能路径解析
- `repo/ToolSettingsCodec.kt`: builtin 与 custom tool 编解码
- `repo/UpdateCheckApi.kt`: 更新检查 API
- `repo/WebSettingsApi.kt`: Web 设置 API
- `repo/WebSettingsModels.kt`: Web 设置数据模型
- `repo/XRepo.kt`: App 侧设置读写门面
- `repo/XRepoRuntimeGateway.kt`: runtime 与 repository 桥接
- `takeover/TakeoverResolver.kt`: query 到 takeover target 的决策

## IPC 基础设施

### `ipc/src/main/java/com/niki914/nexus/ipc/`

- `IpcResult.kt`: IPC 结果封装
- `XIpcBridge.kt`: IPC 桥接入口
- `XNotificationBridge.kt`: 通知 IPC 桥接
- `XRes.kt`: IPC 资源封装
- `cp/SettingsContentProvider.kt`: 设置 ContentProvider
- `cp/XProviderDispatcher.kt`: 内容提供者分发器
- `store/ConfigPersistence.kt`: 配置持久化
- `store/IpcJsonMutator.kt`: IPC JSON 变更器
- `store/StoreDescriptor.kt`: store 描述模型
- `store/StoreDescriptorRegistry.kt`: store 描述注册表
- `store/XIpcStoreRepository.kt`: 多 store 原子读写仓库

## Compose 基础设施

### `composebase/src/main/java/com/niki914/nexus/agentic/app/ui/infra/`

- `ActionBarButton.kt`: 顶栏按钮基建
- `ConfirmationLiquidDialog.kt`: 确认弹窗
- `LiquidDialog.kt`: Liquid 弹窗容器
- `LiquidScreen.kt`: 顶层 Liquid Shell
- `LiquidScreenContentContext.kt`: Liquid Shell 内容上下文
- `LiquidScreenState.kt`: 页面状态
- `LiquidScreenSwipeContent.kt`: 页面转场容器
- `LiquidViewportAvoidance.kt`: 视口避让与 inset 处理
- `component/LiquidButton.kt`: 基础按钮
- `component/LiquidSecretTextField.kt`: 密文输入框
- `component/LiquidTextField.kt`: 文本输入框
- `component/LiquidTextFieldContainer.kt`: 文本输入框容器
- `component/LiquidToggle.kt`: 开关组件
- `component/LiquidToggleStateMachine.kt`: 开关状态机
- `component/MaterialTintLiquidButton.kt`: 带主题着色的 Liquid 按钮
- `component/PageDescriptionText.kt`: 页面说明文本
- `component/SettingExpandableTextCard.kt`: 可展开文本卡片
- `component/SettingExpandableTextItem.kt`: 可展开文本设置项
- `component/SettingNavigationItem.kt`: 设置导航项
- `component/SettingToggleItem.kt`: 开关设置项
- `component/SettingsDetailFormScaffold.kt`: 设置详情表单骨架
- `component/SettingsDetailPageDefaults.kt`: 设置详情页默认参数
- `component/SettingsGroupCard.kt`: 设置组卡片
- `component/SettingsItemSurface.kt`: 设置项表面组件
- `component/SettingsListItem.kt`: 设置列表项
- `component/SettingsListPageContent.kt`: 设置列表页容器
- `component/SettingsSegmentedSelector.kt`: 分段选择器
- `component/SettingsToggleListItemCard.kt`: 开关列表卡片
- `component/SwipeDismissSettingsItemCard.kt`: 滑动删除卡片
- `component/TintLiquidButton.kt`: 着色 Liquid 按钮
- `component/settings/SettingsPageSpec.kt`: 设置页规格定义
- `component/settings/SettingsRowAction.kt`: 设置行动作
- `component/settings/SettingsRowSpec.kt`: 设置行规格
- `component/settings/SettingsSpecPageContent.kt`: 设置规格页内容
- `interaction/DragGestureInspector.kt`: 拖拽手势检测
- `interaction/InteractiveHighlight.kt`: 交互高亮
- `interaction/LiquidInteractiveLayer.kt`: Liquid 交互层
- `interaction/LiquidInteractiveStyle.kt`: Liquid 交互样式
- `nav/NavigationController.kt`: 导航控制器
- `nav/Navigator.kt`: 导航能力接口
- `nav/Page.kt`: 页面接口
- `nav/PageViewModel.kt`: 页面 ViewModel 基类
- `preview/ConfirmationLiquidDialogPreview.kt`: 确认弹窗预览
- `preview/LiquidDialogPreview.kt`: Liquid 弹窗预览
- `preview/SettingsInfraPreview.kt`: 设置基础设施预览
- `shape/G2Shapes.kt`: G2 形状定义

### `composebase/src/main/java/com/niki914/nexus/cb/`

- `BaseTheme.kt`: 基础主题
- `ComposeMVIViewModel.kt`: MVI ViewModel 基类
- `theme/LightColorScheme.kt`: 浅色配色方案
- `theme/Typography.kt`: 字体排版
- `theme/primaryLight.kt`: 主色调浅色定义
