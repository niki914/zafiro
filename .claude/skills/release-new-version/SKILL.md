---
name: "release-new-version"
description: "Use when preparing a Zafiro release commit that requires syncing app/build.gradle.kts version fields with the latest GitHub release."
---

# Release New Version

## Overview

这个 skill 用于执行 Zafiro 的发版提交流程。

它的核心规则只有三条：

- 版本事实以 `app/build.gradle.kts` 为准
- GitHub 最新 release tag 作为下一次 `versionCode` 的基线
- `versionName` 必须先询问用户，默认只建议递增一个小版本号

当前仓库已验证的版本映射规则如下：

- GitHub tag 格式：`<versionCode>-<versionName>`
- 例如：`1-0.0.1`
- 对应端上：
  - `versionCode = 1`
  - `versionName = "0.0.1"`

## When to Use

在以下场景使用：

- 用户明确要求“发版”“升版本”“准备 release 提交”
- 需要根据 GitHub 最新 release 自动推导下一个 `versionCode`
- 需要修改 `app/build.gradle.kts` 中的版本字段并产出 release APK

不要用于：

- 只查询当前版本，不需要修改文件
- 只构建 debug 包
- 普通功能开发或 bug 修复

## Required Inputs

开始前必须确认以下事实：

- 读取 `app/build.gradle.kts`
- 找到 `defaultConfig` 下的 `versionCode` 与 `versionName`
- 读取 GitHub 最新 release tag
- 询问用户希望的新 `versionName`

如果用户没有明确指定新版本号，默认建议：

- 旧版本：`0.0.1`
- 新版本：`0.0.2`

## Version Rule

### 1. 读取本地版本

只读取以下文件：

- `app/build.gradle.kts`

目标字段：

- `versionCode = <Int>`
- `versionName = "<String>"`

### 2. 读取 GitHub 最新 release

优先使用备用脚本：

```bash
python3 .trae/skills/release-new-version/get_latest_release_version.py
```

默认目标仓库：

- `Xposed-Modules-Repo/com.niki914.nexus.agentic`

脚本输出应为单行 tag，例如：

```text
1-0.0.1
```

### 3. 解析与校验

将 tag 按第一个 `-` 拆分：

- 左侧：GitHub 最新 `versionCode`
- 右侧：GitHub 最新 `versionName`

示例：

```text
1-0.0.1 -> versionCode=1, versionName=0.0.1
```

同时校验本地 `app/build.gradle.kts` 当前值是否与该 tag 对应。

如果不一致：

- 先停止自动修改
- 明确告诉用户 GitHub 与本地版本状态不一致
- 请用户决定是否继续发版

### 4. 计算下一版

下一版规则：

- 新 `versionCode = GitHub 最新 versionCode + 1`
- 新 `versionName` 需要用户确认

默认建议：

- 如果 GitHub 最新是 `1-0.0.1`
- 则建议新值为：
  - `versionCode = 2`
  - `versionName = "0.0.2"`

## Execution Workflow

### Step 1. 读取并核对版本

必须先做：

1. 读 `app/build.gradle.kts`
2. 运行备用脚本获取 GitHub 最新 tag
3. 对比本地值与 GitHub 值

### Step 2. 询问用户新版本号

必须显式询问用户：

- 你要发的 `versionName` 是什么？

默认建议文案可以直接使用：

```text
GitHub 最新 release 是 1-0.0.1，本地 app/build.gradle.kts 当前是 versionCode=1、versionName=0.0.1。
按规则下一次发版建议使用 versionCode=2。
如果你没有特殊要求，建议 versionName 使用 0.0.2。
请确认是否使用 0.0.2，或告诉我你想要的新 versionName。
```

### Step 3. 修改版本字段

只修改：

- `app/build.gradle.kts`

仅更新：

- `versionCode`
- `versionName`

不要顺手修改其他构建配置。

### Step 4. 构建 release

版本更新完成后，执行：

```bash
./gradlew assembleRelease
```

### Step 5. 打开产物目录

构建完成后，执行：

```bash
open app/build/outputs/apk/release/
```

## Command Reference

```bash
python3 .trae/skills/release-new-version/get_latest_release_version.py
```

```bash
./gradlew assembleRelease
```

```bash
open app/build/outputs/apk/release/
```

## Common Mistakes

- 直接拿本地 `versionCode + 1`，却不先看 GitHub 最新 release
- 把 GitHub tag `1-0.0.1` 误读成完整字符串版本，而不拆成 `Int` 和 `String`
- 未经确认就自动改 `versionName`
- 修改了 `build.gradle.kts` 里的无关配置
- 发现 GitHub 与本地版本不一致时仍然继续提交

## Output

完成时给出简短结果：

```text
发版结果

GitHub 最新:
- 1-0.0.1

本地原始版本:
- versionCode=1
- versionName=0.0.1

本次更新后:
- versionCode=2
- versionName=0.0.2

已执行:
- ./gradlew assembleRelease
- open app/build/outputs/apk/release/
```
