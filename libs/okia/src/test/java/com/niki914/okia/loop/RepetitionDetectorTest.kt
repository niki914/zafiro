// 保护：复读检测的判据边界（阈值、窗口滑动、跨块重置）。阈值算错会让正常文本被熔断
// 或复读漏检，两者都是用户可见的行为错误。
package com.niki914.okia.loop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepetitionDetectorTest {

    @Test
    fun normalTextDoesNotTrigger() {
        val detector = RepetitionDetector()

        assertFalse(detector.feed("The quick brown fox jumps over the lazy dog."))
        assertFalse(detector.feed(" Another sentence follows here."))
    }

    @Test
    fun repeatedUnitTriggersAtThreshold() {
        val detector = RepetitionDetector(minUnit = 4, maxRepeats = 10)

        // 9 次不触发（阈值之下）
        assertFalse(detector.feed("abcd".repeat(9)))
        // 第 10 次触发
        assertTrue(detector.feed("abcd"))
    }

    @Test
    fun shortUnitRepeatingTriggers() {
        val detector = RepetitionDetector(minUnit = 4, maxRepeats = 10)

        // 4 字符单元本身不是最小要求；更短的重复模式也应命中
        assertTrue(detector.feed("Let me ".repeat(10)))
    }

    @Test
    fun resetClearsHistory() {
        val detector = RepetitionDetector(minUnit = 4, maxRepeats = 10)

        assertFalse(detector.feed("abcd".repeat(9)))
        detector.reset()
        // 重置后累计归零：再喂 9 次仍不触发
        assertFalse(detector.feed("abcd".repeat(9)))
    }

    @Test
    fun detectionWorksAcrossChunks() {
        val detector = RepetitionDetector(minUnit = 4, maxRepeats = 10)

        // 增量逐字符到达，不依赖单次 chunk 的完整单元
        var triggered = false
        repeat(10) { if (detector.feed("abcd")) triggered = true }
        assertTrue(triggered)
    }

    @Test
    fun windowKeepsOldTextFromTriggeringAgain() {
        val detector = RepetitionDetector(minUnit = 4, maxRepeats = 10, windowSize = 40)

        assertTrue(detector.feed("abcd".repeat(10)))
        // 窗口已滑过旧内容：普通文本不再触发
        assertFalse(detector.feed("x".repeat(60) + "tail"))
    }

    // 保护：单字符死循环（如连续输出同一汉字/数字/字母）达到阈值必须熔断，不被误判放行。
    @Test
    fun singleCharacterRepeatTriggersAtThreshold() {
        val detector = RepetitionDetector(minUnit = 4, maxRepeats = 10)

        assertFalse(detector.feed("哈".repeat(39)))
        assertTrue(detector.feed("哈"))
    }

    // 保护：常规短排版（正常分隔线、表格线、省略号）在未达阈值前不被误杀。
    @Test
    fun normalMarkdownElementsDoNotTrigger() {
        val detector = RepetitionDetector()
        val samples = listOf(
            "---",
            "***",
            "--------------------",
            "|---|---|",
            "...",
        )
        for (sample in samples) {
            assertFalse("should not trigger: <$sample>", detector.feed(sample))
            detector.reset()
        }
    }

    // 保护：阈值参数是否真生效（档位传来的 minUnit/maxRepeats 直接决定误熔断与漏检）。
    @Test
    fun unreachableThresholdNeverTriggers() {
        val detector = RepetitionDetector(
            RepetitionConfig(minUnit = Int.MAX_VALUE, maxRepeats = Int.MAX_VALUE)
        )

        assertFalse(detector.feed("abcd".repeat(1000)))
    }

    @Test
    fun higherThresholdsTolerateWhatLowerAborts() {
        // 同一段输入：宽松阈值不触发，严格阈值触发——证明两档参数确实分叉
        val payload = "abcdefgh".repeat(12)

        val relaxed = RepetitionDetector(RepetitionConfig(minUnit = 8, maxRepeats = 24))
        val strict = RepetitionDetector(RepetitionConfig(minUnit = 4, maxRepeats = 10))

        assertFalse(relaxed.feed(payload))
        assertTrue(strict.feed(payload))
    }

    // 保护：宽松档位下窗口是否自适应扩展（否则长单元在固定 200 字符窗口下因 tail.length / maxRepeats < unit 而漏检）。
    @Test
    fun adaptiveWindowDetectsLongerUnitsAtRelaxedThreshold() {
        val detector = RepetitionDetector(RepetitionConfig(minUnit = 8, maxRepeats = 24))
        val unit = "123456789012"
        assertTrue(detector.feed(unit.repeat(24)))
    }
}
