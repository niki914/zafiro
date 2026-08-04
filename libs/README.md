# Vendored Libraries

这两个库原先是 JitPack 依赖，现已作为本地模块集成进本仓库，不再拉取远程制品：

| 目录 | 来源 | 集成 commit | 模块名 |
| --- | --- | --- | --- |
| `kai/` | vendored 自 https://github.com/niki914/s3ss10n @ `e5803ca`（release 2.1.6），本地重命名为 `kai` | — | `:libs:kai` |
| `libterm/` | https://github.com/niki914/libterm @ `55d02c3` | — | `:libs:libterm-core` / `:libs:libterm-runtime` / `:libs:libterm-backend-{libsu,shizuku,ssh}` |
| `okai/` | 本地开发的 LLM turn 引擎（kai 重设计的骨架，仅接口与数据类型，无实现），实现依据 `docs/kai-prd.md` | — | `:libs:okai` |

## 为什么集成

- 消除发版节奏耦合：kai（原 s3ss10n）的契约变更（如 sealed `SessionEvent` 追加）不再需要先发版本、Nexus 再适配，两处改动在同一仓库同一 PR 内落地
- 单一消费者、单一维护者，独立发布的隔离边界没有收益
- 跨库边界调试与编译锁定

## 维护约定

- **本仓库是开发真相源**：库侧改动直接在这里改（如 `libs/kai/` 内的 reasoning 事件、idle 计时语义）
- 上游仓库（`~/repo/android/s3ss10n`、`~/repo/android/libterm`）在需要对外发布新版本时，把 `libs/` 下的源码同步回去再 tag；平时不维护
- 同步方向：上游 → `libs/`（拉取）仅在"从上游拿新代码"时发生，需同时更新上方表格的 commit

## 构建适配说明

- build 文件已改写以适配本仓库工具链（AGP 9.1.1 / Kotlin 2.2.0 / Gradle 9.3.1）：
  - 移除 `maven-publish` 与 `publishing` 块
  - 移除版本目录（`libs.plugins.*` / `libs.*`）引用，改为直接坐标
  - `kotlinOptions { jvmTarget }` → `kotlin { compilerOptions { jvmTarget } }`（AGP 9 移除旧 DSL）
  - `libterm-runtime` 的 `project(":libterm-*")` → `project(":libs:libterm-*")`
  - android 模块不应用 `org.jetbrains.kotlin.android`（AGP 9 内置 Kotlin）；`kotlin("test")` 换 `org.jetbrains.kotlin:kotlin-test-junit:2.2.10`（内置 Kotlin 下默认变体不含 JUnit）
- 单测随本仓库构建：`./gradlew :libs:kai:testDebugUnitTest :libs:libterm-core:test :libs:libterm-runtime:testDebugUnitTest`
