package com.niki914.zafiro.repo

/**
 * 宽松的 token 数量文本解析：支持 k / m / b 后缀，大小写与内部空格不敏感，不支持小数。
 * 纯函数：UI 校验与 gateway 解析共用同一实现，保证「填进去的」和「跑起来的」一致。
 */
internal object TokenCountText {
    private val pattern = Regex("""^(\d+)([kmb])?$""")

    /** 解析失败返回 null：空白、非数字、负号、小数、未知后缀、超出 Long 的位数都算失败。
     *  乘法溢出同样返回 null（multiplyExact 抛错，不会 wrap 成一个假的小值）。 */
    fun parse(text: String): Long? {
        val normalized = text.trim().lowercase().filterNot(Char::isWhitespace)
        val match = pattern.matchEntire(normalized) ?: return null
        val (numberPart, suffix) = match.destructured
        val number = numberPart.toLongOrNull() ?: return null
        val multiplier = when (suffix) {
            "k" -> 1_000L
            "m" -> 1_000_000L
            "b" -> 1_000_000_000L
            else -> 1L
        }
        return runCatching { Math.multiplyExact(number, multiplier) }.getOrNull()
    }
}
