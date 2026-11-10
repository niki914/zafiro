package com.niki914.zafiro.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackPolicyTest {
    @Test
    fun providerFaultsFallBackWhenNothingWasProduced() {
        for (code in listOf(
            LlmErrorCode.Auth, LlmErrorCode.Quota, LlmErrorCode.RateLimit, LlmErrorCode.Overloaded,
            LlmErrorCode.Transport, LlmErrorCode.RetryExhausted, LlmErrorCode.IdleTimeout,
        )) {
            assertTrue("$code", FallbackPolicy.shouldFallBack(code, sawOutput = false, isLastCandidate = false))
        }
    }

    @Test
    fun otherErrorsNeverFallBack() {
        for (code in listOf(
            null, LlmErrorCode.ConfigRequired, LlmErrorCode.TurnConflict, LlmErrorCode.Parse, LlmErrorCode.HookFailed,
            LlmErrorCode.ToolExecutionFailed, LlmErrorCode.OutputTruncated, LlmErrorCode.Repetition,
        )) {
            assertFalse("$code", FallbackPolicy.shouldFallBack(code, sawOutput = false, isLastCandidate = false))
        }
    }

    @Test
    fun noFallbackAfterOutputOrOnTheLastCandidate() {
        assertFalse(FallbackPolicy.shouldFallBack(LlmErrorCode.Quota, sawOutput = true, isLastCandidate = false))
        assertFalse(FallbackPolicy.shouldFallBack(LlmErrorCode.Quota, sawOutput = false, isLastCandidate = true))
    }

    @Test
    fun onlyRealOutputCountsAsOutput() {
        assertFalse(FallbackPolicy.isOutput(LlmStreamEvent.RoundStarted))
        assertFalse(FallbackPolicy.isOutput(LlmStreamEvent.Retrying(1, 3, 1000, "429")))
        assertTrue(FallbackPolicy.isOutput(LlmStreamEvent.TextDelta("a", "a")))
        assertTrue(FallbackPolicy.isOutput(LlmStreamEvent.ToolRunning(ToolCallStatus(name = "x"))))
        assertTrue(FallbackPolicy.isOutput(LlmStreamEvent.ThinkingStarted(0, "")))
    }

    @Test
    fun chainDropsBlankMissingDuplicateAndInUseIds() {
        val existing = setOf("a", "b", "c", "d")
        assertEquals(
            listOf("b", "c"),
            FallbackPolicy.resolveChain(primaryId = "a", activeId = "d", configured = listOf("a", "", "b", "gone", "b", "c"), existingIds = existing),
        )
    }

    @Test
    fun blankPrimaryMeansTheActiveModelIsInUse() {
        val existing = setOf("a", "b")
        assertEquals(listOf("b"), FallbackPolicy.resolveChain(primaryId = "", activeId = "a", configured = listOf("a", "b"), existingIds = existing))
        assertEquals(listOf("a", "b"), FallbackPolicy.resolveChain(primaryId = null, activeId = null, configured = listOf("a", "b"), existingIds = existing))
    }

    @Test
    fun orderIsPreserved() {
        assertEquals(listOf("c", "a"), FallbackPolicy.resolveChain("b", "b", listOf("c", "a"), setOf("a", "b", "c")))
    }
}
