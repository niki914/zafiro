package com.niki914.zafiro.settings.model

import com.niki914.okia.loop.RepetitionConfig

/**
 * 复读检测灵敏度档位。声明序即 UI 顺序，也是「检测强度」的单调轴：
 * [Off] = 0 档（不检测），其后越靠后越严格（单元越短、次数越少）。
 *
 * 判据是「尾部短单元连续重复次数」：单元 = minUnit 个字符，重复满 maxRepeats
 * 次即熔断。minUnit 越小、maxRepeats 越少，越容易命中。
 *
 * [wireValue] 是落盘键值，不落数值：以后微调某档参数，存量用户无需迁移。
 * [Off] 是显式关闭，映射为「单元极短也凑不满次数」的不可达阈值。
 */
enum class RepetitionSensitivity(
    val wireValue: String,
    val minUnit: Int,
    val maxRepeats: Int,
) {
    Off("off", minUnit = Int.MAX_VALUE, maxRepeats = Int.MAX_VALUE),
    Relaxed("relaxed", minUnit = 8, maxRepeats = 24),
    Loose("loose", minUnit = 6, maxRepeats = 16),
    Standard("standard", minUnit = 5, maxRepeats = 14),
    Strict("strict", minUnit = 4, maxRepeats = 10),
    Stricter("stricter", minUnit = 4, maxRepeats = 7),
    Strictest("strictest", minUnit = 3, maxRepeats = 6);

    /** 灵敏度档位 → okia 复读阈值。关闭档的阈值不可达，检测器自然不触发。 */
    fun toRepetitionConfig(): RepetitionConfig =
        RepetitionConfig(minUnit = minUnit, maxRepeats = maxRepeats)

    companion object {
        /** 默认低敏：日常回答最不容易被误杀，现场误判时用户再往上加严。 */
        val DEFAULT = Loose

        /** 容错解析：未知/空/大小写异常一律回落默认值，避免随意改盘导致崩溃。 */
        fun fromWire(value: String?): RepetitionSensitivity {
            return entries.firstOrNull { it.wireValue.equals(value?.trim(), ignoreCase = true) }
                ?: DEFAULT
        }
    }
}
