package com.niki914.zafiro.api.model

/**
 * 用户附加的一个文件 / 文件夹引用。
 *
 * ## 为什么只有一个 `path`
 *
 * - **显示名就是 basename**（「诚实的文件命名」）。路径是唯一真源，
 *   另存一个 `name` 字段迟早会出现「名字和硬盘上的东西脱钩」。
 * - **目录靠末尾的 `/` 表达**，因此不需要 `isDirectory` 字段。
 *   注入块里也是这个写法，历史恢复时按同一个规则解回来。
 * - 文件**不拷贝**，所以这是用户机器上的真实路径，不是沙箱路径。
 *
 * ## 落盘
 *
 * 本类型不进 Room。落到会话树里的只有注入的那段文本（`TurnTextComposer` 产的
 * `<zfr-files>` 块），历史恢复时再从文本里解回本类型。
 */
data class FileRef(val path: String)
