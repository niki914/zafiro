package com.niki914.okia.loop

/**
 * 流式复读检测：同一短串在尾部窗口内连续重复超过阈值即判复读。
 *
 * 模型退化（repetition loop）时流会持续吐字，idle 超时永不触发；本检测器
 * 提供内容层的熔断判据。正文（text）与思考（thinking）共用同一实例语义——
 * 各自持有独立实例，阈值相同（调用方按块 feed）。
 *
 * 判据：从尾部取长度 [minUnit] 的短串，检查它是否连续重复 >= [maxRepeats] 次。
 * 只看尾部窗口（[windowSize]），O(窗口长度)，不随输出增长而变慢。
 *
 * 阈值取向：宁可迟钝不可误杀。默认 4 字符单元重复 10 次（共 40 字符）才触发，
 * 正常文本（列表项、代码片段、强调、普通排版分隔线）几乎不会命中。
 */
internal class RepetitionDetector(
    private val minUnit: Int = DEFAULT_MIN_UNIT,
    private val maxRepeats: Int = DEFAULT_MAX_REPEATS,
    private val windowSize: Int = adaptiveWindowSize(maxRepeats),
) {

    constructor(config: RepetitionConfig) : this(
        minUnit = config.minUnit,
        maxRepeats = config.maxRepeats,
    )

    private val tail = StringBuilder()

    /** 块开始（TextStarted / ThinkingStarted）时重置：跨块不累计。 */
    fun reset() {
        tail.clear()
    }

    /** 追加增量；返回 true = 判定为复读。 */
    fun feed(chunk: String): Boolean {
        if (chunk.isEmpty()) return false
        tail.append(chunk)
        // 只保留尾部窗口：更早的历史对「尾部自重复」判据无贡献
        if (tail.length > windowSize) {
            tail.delete(0, tail.length - windowSize)
        }
        return detects()
    }

    private fun detects(): Boolean {
        // 先从最短单元试起：单元越短越容易重复，命中即算复读
        for (unit in minUnit..(tail.length / maxRepeats)) {
            if (repeatsOf(unit) >= maxRepeats) return true
        }
        return false
    }

    /** 尾部单元长度 unit 的连续重复次数（至少 1）。 */
    private fun repeatsOf(unit: Int): Int {
        val end = tail.length
        val start = end - unit
        var count = 1
        var cursor = start
        while (cursor - unit >= 0) {
            if (!regionEquals(cursor - unit, cursor, unit)) return count
            count++
            cursor -= unit
        }
        return count
    }

    /** 比较尾巴上两段等长区间是否相同。 */
    private fun regionEquals(aStart: Int, bStart: Int, length: Int): Boolean {
        for (offset in 0 until length) {
            if (tail[aStart + offset] != tail[bStart + offset]) return false
        }
        return true
    }

    internal companion object {
        const val DEFAULT_MIN_UNIT = 4
        const val DEFAULT_MAX_REPEATS = 10
        const val DEFAULT_WINDOW_SIZE = 200
        private const val MAX_UNIT_ESTIMATE = 30

        internal fun adaptiveWindowSize(maxRepeats: Int): Int {
            if (maxRepeats >= 1000) return DEFAULT_WINDOW_SIZE
            return maxOf(DEFAULT_WINDOW_SIZE, maxRepeats * MAX_UNIT_ESTIMATE)
        }
    }
}

/**
 * 复读判据的可调阈值。宿主按用户灵敏度档位构造；null（不传）时 loop 用
 * [RepetitionDetector] 的默认值。单元越小、次数越少越灵敏。
 */
data class RepetitionConfig(
    val minUnit: Int,
    val maxRepeats: Int,
)
