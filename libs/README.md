# Vendored Libraries

libterm 原本是 JitPack 依赖，现已作为本地模块集成进本仓库，不再拉取远程制品；okia、logging 是本地新增模块：

| 目录 | 来源 | 模块名 |
| --- | --- | --- |
| `libterm/` | https://github.com/niki914/libterm @ `55d02c3` | `:libs:libterm-core` / `:libs:libterm-runtime` / `:libs:libterm-backend-{libsu,shizuku,ssh}` |
| `okia/` | 本地开发的 LLM 回合执行库，设计依据 `docs/okia.md` | `:libs:okia` |
| `logging/` | 本地日志模块 | `:libs:logging` |

## 为什么集成

消除发版节奏耦合，库侧契约变更与消费方在同一仓库同一 PR 内落地；单一消费者、单一维护者，独立发布的隔离边界没有收益；跨库边界调试与编译锁定。

## 维护约定

- **本仓库是开发真相源**：库侧改动直接在这里改
- 上游仓库（`~/repo/android/libterm`）在需要对外发布新版本时，把 `libs/` 下的源码同步回去再 tag；平时不维护
- 同步方向：上游 → `libs/`（拉取）仅在"从上游拿新代码"时发生，需同时更新上方表格的 commit

## 构建适配说明

- build 文件已改写以适配本仓库工具链（AGP 9.1.1 / Kotlin 2.2.0 / Gradle 9.3.1）：
  - 移除 `maven-publish` 与 `publishing` 块
  - 移除版本目录（`libs.plugins.*` / `libs.*`）引用，改为直接坐标
  - `kotlinOptions { jvmTarget }` → `kotlin { compilerOptions { jvmTarget } }`（AGP 9 移除旧 DSL）
  - `libterm-runtime` 的 `project(":libterm-*")` → `project(":libs:libterm-*")`
  - android 模块不应用 `org.jetbrains.kotlin.android`（AGP 9 内置 Kotlin）；`kotlin("test")` 换 `org.jetbrains.kotlin:kotlin-test-junit:2.2.10`（内置 Kotlin 下默认变体不含 JUnit）
- 单测随本仓库构建：`./gradlew :libs:libterm-core:test :libs:libterm-runtime:testDebugUnitTest`
- okia 骨架编译：`./gradlew :libs:okia:compileDebugKotlin`
