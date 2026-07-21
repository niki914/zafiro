# Nexus Wiki Index

> 本文件是 `nexus-lookup` 的总入口。任何检索先从这里开始，再按场景路由到对应子文档。

## 使用目标

这套 wiki 只负责三件事：

- 给出稳定的知识地图
- 说明应该去哪里继续读源码
- 区分稳定事实、进行中设计、未来提案

它不负责存放源码副本，也不应该演变成另一个 `CLAUDE.md`。

## 知识地图

### overview/ — 项目总览与当前状态

| 文件 | 层级 | 源码依据 | 预期内容 |
| --- | --- | --- | --- |
| `overview/project-overview.md` | Stable | `settings.gradle.kts`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml` | 项目定位、模块结构、宿主矩阵、关键入口、主要目录 |
| `overview/current-status.md` | In Progress | `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/buildin/BuiltinToolRegistry.kt`, `store/src/main/java/com/niki914/nexus/store/StoreDescriptorRegistry.kt`, `app/src/main/java/com/niki914/nexus/agentic/app/ui/nexus/NexusPages.kt` | 当前能力分层、半落地链路、保留提案与未核实项 |

### architecture/ — 核心机制与链路

| 文件 | 层级 | 源码依据 | 预期内容 |
| --- | --- | --- | --- |
| `architecture/boot-sequence.md` | Stable | `app/src/main/java/a0/a0/a0/a0/a0/a0/Entrance.kt`, `app/src/main/java/com/niki914/nexus/agentic/app/App.kt`, `agent-runtime/src/main/java/com/niki914/nexus/agentic/runtime/settings/RuntimeEnvironment.kt` | Xposed 入口、Context 捕获、宿主路由、Hook 安装顺序 |
| `architecture/turn-state.md` | Stable | `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/ActiveTurnStore.kt`, `app/src/main/java/com/niki914/nexus/agentic/mod/feat/AbstractAssistantHook.kt` | 会话状态、turnId、takeover 语义、session reset |
| `architecture/render-pipeline.md` | Stable | `app/src/main/java/com/niki914/nexus/agentic/mod/feat/oppo/BreenoChatHook.kt`, `app/src/main/java/com/niki914/nexus/agentic/mod/feat/hyper/XiaoaiChatHook.kt` | Breeno 与 XiaoAi 的响应注入模型与差异 |
| `architecture/config-resolution.md` | Stable | `app/src/main/java/com/niki914/nexus/agentic/repo/XRepo.kt`, `app/src/main/java/com/niki914/nexus/agentic/repo/XRepoRuntimeGateway.kt`, `store/src/main/java/com/niki914/nexus/store/XIpcStoreRepository.kt` | 本地配置、远程配置、IPC、server 回退逻辑 |
| `architecture/llm-runtime.md` | In Progress | `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/LLMController.kt`, `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/ToolManager.kt`, `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/buildin/BuiltinToolRegistry.kt` | LLM 控制器、Prompt、Tool、MCP 与流式事件运行时 |

### domains/ — 业务专项导航

| 文件 | 层级 | 源码依据 | 预期内容 |
| --- | --- | --- | --- |
| `domains/coloros-breeno.md` | Stable | `app/src/main/java/com/niki914/nexus/agentic/mod/feat/oppo/BreenoChatHook.kt`, `app/src/main/java/com/niki914/nexus/agentic/mod/feat/oppo/BreenoFeedbackAssembler.kt` | Breeno 的注入链路、卡片劫持与全量刷新渲染机制 |
| `domains/hyperos-xiaoai.md` | Stable | `app/src/main/java/com/niki914/nexus/agentic/mod/feat/hyper/XiaoaiChatHook.kt`, `app/src/main/java/com/niki914/nexus/agentic/mod/feat/hyper/XiaoaiRenderSession.kt` | XiaoAi 的输入捕获、指令白名单拦截与流式分片注入机制 |
| `domains/ui-shell/index.md` | In Progress | `app/src/main/java/com/niki914/nexus/agentic/app/ui/nexus/NexusApp.kt`, `app/src/main/java/com/niki914/nexus/agentic/app/ui/nexus/nav/NexusPage.kt`, `app/src/main/java/com/niki914/nexus/agentic/app/ui/nexus/NexusPages.kt` | UI Shell、onboarding、settings tree 与导航状态机 |

### reference/ — 高频索引与注册表

| 文件 | 层级 | 源码依据 | 预期内容 |
| --- | --- | --- | --- |
| `reference/source-map.md` | Stable | `.wiki_generator/source_inventory.md`, `settings.gradle.kts`, `app/src/main/AndroidManifest.xml` | 关键类、目录、文件入口的相对路径地图 |


## 检索建议

| 场景 | 推荐阅读顺序 |
| --- | --- |
| 工程结构、模块职责、关键入口 | `overview/project-overview.md` -> `reference/source-map.md` |
| 现在项目做到哪、哪些能力还没正式落地 | `overview/current-status.md` -> `reference/source-map.md` |
| Xposed 启动、宿主匹配、Hook 安装顺序 | `architecture/boot-sequence.md` -> `reference/source-map.md` |
| 会话状态、takeover、turnId、reset | `architecture/turn-state.md` |
| Breeno 响应注入、卡片刷新、相关子 Hook | `domains/coloros-breeno.md` -> `architecture/render-pipeline.md` |
| XiaoAi 响应目标捕获、文本流/TTS 拦截、分片注入 | `domains/hyperos-xiaoai.md` -> `architecture/render-pipeline.md` |
| 配置来源、IPC、server、版本回退 | `architecture/config-resolution.md` -> `reference/source-map.md` |
| LLMController、Prompt、Tool、MCP、事件流 | `architecture/llm-runtime.md` -> `overview/current-status.md` |
| UI、onboarding、settings tree、Liquid Glass、导航状态机 | `domains/ui-shell/index.md` -> `reference/source-map.md` |
| 屏幕 Accessibility 树捕获、手机控制操作、Pointer overlay | `app/.../mod/feat/NexusAccessibilityService.kt` -> `agent-runtime/.../accessibility/AccessibilityController.kt` -> `app/.../overlay/PointerOverlay.kt` |
| Skill 文件管理、运行加载、设置 UI | `app/.../repo/SkillApi.kt` -> `agent-runtime/.../buildin/impl/LoadSkillBuiltin.kt` -> `app/.../ui/nexus/content/SkillsSettingsContent.kt` |
