package com.tricreta.zafiro.autonomy.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TeamTest {
    @Test fun delegationStopsAtDepthThree() {
        assertTrue(BoardRules.canDelegate(0)); assertTrue(BoardRules.canDelegate(2))
        assertFalse(BoardRules.canDelegate(3)); assertFalse(BoardRules.canDelegate(-1))
    }

    @Test fun boardMoves() {
        assertTrue(BoardRules.canMove(BoardRules.Status.TODO, BoardRules.Status.DOING))
        assertFalse(BoardRules.canMove(BoardRules.Status.DONE, BoardRules.Status.TODO))
        assertTrue(BoardRules.canMove(BoardRules.Status.FAILED, BoardRules.Status.TODO))
        assertFalse(BoardRules.canMove(BoardRules.Status.DOING, BoardRules.Status.TODO))
        assertEquals(BoardRules.Status.DONE, BoardRules.parse(" done "))
        assertFalse(BoardRules.allFinished(emptyList()))
        assertTrue(BoardRules.allFinished(listOf(BoardRules.Status.DONE, BoardRules.Status.FAILED)))
        assertFalse(BoardRules.allFinished(listOf(BoardRules.Status.DONE, BoardRules.Status.DOING)))
    }

    @Test fun quotasCapAndParse() {
        val q = Quotas.parse("comment=2, like: 3; junk")
        assertEquals(Quotas.Verdict.Allowed(1), q.check("comment", 0))
        assertTrue(q.check("comment", 2) is Quotas.Verdict.Denied)
        assertEquals(Quotas.Verdict.Allowed(Int.MAX_VALUE), q.check("unlisted", 99))
        assertEquals(15, Quotas.DEFAULT.limits["comment"])
    }

    @Test fun pacingStaysInRange() {
        assertEquals(1000, Pacing.delayMs(1000, 5000, 0.0)); assertEquals(5000, Pacing.delayMs(1000, 5000, 1.0))
        assertEquals(3000, Pacing.delayMs(5000, 1000, 0.5))
    }

    @Test fun tieringPromotesAfterTenAndDemotesOnOneRejection() {
        var s = Tiering.State()
        repeat(9) { s = Tiering.approved(s) }
        assertFalse(s.auto)
        s = Tiering.approved(s)
        assertTrue(s.auto)
        assertEquals(Tiering.Gate.AUTO, Tiering.gate("comment", false, s))
        s = Tiering.rejected(s)
        assertEquals(Tiering.State(0, false), s)
        assertEquals(Tiering.Gate.NEEDS_APPROVAL, Tiering.gate("comment", false, s))
        assertEquals(Tiering.Gate.AUTO, Tiering.gate("reply", true, s))
        assertEquals(Tiering.Gate.NEEDS_APPROVAL, Tiering.gate("reply", false, s))
    }
}
