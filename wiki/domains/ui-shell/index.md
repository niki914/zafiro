# UI Shell Domain

## 结构边界

UI Shell 当前分成两层：

- `app/` 侧入口、页面状态和路由装配集中在 `app/src/main/java/com/niki914/nexus/agentic/app/MainActivity.kt`、`app/src/main/java/com/niki914/nexus/agentic/app/ui/nexus/NexusApp.kt`、`app/src/main/java/com/niki914/nexus/agentic/app/ui/nexus/NexusPages.kt`。
- `composebase/` 侧壳层、导航控制和通用设置组件集中在 `composebase/src/main/java/com/niki914/nexus/agentic/app/ui/infra/LiquidScreen.kt`、`composebase/src/main/java/com/niki914/nexus/agentic/app/ui/infra/nav/NavigationController.kt`、`composebase/src/main/java/com/niki914/nexus/agentic/app/ui/infra/component/SettingsListPageContent.kt`。

当前导航栈由 `composebase/src/main/java/com/niki914/nexus/agentic/app/ui/infra/nav/NavigationController.kt` 驱动，并由 `app/src/main/java/com/niki914/nexus/agentic/app/ui/nexus/NexusApp.kt` 持有页面栈与返回逻辑；这里没有接入 Jetpack Navigation。

## 当前导航事实

- `MainActivity` 解析 `StartupAssistantUi`，调用 `AppLaunchDecision.resolve()`，再进入 `NexusApp`。
- `AppLaunchDecision` 只根据 `XRepo.onboardingCompleted()` 决定首屏是 `StartupPage` 还是 `HomePage`。
- `NexusApp` 持有导航栈、根返回逻辑、会话选中状态和 page chrome host。
- `NexusPages` 按 `NexusPage` 分发到 route 层。
- 首页左上动作会进入 `ConversationHistoryPage`，右上菜单提供“新建会话”和“设置”。
- `ConversationHistoryPage` 已接通页面路由与内容展示。但其选中/删除操作并不是页面自闭环的，而是由 `NexusApp` 持有 `activeConversationId`，并将操作结果回调给主页 `HomeChatViewModel`，由其驱动底层的 `replaceHistory` 与 `resetConversation`。
- 设置树首页通过 `app/src/main/java/com/niki914/nexus/agentic/app/ui/nexus/content/SettingsHomePageContent.kt` 生成分组入口。进入详情页时，`SettingsDetailPageContent` 受到 `SettingsViewModel` 的 `visibleGroups` 门控：只有在可见分组内的枚举才会真正分发到对应的内容页，否则直接被拦截。

## 页面与路由

### app/src/main/java/com/niki914/nexus/agentic/app/

- `MainActivity.kt`：应用入口，启动 `NexusApp`。
- `EXT.kt`：把当前宿主环境映射成 `StartupAssistantUi`。

### app/src/main/java/com/niki914/nexus/agentic/app/ui/nexus/

- `NexusApp.kt`：顶层 UI Shell，管理导航栈、page chrome、根返回和当前会话状态。
- `NexusPages.kt`：页面分发中枢，包含 `ConversationHistoryPage`、设置页和详情页路由分派。
- `PageChrome.kt`：页面级标题、左右动作、菜单、返回处理和 overlay 注入点。

### app/src/main/java/com/niki914/nexus/agentic/app/ui/nexus/nav/

- `NexusPage.kt`：页面定义与 `routeKey`，包含 `ConversationHistoryPage`、`SettingsHomePage` 以及各类详情页。
- `NexusSettingsGroup.kt`：当前设置分组枚举，包含 `ModelConfig`、`Memory`、`BuiltinTools`、`Skills`、`CustomShellTools`、`Mcp`、`Takeover`、`ExecutionRules`、`About`。

### app/src/main/java/com/niki914/nexus/agentic/app/ui/nexus/model/

- `AppLaunchDecision.kt`：首屏决策，只看 onboarding 完成态。
- `StartupAssistantUi.kt`：当前宿主类型枚举。

### app/src/main/java/com/niki914/nexus/agentic/app/ui/nexus/route/

- `StartupPageRoute.kt`、`ProviderPickPageRoute.kt`、`ConfigurePageRoute.kt`、`DonePageRoute.kt`：onboarding 主流程。
- `HomePageRoute.kt`：从首页进入会话历史或设置首页。
- `ConversationHistoryPageRoute.kt`：加载会话列表、处理选中、删除当前会话和返回。
- `SettingsHomePageRoute.kt`、`SettingsDetailPageRoute.kt`、`SettingsProviderPickPageRoute.kt`、`SettingsConfigurePageRoute.kt`：设置树首页、详情页分发，以及从设置重新进入 provider 选择与配置。
- `SkillDetailRoute.kt`：Skill 详情编辑页路由。

