// 保护：多轮分支树在不同轮次分叉时正确隔离 candidate 集、计算独立的 (current/total) 并正确推导切换目标。
package com.niki914.zafiro.app.conversation

import com.niki914.zafiro.api.model.ConversationTurn
import com.niki914.zafiro.api.model.TurnId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationBranchTreeTest {

    @Test
    fun singleConversation_hasNoTurnBranches() {
        val turns = listOf(turn(0))
        val family = listOf(summary("c0"))

        val result = ConversationBranchTree.computeTurnBranches("c0", turns, family)

        assertTrue(result.isEmpty())
    }

    @Test
    fun turn0Regenerations_calculatesBranchPagersForTurn0Only() {
        val c0 = summary("c0", createdAt = 1000, updatedAt = 1000)
        val c1 = summary("c1", parentId = "c0", forkTurn = 0, createdAt = 2000, updatedAt = 2000)
        val c2 = summary("c2", parentId = "c1", forkTurn = 0, createdAt = 3000, updatedAt = 3000)
        val family = listOf(c0, c1, c2)
        val turns = listOf(turn(0))

        val state0 = ConversationBranchTree.computeTurnBranches("c0", turns, family)[0L]!!
        val state1 = ConversationBranchTree.computeTurnBranches("c1", turns, family)[0L]!!
        val state2 = ConversationBranchTree.computeTurnBranches("c2", turns, family)[0L]!!

        assertEquals(1 to 3, state0.current to state0.total)
        assertEquals(2 to 3, state1.current to state1.total)
        assertEquals(3 to 3, state2.current to state2.total)
        assertEquals(listOf("c0", "c1", "c2"), state1.variantConversationIds)
    }

    @Test
    fun turn1RegenerateUnderTurn0Variant_isolatesTurn1ToThatVariantSubtree() {
        val c0 = summary("c0", createdAt = 1000, updatedAt = 1000)
        val c1 = summary("c1", parentId = "c0", forkTurn = 0, createdAt = 2000, updatedAt = 2000)
        val c2 = summary("c2", parentId = "c1", forkTurn = 0, createdAt = 3000, updatedAt = 3000)
        val c3 = summary("c3", parentId = "c1", forkTurn = 1, createdAt = 4000, updatedAt = 4500)
        val family = listOf(c0, c1, c2, c3)
        val turns = listOf(turn(0), turn(1))

        val resultOnC3 = ConversationBranchTree.computeTurnBranches("c3", turns, family)
        val turn0State = resultOnC3[0L]!!
        val turn1State = resultOnC3[1L]!!

        assertEquals(2 to 3, turn0State.current to turn0State.total)
        assertEquals(listOf("c0", "c3", "c2"), turn0State.variantConversationIds)
        assertEquals(2 to 2, turn1State.current to turn1State.total)
        assertEquals(listOf("c1", "c3"), turn1State.variantConversationIds)
    }

    @Test
    fun differentTurn0Variant_hasNoTurn1BranchIndicator() {
        val c0 = summary("c0", createdAt = 1000, updatedAt = 5000)
        val c1 = summary("c1", parentId = "c0", forkTurn = 0, createdAt = 2000, updatedAt = 2000)
        val c2 = summary("c2", parentId = "c1", forkTurn = 0, createdAt = 3000, updatedAt = 3000)
        val c3 = summary("c3", parentId = "c1", forkTurn = 1, createdAt = 4000, updatedAt = 4500)
        val family = listOf(c0, c1, c2, c3)
        val turnsOnC0 = listOf(turn(0), turn(1))

        val resultOnC0 = ConversationBranchTree.computeTurnBranches("c0", turnsOnC0, family)

        assertEquals(1 to 3, resultOnC0[0L]!!.current to resultOnC0[0L]!!.total)
        assertNull(resultOnC0[1L])
    }

    private fun summary(
        id: String,
        parentId: String? = null,
        forkTurn: Int? = null,
        createdAt: Long = 1000L,
        updatedAt: Long = 1000L,
    ) = ConversationSummary(
        id = id,
        title = "title",
        titleEdited = false,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastMessagePreview = "preview",
        turnCount = 1,
        rootId = "c0",
        parentId = parentId,
        forkTurn = forkTurn,
    )

    private fun turn(index: Int) = ConversationTurn(
        id = TurnId("t$index"),
        userText = "user $index",
        images = emptyList(),
        blocks = emptyList(),
    )
}
