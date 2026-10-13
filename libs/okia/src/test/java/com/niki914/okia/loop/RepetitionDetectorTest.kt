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

    @Test
    fun typographicRunsDoNotTrigger() {
        // 单字符长串 + 空白构成的重复是排版（Markdown 分隔线 / 表格边框 /
        // 省略号 / 前导点），不是模型复读。误判会让正常回答直接熔断。
        val samples = listOf(
            "-".repeat(60),
            "=".repeat(60),
            ".".repeat(60),
            "_".repeat(60),
            "*".repeat(60),
            " ".repeat(60),
            "* ".repeat(30),
            ". . . ".repeat(15),
            "|" + "-".repeat(20) + "|",
        )
        for (sample in samples) {
            assertFalse("should not trigger: <$sample>", RepetitionDetector().feed(sample))
        }
    }
}
