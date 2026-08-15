# LOG_TASK.md

日志修补分支的任务前因后果与进度记录。

## 前因（为什么做）

- 本分支（`worktree-feat+logging`）的上一笔提交 `bf6d95f` 引入了新日志系统 `libs/logging`：`com.niki914.logging.Logger`（分级 + scope 门控 + Android/JVM 双后端）。
- 但该 `Logger` 目前**尚未被任何业务模块引用**，代码里还散落着旧日志写法，导致日志不统一、关键链路无日志，debug 时难以核对功能准确性。
- 因此本分支要做一次日志修补：统一旧日志 + 给关键业务补埋点。

## 任务（做什么）

> 这个分支是一个日志修补分支，任务：
> - 清理/统一我们新增日志系统以外的日志（不管libs:）下的模块
> - 为关键业务添加日志来 debug 方便检查功能准确性
> 关键业务：对话功能、对话切换、对话加载耗时、设置页配置、模块hook点健康度巴啦巴啦，你梳理一下，然后写一个 LOG.md …… 然后我会改这个文档进行增删

拆解为三步：

1. **清理/统一旧日志**：把非 `libs:` 模块里、新 `Logger` 之外的日志统一收编到 `com.niki914.logging.Logger`。
2. **给关键业务补日志**：围绕对话功能、对话切换、对话加载耗时、设置页配置、模块 hook 点健康度（含顺带梳理出的渲染管线、Takeover 决策、工具执行），列出需要埋点的函数。
3. **产出 `LOG.md`**：按业务域分组、无序列表列出埋点函数，作为待办清单；由用户增删定稿后再落地实现。

## 现状盘点（已梳理）

新 `Logger` 之外的日志：

| 位置 | 现状 | 处置方向 |
|---|---|---|
| `xposed-api/.../util/Xlogging.kt` | `xlog`/`xtlog` → `Log.e("nexus-x-log")`，被 xposed-runtime / xposed-api 十余处调用 | 统一到 `Logger` |
| `ui-kit/.../base/ComposeMVIViewModel.onError` | 直接 `Log.e` | 改 `Logger.e` |
| `XEvent.emit` | 内部 `xtlog`（且 `emit` 当前 `return // TODO` 整体关闭） | 随 Xlogging 替换 |

> 注：`HomeChatComponents.kt` 的 `println(answer)` 是 Markdown 预览字符串字面量，非真实日志，已排除。

## 进度

- [x] 梳理代码结构，定位新日志系统与旧日志残留
- [x] 按业务域梳理关键业务埋点函数，产出 `LOG.md` 草稿
- [x] 用户审阅/增删 `LOG.md`（定稿：注释英语、TAG 格式 `niki914_nexus_XXX`、XEvent 改整体移除）
- [x] 按定稿 `LOG.md` 统一旧日志（`xlog/xtlog`、`Log.e`、XEvent 整包移除）
- [x] 按定稿 `LOG.md` 为关键业务补埋点（Chunk 1-6 全部完成，见 `Progress.md` 检查点 #3-#8）
- [x] 自测/验证日志输出（全模块编译 + 554 单测全绿：agent-runtime 319 / app 216 / store 19）