### app/src/main/java/com/niki914/nexus/agentic/app/ui/nexus/content/

- `HomePageContent.kt`：聊天首页内容，向 page chrome 注册历史入口和右上菜单。
- `ConversationHistoryPageContent.kt`：会话历史列表、空态、错误态、删除确认。
- `SettingsHomePageContent.kt`：设置首页分组列表。
- `SettingsDetailPageContent.kt`：把当前设置分组分发到各自内容页。
- `ConfigurePageContent.kt`、`DonePageContent.kt`：onboarding 配置页与完成页。
- `EditableSettingsDetailScaffold.kt`：设置详情共用壳，处理未保存返回和删除按钮 chrome。
- `SkillsSettingsContent.kt`：Skill 设置首页，列出已安装 skill、支持导入和开关。
- `SkillDetailContent.kt`：Skill 详情编辑内容页，支持加载、编辑、保存与删除。

## ConversationHistory 链路与状态桥

历史页不是一个自闭环的孤立页面，其状态流转强依赖与 `HomeChatViewModel` 的状态桥接：

- 页面定义：`app/src/main/java/com/niki914/nexus/agentic/app/ui/nexus/nav/NexusPage.kt`
- 入口动作：`app/src/main/java/com/niki914/nexus/agentic/app/ui/nexus/content/HomePageContent.kt`
- 首页路由与回调桥：`app/src/main/java/com/niki914/nexus/agentic/app/ui/nexus/route/HomePageRoute.kt`
- 全局状态持有：`app/src/main/java/com/niki914/nexus/agentic/app/ui/nexus/NexusApp.kt`（持有 `activeConversationId` 与删除回调）
- 页面内容：`app/src/main/java/com/niki914/nexus/agentic/app/ui/nexus/content/ConversationHistoryPageContent.kt`

这个链路当前已经覆盖：

- 从首页左上角进入历史页，加载 `ConversationRepo.listConversations()`。
- 选中会话后：`NexusApp` 将事件送回 `HomePageRoute`，由 `HomeChatState` 从 Repo 取出历史并 `replaceHistory()` 回灌给 `LLMController`。
- 删除会话：如果是当前会话被删，`HomeChatState` 会清空当前指针并重置 LLM 运行时；否则只删 Repo。

## Shell 与通用基建

### composebase/src/main/java/com/niki914/nexus/agentic/app/ui/infra/

- `LiquidScreen.kt`：顶层 Liquid Shell，管理 action bar、blur layer、viewport avoidance 和 dialog host。
- `LiquidScreenSwipeContent.kt`：页面切换动画壳。
- `composebase/src/main/java/com/niki914/nexus/agentic/app/ui/infra/nav/NavigationController.kt`：自定义导航栈。

### composebase/src/main/java/com/niki914/nexus/agentic/app/ui/infra/component/

- `SettingsListPageContent.kt`：设置列表页通用容器。
- `SettingsDetailFormScaffold.kt`：设置详情表单骨架。
- `SwipeDismissSettingsItemCard.kt`：会话历史和其他可滑删项复用的列表卡片。
- `SettingsGroupCard.kt`：空态、错误态等分组卡片容器。

## 检索建议

- 看启动后先落哪个页面：先读 `app/src/main/java/com/niki914/nexus/agentic/app/ui/nexus/model/AppLaunchDecision.kt`，再读 `app/src/main/java/com/niki914/nexus/agentic/app/ui/nexus/NexusApp.kt`。
- 看首页顶栏、历史入口和菜单：先读 `app/src/main/java/com/niki914/nexus/agentic/app/ui/nexus/content/HomePageContent.kt`，再读 `app/src/main/java/com/niki914/nexus/agentic/app/ui/nexus/PageChrome.kt`。
- 看会话历史完整链路：按上面的 `ConversationHistory` 链路顺序读。
- 看设置树结构：先读 `app/src/main/java/com/niki914/nexus/agentic/app/ui/nexus/nav/NexusSettingsGroup.kt`，再读 `app/src/main/java/com/niki914/nexus/agentic/app/ui/nexus/content/SettingsHomePageContent.kt` 与 `app/src/main/java/com/niki914/nexus/agentic/app/ui/nexus/content/SettingsDetailPageContent.kt`。

## 当前边界

- UI Shell 不只是一组页面；顶层壳、导航控制、page chrome 和通用设置组件都在 `composebase/`。
- 设置树本身已经接通到当前全部枚举分组，但具体功能成熟度仍需结合 `wiki/overview/current-status.md` 和对应源码继续判断。
- 宿主差异不在 `AppLaunchDecision`；首屏分流只区分是否完成 onboarding，宿主相关差异发生在 onboarding 流程和后续宿主链路。
