package com.niki914.okia.hooks

/**
 * beforeInput 的可改数据载体。字段只读暴露；写入走 write 并记录
 * 签名字段（最后写入者，审计可追溯）。
 * 骨架期只声明字段，write 留空（没有消费者，不设计 API）。
 * Design source: pi extensions mutation（原地改 event.input，后续 handler 可见）。
 */
class InputHolder(
    val text: String
) {

    // 最后写入者签名（write 记录）
    val lastWriter: String? = null

    // 改写输入文本并记录签名
    fun write(text: String, signature: String): Unit = TODO()
}
