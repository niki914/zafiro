package com.niki914.zafiro.chat

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmStreamCollectorsTest {

    @Test
    fun collectAsFull_updatesToolStatusInPlaceAndKeepsMultipleToolsSeparate() = runTest {
        val frames = mutableListOf<LlmTextFrame>()

        flowOf(
            LlmStreamEvent.RoundStarted,
            LlmStreamEvent.TextDelta(delta = "I'll call search.", fullText = "I'll call search."),
            LlmStreamEvent.ToolRunning(ToolCallStatus(callId = "search-1", name = "search")),
            LlmStreamEvent.ToolRunning(ToolCallStatus(callId = "calc-1", name = "calc")),
            LlmStreamEvent.ToolSucceeded(ToolCallStatus(callId = "search-1", name = "search")),
            LlmStreamEvent.ToolFailed(ToolCallStatus(callId = "calc-1", name = "calc"), "blocked"),
            LlmStreamEvent.TextDelta(
                delta = "I've done the check.",
                fullText = "I'll call search.I've done the check.",
            ),
            LlmStreamEvent.Completed,
        ).collectAsFull(testLabels) { frame ->
            frames += frame
        }

        assertEquals(
            """
            I'll call search.
            `[search] success`
            `[calc] failed`
            I've done the check.
            """.trimIndent(),
            frames.last().text,
        )
        assertEquals(true, frames.first().isFirst)
        assertEquals(true, frames.last().isFinal)
    }

    @Test
    fun collectAsChunk_appendsToolStatusLinesAndPreservesAiTextOrder() = runTest {
        val frames = mutableListOf<LlmTextFrame>()

        flowOf(
            LlmStreamEvent.RoundStarted,
            LlmStreamEvent.TextDelta(delta = "I'll call search.", fullText = "I'll call search."),
            LlmStreamEvent.ToolRunning(ToolCallStatus(callId = "search-1", name = "search")),
            LlmStreamEvent.ToolSucceeded(ToolCallStatus(callId = "search-1", name = "search")),
            LlmStreamEvent.TextDelta(
                delta = "I've done the check.",
                fullText = "I'll call search.I've done the check.",
            ),
            LlmStreamEvent.Completed,
        ).collectAsChunk(testLabels) { frame ->
            frames += frame
        }

        assertEquals(
            """
            I'll call search.
            `[search] called`
            `[search] running`
            `[search] success`
            I've done the check.
            """.trimIndent(),
            frames.last().text,
        )
        assertEquals(true, frames.first().isFirst)
        assertEquals(true, frames.last().isFinal)
    }

    @Test
    fun collectAsFull_handlesInterleavedToolStartsAndCompletions() = runTest {
        val frames = mutableListOf<LlmTextFrame>()

        flowOf(
            LlmStreamEvent.RoundStarted,
            LlmStreamEvent.TextDelta(delta = "I'll call tools.", fullText = "I'll call tools."),
            LlmStreamEvent.ToolRunning(ToolCallStatus(callId = "a-1", name = "alpha")),
            LlmStreamEvent.ToolRunning(ToolCallStatus(callId = "b-1", name = "beta")),
            LlmStreamEvent.ToolSucceeded(ToolCallStatus(callId = "b-1", name = "beta")),
            LlmStreamEvent.ToolRunning(ToolCallStatus(callId = "c-1", name = "gamma")),
            LlmStreamEvent.ToolFailed(ToolCallStatus(callId = "a-1", name = "alpha"), "timeout"),
            LlmStreamEvent.ToolSucceeded(ToolCallStatus(callId = "c-1", name = "gamma")),
            LlmStreamEvent.TextDelta(
                delta = "I've handled the crossed results.",
                fullText = "I'll call tools.I've handled the crossed results.",
            ),
            LlmStreamEvent.Completed,
        ).collectAsFull(testLabels) { frame ->
            frames += frame
        }

        assertEquals(
            """
            I'll call tools.
            `[alpha] failed`
            `[beta] success`
            `[gamma] success`
            I've handled the crossed results.
            """.trimIndent(),
            frames.last().text,
        )
        assertEquals(
            """
            I'll call tools.
            `[alpha] running`
            `[beta] success`
            """.trimIndent(),
            frames[6].text,
        )
        assertEquals(true, frames.first().isFirst)
        assertEquals(true, frames.last().isFinal)
    }

    @Test
    fun collectAsChunk_handlesInterleavedToolStartsAndCompletions() = runTest {
        val frames = mutableListOf<LlmTextFrame>()

        flowOf(
            LlmStreamEvent.RoundStarted,
            LlmStreamEvent.TextDelta(delta = "I'll call tools.", fullText = "I'll call tools."),
            LlmStreamEvent.ToolRunning(ToolCallStatus(callId = "a-1", name = "alpha")),
            LlmStreamEvent.ToolRunning(ToolCallStatus(callId = "b-1", name = "beta")),
            LlmStreamEvent.ToolSucceeded(ToolCallStatus(callId = "b-1", name = "beta")),
            LlmStreamEvent.ToolRunning(ToolCallStatus(callId = "c-1", name = "gamma")),
            LlmStreamEvent.ToolFailed(ToolCallStatus(callId = "a-1", name = "alpha"), "timeout"),
            LlmStreamEvent.ToolSucceeded(ToolCallStatus(callId = "c-1", name = "gamma")),
            LlmStreamEvent.TextDelta(
                delta = "I've handled the crossed results.",
                fullText = "I'll call tools.I've handled the crossed results.",
            ),
            LlmStreamEvent.Completed,
        ).collectAsChunk(testLabels) { frame ->
            frames += frame
        }

        assertEquals(
            """
            I'll call tools.
            `[alpha] called`
            `[alpha] running`
            `[beta] called`
            `[beta] running`
            `[beta] success`
            `[gamma] called`
            `[gamma] running`
            `[alpha] failed`
            `[gamma] success`
            I've handled the crossed results.
            """.trimIndent(),
            frames.last().text,
        )
        assertEquals(
            """
            I'll call tools.
            `[alpha] called`
            `[alpha] running`
            `[beta] called`
            `[beta] running`
            `[beta] success`
            """.trimIndent(),
            frames[6].text.trimEnd(),
        )
        assertEquals(true, frames.first().isFirst)
        assertEquals(true, frames.last().isFinal)
    }

    @Test
    fun collectAsFull_emitsErrorAsFinalVisibleFrame() = runTest {
        val frames = mutableListOf<LlmTextFrame>()

        flowOf(
            LlmStreamEvent.Error("请先填写配置"),
        ).collectAsFull(testLabels) { frame ->
            frames += frame
        }

        assertEquals(1, frames.size)
        assertEquals(
            """
            ## Error
            ```
            请先填写配置
            ```
            """.trimIndent(),
            frames.single().text,
        )
        assertEquals(true, frames.single().isFirst)
        assertEquals(true, frames.single().isFinal)
    }

    @Test
    fun collectAsChunk_appendsErrorAsFinalVisibleFrame() = runTest {
        val frames = mutableListOf<LlmTextFrame>()

        flowOf(
            LlmStreamEvent.RoundStarted,
            LlmStreamEvent.TextDelta(delta = "处理中", fullText = "处理中"),
            LlmStreamEvent.Error("请先填写配置"),
        ).collectAsChunk(testLabels) { frame ->
            frames += frame
        }

        assertEquals(
            """
            处理中
            请先填写配置
            """.trimIndent(),
            frames.last().text,
        )
        assertEquals(false, frames.last().isFirst)
        assertEquals(true, frames.last().isFinal)
    }

    // ── FullTextProjector（Service 复用的 internal seam）与旧 collectAsFull 对照 ────────

    @Test
    fun fullTextProjector_matchesCollectAsFull_andMatchesGoldenFrames() = runTest {
        val events = listOf(
            LlmStreamEvent.RoundStarted,
            LlmStreamEvent.TextDelta(delta = "I'll call search.", fullText = "I'll call search."),
            LlmStreamEvent.ThinkingStarted(0, "plan"),
            LlmStreamEvent.ThinkingEnded(0, "plan"),
            LlmStreamEvent.ToolRunning(ToolCallStatus(callId = "search-1", name = "search")),
            LlmStreamEvent.ToolSucceeded(ToolCallStatus(callId = "search-1", name = "search")),
            LlmStreamEvent.TextDelta(delta = "Done.", fullText = "I'll call search.Done."),
            LlmStreamEvent.Completed,
        )

        val projectorFrames = mutableListOf<LlmTextFrame>()
        val projector = FullTextProjector(testLabels)
        events.forEach { event ->
            projector.apply(event).forEach { projectorFrames += it }
        }

        val collectorFrames = mutableListOf<LlmTextFrame>()
        flowOf(*events.toTypedArray()).collectAsFull(testLabels) { collectorFrames += it }

        // Service 复用的 internal seam 与旧 collectAsFull 逐帧一致（含顺序与终结帧）
        assertEquals(collectorFrames, projectorFrames)
        assertEquals(
            """
            I'll call search.
            `[Thought]`
            `[search] success`
            Done.
            """.trimIndent(),
            projectorFrames.last().text,
        )
        assertTrue(projectorFrames.first().isFirst)
        assertTrue(projectorFrames.last().isFinal)
    }

    @Test
    fun fullTextProjector_emptyThinkingEmitsNoThinkingLine() {
        val projector = FullTextProjector(testLabels)
        val frames = mutableListOf<LlmTextFrame>()
        listOf(
            LlmStreamEvent.RoundStarted,
            LlmStreamEvent.ThinkingStarted(0, ""),
            LlmStreamEvent.ThinkingEnded(0, ""),
            LlmStreamEvent.TextDelta(delta = "answer", fullText = "answer"),
            LlmStreamEvent.Completed,
        ).forEach { event -> projector.apply(event).forEach { frames += it } }

        assertEquals("answer", frames.last().text)
        assertTrue(frames.none { "[Thinking]" in it.text || "[Thought]" in it.text })
    }

    @Test
    fun fullTextProjector_toolArgsAndResultStayOutOfVisibleFrame() {
        val projector = FullTextProjector(testLabels)
        val frames = mutableListOf<LlmTextFrame>()
        listOf(
            LlmStreamEvent.RoundStarted,
            LlmStreamEvent.ToolRunning(
                ToolCallStatus(
                    callId = "c1",
                    name = "terminal",
                    label = "terminal",
                    argumentsJson = """{"command":"rm -rf /"}""",
                ),
            ),
            LlmStreamEvent.ToolSucceeded(
                ToolCallStatus(callId = "c1", name = "terminal", label = "terminal"),
                outputText = "secret-output",
            ),
            LlmStreamEvent.Completed,
        ).forEach { event -> projector.apply(event).forEach { frames += it } }

        assertEquals("`[terminal] success`", frames.last().text)
        assertTrue(frames.none { "rm -rf /" in it.text || "secret-output" in it.text })
    }

    @Test
    fun fullTextProjector_retryLineAppearsAndClearsOnRecovery() {
        val projector = FullTextProjector(testLabels)
        val frames = mutableListOf<LlmTextFrame>()
        listOf(
            LlmStreamEvent.RoundStarted,
            LlmStreamEvent.TextDelta(delta = "partial", fullText = "partial"),
            LlmStreamEvent.Retrying(
                attempt = 1,
                maxAttempts = 3,
                delayMs = 100L,
                reason = "rate limit",
            ),
            LlmStreamEvent.TextDelta(delta = "recovered", fullText = "partialrecovered"),
            LlmStreamEvent.Completed,
        ).forEach { event -> projector.apply(event).forEach { frames += it } }

        // 重试状态行在流恢复后整行退场；正文不重复、不丢失
        val retryFrame = frames.first { "[Retrying 1/3]" in it.text }
        assertTrue(retryFrame.text.startsWith("partial"))
        assertEquals("partialrecovered", frames.last().text)
        assertTrue(frames.last().isFinal)
    }

    @Test
    fun fullTextProjector_streamWithoutCompletedHasNoFinalFrame() {
        val projector = FullTextProjector(testLabels)
        val frames = mutableListOf<LlmTextFrame>()
        listOf(
            LlmStreamEvent.RoundStarted,
            LlmStreamEvent.TextDelta(delta = "partial", fullText = "partial"),
            LlmStreamEvent.ThinkingStarted(0, "half thought"),
            LlmStreamEvent.ThinkingEnded(0, "half thought"),
        ).forEach { event -> projector.apply(event).forEach { frames += it } }

        // 中止（无 Completed）：不产生 isFinal 帧，已收内容保留
        assertTrue(frames.none { it.isFinal })
        assertEquals("partial\n`[Thought]`", frames.last().text)
    }

    @Test
    fun fullTextProjector_errorIsFinalFrameWithGoldenText() {
        val projector = FullTextProjector(testLabels)
        val frames = projector.apply(LlmStreamEvent.Error("boom"))

        assertEquals(1, frames.size)
        assertTrue(frames.single().isFirst)
        assertTrue(frames.single().isFinal)
        assertEquals("## Error\n```\nboom\n```", frames.single().text)
    }

    @Test
    fun fullTextProjector_errorAfterTextIsFinalButNotFirst() {
        val projector = FullTextProjector(testLabels)
        val frames = mutableListOf<LlmTextFrame>()
        projector.apply(LlmStreamEvent.TextDelta(delta = "hello", fullText = "hello"))
            .forEach { frames += it }
        projector.apply(LlmStreamEvent.Error("boom")).forEach { frames += it }

        assertFalse(frames.last().isFirst)
        assertTrue(frames.last().isFinal)
        assertEquals("hello\n## Error\n```\nboom\n```", frames.last().text)
    }

    private val testLabels = ToolStatusLabels(
        called = "called",
        running = "running",
        success = "success",
        failed = "failed",
    )
}
