## 未完成项目

[] MEDIUM: 通过参考开源项目重构宿主业务
[] HARD: 实现一个 Replay 功能，用户可以录制一段操作，作为工具保存下来，Agent 通过调用这个工具来重放用户的操作
[] EAZY: `Build.VERSION.SDK_INT >= Build.VERSION_CODES.O` 这样的版本相关的无用判断
[] MEDIUM: 内联包名清理，使用默认参数而放在构造函数里面的成员
[] HARD: 处理散落的 `TODO`
[] EAZY: Composer 附件多选：Photos 从 `PickVisualMedia` 换到 `PickMultipleVisualMedia`（同一个系统相册，不是自研 picker），Files 侧允许多选 | Skills 导入代码梳理（Merge 主界面的导入功能）
[] MEDIUM: 附件 Recents：在选项单里列出最近附加过的文件 / 文件夹（需要一份最近附件列表的持久化）
[] EZAT: 添加一个 runCatching 封装到 :api 专门处理 cancellation exception 等异常，然后全仓搜索 try / runCatching 做清扫
[] HARD: 多 Agent 架构 + PR#282