package com.niki914.zafiro.app.ui.model

import org.junit.Assert.assertEquals
import org.junit.Test

class FallbackSlotsTest {
    @Test
    fun emptyChainGivesThreeBlankSlots() {
        assertEquals(listOf("", "", ""), FallbackSlots.toSlots(emptyList()))
    }

    @Test
    fun chainFillsSlotsInOrder() {
        assertEquals(listOf("a", "b", ""), FallbackSlots.toSlots(listOf("a", "b")))
    }

    @Test
    fun settingASlotMovesTheModelInsteadOfDuplicatingIt() {
        val slots = FallbackSlots.set(listOf("a", "b", ""), index = 2, id = "a")
        assertEquals(listOf("", "b", "a"), slots)
        assertEquals(listOf("b", "a"), FallbackSlots.toChain(slots))
    }

    @Test
    fun clearingASlotWithBlankLeavesOthersAlone() {
        assertEquals(listOf("a", "", "c"), FallbackSlots.set(listOf("a", "b", "c"), 1, ""))
    }

    @Test
    fun pruneDropsDeletedModelsAndClosesGaps() {
        assertEquals(listOf("c", "", ""), FallbackSlots.prune(listOf("gone", "", "c"), setOf("a", "c")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun outOfRangeSlotIsRejected() {
        FallbackSlots.set(listOf("", "", ""), 3, "a")
    }
}
