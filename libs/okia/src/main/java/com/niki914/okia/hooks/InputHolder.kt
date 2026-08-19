package com.niki914.okia.hooks

/**
 * beforeInput 的可改数据载体。字段只读暴露（private set）；写入走 write 并记录
 * 签名字段（最后写入者，审计可追溯）。
 * write 实现（T5）：text / lastWriter 为 private set 属性；write 改值并记录
 * 签名，多次 write 后者覆盖、lastWriter 为最后写入者。
 * 改写文本落点 = 本次请求历史投影（RealAgentLoop 在 buildRequest 前替换
 * history 末尾 User 的文本块，树不变——UI 显示原文、模型收到改写版，
 * 对齐 §5.8 分层预期；改写作用域 = 本回合第一次请求）。
 * Design source: pi extensions mutation（原地改 event.input，后续 handler 可见）。
 */
class InputHolder(
    initialText: String
) {

    // 当前文本（write 后为改写值）；private set 保持只读暴露
    var text: String = initialText
        private set

    // 最后写入者签名（write 记录）；未写入为 null
    var lastWriter: String? = null
        private set

    // 改写输入文本并记录签名
    fun write(text: String, signature: String) {
        this.text = text
        lastWriter = signature
    }
}
