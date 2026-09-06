package com.niki914.okia.message

/**
 * 思考强度（reasoning effort）。对齐 pi ThinkingLevel / Eta ReasoningEffort。
 * 协议层负责把 level 映射为各 Provider 的请求字段（reasoning_effort /
 * reasoning.effort / thinking+output_config），映射为恒等（值即 wire 值）。
 * OFF = 显式关闭思考；null = 不发送任何思考字段（Provider 默认行为）。
 * Design source: pi packages/ai types.ts ThinkingLevel；Eta Reasoning.kt。
 */
enum class ThinkingLevel(val wireValue: String) {
    OFF("none"),
    MINIMAL("minimal"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    XHIGH("xhigh"),
    MAX("max");

    /** 是否要求 Provider 输出思考（非 OFF 且非 null）。 */
    val requestsThinking: Boolean
        get() = this != OFF

    companion object {
        val Default = HIGH

        /** 宽松解析 wire 值（大小写不敏感，兼容 none/extra_high 等别名）；未知值回退 Default。 */
        fun fromWire(value: String?): ThinkingLevel {
            val normalized = value?.trim()?.lowercase().orEmpty()
            if (normalized.isEmpty()) return Default
            return entries.firstOrNull { it.wireValue == normalized } ?: when (normalized) {
                "off", "disabled" -> OFF
                "x-high", "extra_high", "extra-high" -> XHIGH
                else -> Default
            }
        }

        fun toWire(level: ThinkingLevel): String = level.wireValue
    }
}
