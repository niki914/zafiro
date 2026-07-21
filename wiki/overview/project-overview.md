# Project Overview

## 项目定位

Nexus 是一个 Android Xposed 模块，目标是在受支持的宿主进程中接管或替换原生语音助手回答链路，并把大模型运行时、配置同步、设置 UI 与宿主 Hook 编排到同一套工程里。

## 支持的宿主

- `ColorOS / Breeno`
  - 宿主包名：`com.heytap.speechassist`
  - 主 Hook：`app/src/main/java/com/niki914/nexus/agentic/mod/feat/oppo/BreenoChatHook.kt`
- `HyperOS / XiaoAi`
  - 宿主包名：`com.miui.voiceassist`
  - 主 Hook：`app/src/main/java/com/niki914/nexus/agentic/mod/feat/hyper/XiaoaiChatHook.kt`

## 模块边界

- `app/`：Xposed 入口、宿主分发、主 App UI、Breeno/XiaoAi 业务 Hook。
- `agent-runtime/`：LLM runtime、Prompt 组装、Tool 解析与执行、MCP 发现缓存、流式事件映射。
- `xposed-api/`：Xposed 事件类型、工具函数、共享常量与 Context 提供。
- `xposed-runtime/`：Xposed 运行时封装、Hook 基类、Activity/Context 观测与通用工具。
- `store/`：Store 持久化、IPC 桥接（`XIpcBridge`）、配置序列化与通知发送。
- `ui-kit/`：Compose UI 基建、导航栈、`LiquidScreen` 壳层、交互层与通用组件。

## 关键入口

### `app/src/main/java/`

- `app/src/main/java/a0/a0/a0/a0/a0/a0/Entrance.kt`：Xposed 入口；加载 `ContextHook`，初始化 repo/runtime，再按宿主路由业务 Hook。
- `app/src/main/java/com/niki914/nexus/agentic/app/MainActivity.kt`：主 App 入口；初始化 `XRepo`，计算启动页并进入 `NexusApp`。
- `app/src/main/java/com/niki914/nexus/agentic/mod/feat/AbstractAssistantHook.kt`：Breeno/XiaoAi 通用生命周期与 query 分流基类。
- `app/src/main/java/com/niki914/nexus/agentic/mod/XService.kt`：配置读取、远端刷新、通知发送门面。

### `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/`

- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/LLMController.kt`：运行时总入口；负责 refresh、stream、session reset、MCP 刷新时机。
- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/ConversationTurnState.kt`：轮次状态与 `turnId` 生成。
- `agent-runtime/src/main/java/com/niki914/nexus/agentic/chat/agentic/`：Prompt、Tool、MCP、shell 安全与流式事件映射。

### `store/src/main/java/com/niki914/nexus/store/`

- `store/src/main/java/com/niki914/nexus/store/XIpcBridge.kt`：统一屏蔽主进程与宿主进程的配置读写差异。
- `store/src/main/java/com/niki914/nexus/store/XRes.kt`：宿主枚举、IPC contract 与 Store URI 定义。
- `store/src/main/java/com/niki914/nexus/store/`：多 store 持久化实现；当前静态 store 对应 settings/hooks.json、settings/agents/main/config.json、settings/agents/main/memory.json、settings/tools/builtin_tools.json、settings/tools/custom_tools.json、settings/tools/mcp/servers.json、settings/rules/execution_rules.json、settings/rules/takeover_rules.json、settings/app_state.json，并按 MCP server 动态扩展到 settings/tools/mcp/cache/ 目录下按 serverId 命名的 JSON 文件。

## 设置模型

- `store/src/main/java/com/niki914/nexus/store/StoreDescriptorRegistry.kt` 显示 hook 配置仍通过 `web_settings` 语义读取，但运行时文件路径已落到 settings/hooks.json。
- `store/src/main/java/com/niki914/nexus/store/StoreDescriptorRegistry.kt` 与 `app/src/main/java/com/niki914/nexus/agentic/repo/XRepo.kt` 显示，面向 agent 的 LLM、memory、builtin、custom、MCP、execution、takeover、app state 已拆到独立 store，由 `XRepo` 聚合访问。
- `app/src/main/java/com/niki914/nexus/agentic/repo/XRepoRuntimeGateway.kt` 把其中与 runtime 相关的 settings 暴露给 `agent-runtime/`，不再依赖单一 local_settings.json 做全量配置交换。

### `app/src/main/java/com/niki914/nexus/agentic/app/ui/nexus/`

- `app/src/main/java/com/niki914/nexus/agentic/app/ui/nexus/NexusApp.kt`：主 App 导航栈与页面壳层装配。
- `app/src/main/java/com/niki914/nexus/agentic/app/ui/nexus/NexusPages.kt`：页面分发与 onboarding / settings 导航流。
- `app/src/main/java/com/niki914/nexus/agentic/app/ui/nexus/nav/NexusPage.kt`：页面模型定义。

### `ui-kit/src/main/java/com/niki914/nexus/agentic/app/ui/infra/`

- `ui-kit/src/main/java/com/niki914/nexus/agentic/app/ui/infra/LiquidScreen.kt`
- `ui-kit/src/main/java/com/niki914/nexus/agentic/app/ui/infra/LiquidScreenState.kt`
- `ui-kit/src/main/java/com/niki914/nexus/agentic/app/ui/infra/LiquidScreenSwipeContent.kt`
- `ui-kit/src/main/java/com/niki914/nexus/agentic/app/ui/infra/nav/NavigationController.kt`
