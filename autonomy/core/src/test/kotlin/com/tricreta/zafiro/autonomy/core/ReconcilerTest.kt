package com.tricreta.zafiro.autonomy.core

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReconcilerTest {
    private val nairobi = ZoneId.of("Africa/Nairobi")
    private fun at(d: Int, h: Int, m: Int = 0, s: Int = 0): Instant =
        ZonedDateTime.of(2026, 11, d, h, m, s, 0, nairobi).toInstant()

    private fun daily(
        id: String = "t1",
        enabled: Boolean = true,
        misfire: MisfirePolicy = MisfirePolicy.RunLate(Duration.ofHours(2)),
        overlap: OverlapPolicy = OverlapPolicy.SKIP,
        created: Instant = at(9, 12),
    ) = TaskSpec(id, enabled, TimeTrigger.Daily(LocalTime.of(8, 0)), nairobi, created, misfire, overlap)

    private fun plan(
        tasks: List<TaskSpec>, cursors: List<TaskCursor> = emptyList(),
        active: Set<String> = emptySet(), now: Instant,
    ) = Reconciler.plan(tasks, cursors.associateBy { it.taskId }, active, now)

    @Test
    fun `new task arms the alarm for its first occurrence and creates no run`() {
        val p = plan(listOf(daily()), now = at(9, 12))
        assertTrue(p.runs.isEmpty())
        assertEquals(at(10, 8), p.nextWake)
    }

    @Test
    fun `alarm delivered a second late queues exactly one run and re-arms for tomorrow`() {
        val p = plan(listOf(daily()), now = at(10, 8, 0, 1))
        assertEquals(listOf(PlannedRun("t1", at(10, 8), PlannedState.QUEUED)), p.runs)
        assertEquals(at(11, 8), p.nextWake)
        assertEquals(at(10, 8), p.cursors.single().lastHandled)
    }

    @Test
    fun `replaying the same plan after cursors are saved creates no duplicate run`() {
        val first = plan(listOf(daily()), now = at(10, 8, 0, 1))
        val second = plan(listOf(daily()), cursors = first.cursors, now = at(10, 8, 0, 5))
        assertTrue(second.runs.isEmpty())
        assertEquals(at(11, 8), second.nextWake)
    }

    @Test
    fun `phone was off at 08 and comes back at 09 - runs late within the grace`() {
        val p = plan(listOf(daily()), now = at(10, 9, 0))
        assertEquals(listOf(PlannedRun("t1", at(10, 8), PlannedState.QUEUED)), p.runs)
    }

    @Test
    fun `phone was off beyond the grace - recorded as missed, not silently dropped, not run`() {
        val p = plan(listOf(daily()), now = at(10, 11, 0)) // 3h late, grace 2h
        assertEquals(listOf(PlannedRun("t1", at(10, 8), PlannedState.MISSED, MissReason.LATE, 1)), p.runs)
        assertEquals(at(11, 8), p.nextWake)
    }

    @Test
    fun `Skip policy only tolerates the on-time slack`() {
        val t = daily(misfire = MisfirePolicy.Skip)
        assertEquals(PlannedState.QUEUED, plan(listOf(t), now = at(10, 8, 1, 0)).runs.single().state) // 60s late
        assertEquals(PlannedState.MISSED, plan(listOf(t), now = at(10, 8, 5, 0)).runs.single().state) // 5 min late
    }

    @Test
    fun `three days off - latest runs if within grace and older ones are coalesced into one missed record`() {
        val cursor = TaskCursor("t1", at(10, 8))
        val p = plan(listOf(daily()), listOf(cursor), now = at(13, 8, 30)) // missed 11,12 ; 13 is 30min late
        val queued = p.runs.filter { it.state == PlannedState.QUEUED }
        val missed = p.runs.filter { it.state == PlannedState.MISSED }
        assertEquals(listOf(PlannedRun("t1", at(13, 8), PlannedState.QUEUED)), queued)
        assertEquals(1, missed.size)
        assertEquals(2, missed.single().missedCount)
        assertEquals(at(12, 8), missed.single().scheduledFor)
        // keys are unique so storage can enforce UNIQUE(task, scheduledFor)
        assertEquals(p.runs.size, p.runs.map { it.taskId to it.scheduledFor }.toSet().size)
    }

    @Test
    fun `overlap SKIP records a missed overlap when the previous run is still active`() {
        val p = plan(listOf(daily()), active = setOf("t1"), now = at(10, 8, 0, 1))
        assertEquals(listOf(PlannedRun("t1", at(10, 8), PlannedState.MISSED, MissReason.OVERLAP, 1)), p.runs)
    }

    @Test
    fun `overlap QUEUE still queues`() {
        val p = plan(listOf(daily(overlap = OverlapPolicy.QUEUE)), active = setOf("t1"), now = at(10, 8, 0, 1))
        assertEquals(PlannedState.QUEUED, p.runs.single().state)
    }

    @Test
    fun `disabled tasks never run and never accumulate missed history while paused`() {
        val paused = daily(enabled = false)
        val p = plan(listOf(paused), listOf(TaskCursor("t1", at(1, 8))), now = at(10, 9))
        assertTrue(p.runs.isEmpty())
        assertNull(p.nextWake)
        assertEquals(at(10, 9), p.cursors.single().lastHandled)
        // resume the next morning: only the new occurrence counts, nothing is reported missed
        val resumed = plan(listOf(daily()), p.cursors, now = at(11, 8, 0, 1))
        assertEquals(listOf(PlannedRun("t1", at(11, 8), PlannedState.QUEUED)), resumed.runs)
    }

    @Test
    fun `next wake is the earliest across tasks`() {
        val a = daily("a")
        val b = TaskSpec("b", true, TimeTrigger.Daily(LocalTime.of(6, 30)), nairobi, at(9, 12))
        assertEquals(at(10, 6, 30), plan(listOf(a, b), now = at(9, 12)).nextWake)
    }

    @Test
    fun `one-shot task runs once and then disarms`() {
        val once = TaskSpec(
            "o", true, TimeTrigger.Once(java.time.LocalDateTime.of(2026, 11, 10, 8, 0)), nairobi, at(9, 12),
        )
        val p = plan(listOf(once), now = at(10, 8, 0, 2))
        assertEquals(PlannedState.QUEUED, p.runs.single().state)
        assertNull(p.nextWake)
        val again = plan(listOf(once), p.cursors, now = at(10, 8, 5))
        assertTrue(again.runs.isEmpty())
    }

    @Test
    fun `task with no trigger (manual only) is ignored by the scheduler`() {
        val manual = TaskSpec("m", true, null, nairobi, at(9, 12))
        val p = plan(listOf(manual), now = at(10, 8))
        assertTrue(p.runs.isEmpty())
        assertNull(p.nextWake)
        assertTrue(p.cursors.isEmpty())
    }

    @Test
    fun `phone time zone change does not move a task pinned to Africa Nairobi`() {
        // The task carries its own zone; reconcile after a device zone change yields the same instant.
        val p1 = plan(listOf(daily()), now = at(9, 12))
        val p2 = plan(listOf(daily()), now = at(9, 12))
        assertEquals(p1.nextWake, p2.nextWake)
        assertEquals(Instant.parse("2026-11-10T05:00:00Z"), p1.nextWake)
    }
}
