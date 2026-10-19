---
name: zafiro-qa
description: 针对 Zafiro Android 真机/模拟器进行 QA 测试与端到端验证。通过 Release 版提供的 Zafiro MCP 服务操控手机、截屏与验证 Tool 表现，结合 ADB 注入沙箱环境与拉取调试日志，验证 Debug 版功能。
---

# Zafiro QA

通过真机已部署的 Release 版 Zafiro（提供 MCP 服务），驱动手机自动化测试与状态验证，对开发中的 Debug 版 Zafiro 进行功能 QA。

## 核心架构与操作红线

本项目在真机上采用 **双包共存（Dual-App）架构**：

| 版本 | 包名 | 职责 | 关键要求 |
|---|---|---|---|
| **Release 版** | `com.niki914.zafiro` | **测试基础设施**：在后台提供 Zafiro MCP 服务（端口 8791），作为 Agent 操控手机的手和眼。 | **【绝对红线】严禁 `kill`、`uninstall` 或清空 Release 版数据**，任何导致 Release 版退出的操作都会直接打断 MCP 连接！ |
| **Debug 版** | `com.niki914.zafiro.debug` | **被测目标（SUT）**：包含当前分支最新代码、新开发工具或待测修复，有独立的沙箱数据与日志目录。 | 所有环境注入（配置/设置）、UI 操控演练、日志拉取与断言，**均只针对 Debug 版**。 |

心智模型：**“坐在 Release 版（MCP 遥控器）上，伸手去测 Debug 版（被测目标）”**。

本文档所涉及的任何操作手机的行为：安装、触控和截屏等，必须有用户的授权才可执行！

---

## 阶段一：MCP 服务感知与就绪准备

在开始执行测试动作前，必须确认 Harness（宿主环境）是否已感知到 `zafiro` MCP 服务。

### 1. 感知判断

> [NOTE] 这一步无须读取任何配置，问问自己，自己是否有这些工具，如果没有，则是没感知到，无须通过任何配置文件判断这一点

检查当前环境中是否存在 `zafiro` 相关的 MCP 工具（如 `zafiro_screenshot`、`zafiro_launch_app`、`zafiro_load_skill`、`screenshot` 等）：
- **若能直接感知到**：说明连接正常，直接跳到 **阶段二** 开始测试。
- **若无法感知到**：按以下步骤建立端口转发并等待注入。

### 2. 建立 ADB 端口转发
Release 版 Zafiro 内置的 MCP HTTP 服务默认监听在 Android 设备的 `8791` 端口。

在 PC 终端执行端口转发：
```bash
adb forward tcp:8791 tcp:8791
```

### 3. 验证端点连通性
在 PC 侧通过 `curl` 检查端点健康度：
```bash
curl -I -s http://127.0.0.1:8791/mcp
```
- 若返回 HTTP 响应（通常为 `400` 或 `405` 等非连接拒绝状态），证明链路已通；
- 若出现 `Connection refused`，应停止操作并建议用户检查手机上 Release 版 Zafiro 是否在前台或后台存活。

### 4. 停下等待注入（重要）
当 `curl` 验证通过但当前 Agent 仍未感知到 MCP 工具时：
**必须停下并提示用户**：“已完成 adb forward (端口 8791)，端点连通正常。请确认在当前 Harness 中注入/重载 `zafiro` MCP 服务后继续。”

---

## 阶段二：双轨协同测试手段

MCP 就绪后，结合 **“MCP 远程驱动”** 与 **“ADB 沙箱底座”** 两种手段进行全方位 QA。

### 轨道 A：通过 MCP 驱动与验证功能

1. **加载手机控制能力**：
   在需要进行界面交互（点击、滑动、输入、截屏）前，必须优先调用 Zafiro MCP 上的 `load_skill`：
   ```json
   {
     "name": "phone-use"
   }
   ```
   加载完成后，即可解锁无障碍层与 Shell 层的点击、长按、滑动、输入以及真机截图能力。

2. **UI 场景自动化操控**：
   - 启动被测 Debug 应用：`launch_app(packageName="com.niki914.zafiro.debug")`；
   - 观察当前界面：调用 `screenshot` 获取屏幕画面（必要时结合 `view_image` 查看）；
   - 模拟用户交互：通过无障碍节点或坐标操作按钮、输入文本、切换对话。

3. **Tool 表现端到端验证**：
   正在开发/修复某个 Tool（如 Python 脚本工具、内置工具）时，无需在手机上人工手动点测，直接通过 MCP 调用该工具，检验其输入输出、异常处理和落盘状态是否符合预期。

### 轨道 B：通过 ADB 进行环境注入与日志排查

1. **沙箱环境注入（Settings / Config）**：
   Debug 版有独立沙箱目录 `/data/data/com.niki914.zafiro.debug/`：
   - 注入 MCP 配置、执行规则或记忆配置：
     通过 `adb push` 将准备好的 JSON 配置文件直接写入 Debug 版的 files 或 shared_prefs 目录；
   - 注入安全管控与权限策略：
     针对测试场景快速准备测试前置状态，免去复杂的 UI 点击配置。

2. **沙箱落盘日志拉取（Log Triage）**：
   Debug 构建除了将日志输出到 logcat 之外，还会将同一条日志落盘到独立沙箱，用于在 logcat 被系统日志冲刷时回溯。
   - **日志落盘路径**：`/data/data/com.niki914.zafiro.debug/files/logs/`
   - **日志命名规则**：`<进程>-<MMdd-HHmmss>.log`
     - 主进程：`main-*`
     - Chaquopy Python 进程：`python-*`
   - **日志轮转策略**：单文件满 8MB 切换，目录内最多保留 10 个。
   - **拉取命令**：
     ```bash
     # 通过 run-as 拉取 debug 沙箱日志（或 root 环境直接 cat）
     adb exec-out run-as com.niki914.zafiro.debug cat files/logs/<最新日志文件> > ./latest_debug.log
     # 或整包导出
     adb shell "run-as com.niki914.zafiro.debug tar -czf - files/logs/" | tar -xzf - -C ./debug_logs/
     ```

---

## 最佳实践：闭环验证流程

一次典型的 QA 验证闭环建议如下：

```
[环境准备] -> ADB 写入 Debug 测试配置 / 重置 Debug 数据
     ↓
[前置启动] -> MCP launch_app 启动 com.niki914.zafiro.debug
     ↓
[用例驱动] -> MCP load_skill('phone-use') -> 截图 -> 自动化触发待测交互/对话
     ↓
[状态核验] -> MCP 截屏检查 UI 卡片状态与交互反馈
     ↓
[底层确认] -> ADB 拉取 Debug 版沙箱日志 (files/logs/main-*.log) 确认无异常栈
```
