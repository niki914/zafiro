package com.tricreta.zafiro.autonomy

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tricreta.zafiro.autonomy.core.RunState
import com.tricreta.zafiro.autonomy.data.AutonomyDatabase
import com.tricreta.zafiro.autonomy.data.AutonomyRepository
import com.tricreta.zafiro.autonomy.data.TaskEntity
import com.tricreta.zafiro.autonomy.data.TriggerTypes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutonomyRepositoryTest {

    private val nairobi = ZoneId.of("Africa/Nairobi")
    private lateinit var db: AutonomyDatabase
    private lateinit var repo: AutonomyRepository
    private var nowMs = 0L

    private fun at(d: Int, h: Int, m: Int = 0, s: Int = 0): Instant =
        ZonedDateTime.of(2026, 11, d, h, m, s, 0, nairobi).toInstant()

    private fun daily(id: String = "t1", enabled: Boolean = true, grace: Long = 2 * 3_600_000L) = TaskEntity(
        id = id, name = "Morning", prompt = "do it", enabled = enabled,
        triggerType = TriggerTypes.DAILY, onceAt = null, intervalMinutes = null, anchorMs = null,
        timeOfDay = "08:00", daysMask = 0, dayOfMonth = 1, zoneId = nairobi.id,
        createdAtMs = at(9, 12).toEpochMilli(), updatedAtMs = 0, misfireGraceMs = grace, overlap = "SKIP",
        maxSteps = 10, maxDurationMs = 60_000, configId = "", approvalTimeoutMs = 60_000,
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AutonomyDatabase::class.java)
            .allowMainThreadQueries().build()
        nowMs = at(9, 12).toEpochMilli()
        repo = AutonomyRepository(db) { nowMs }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `a due daily task produces exactly one queued run, and replaying creates none`() = runBlocking {
        repo.saveTask(daily())
        val first = repo.reconcile(at(10, 8, 0, 1))
        assertEquals(1, first.newRuns)
        assertEquals(1, first.queued)
        assertEquals(at(11, 8), first.nextWake)
        val second = repo.reconcile(at(10, 8, 0, 5))
        assertEquals(0, second.newRuns)
        assertEquals(1, second.queued)
        Unit
    }

    @Test
    fun `claimNext hands a run to exactly one caller`() = runBlocking {
        repo.saveTask(daily())
        repo.reconcile(at(10, 8, 0, 1))
        val a = repo.claimNext()
        val b = repo.claimNext()
        assertNotNull(a)
        assertNull(b)
        assertEquals(RunState.STARTING.name, a.state)
    }

    @Test
    fun `a run later than its grace is recorded as missed and never queued`() = runBlocking {
        repo.saveTask(daily(grace = 3_600_000L))
        val out = repo.reconcile(at(10, 11, 0)) // 3 h late, grace 1 h
        assertEquals(0, out.queued)
        val runs = repo.observeRuns().first()
        assertEquals(1, runs.size)
        assertEquals(RunState.MISSED.name, runs.single().state)
        assertTrue(runs.single().summary!!.contains("Missed"))
    }

    @Test
    fun `a paused task never runs and resuming does not report the pause as missed`() = runBlocking {
        repo.saveTask(daily())
        repo.setEnabled("t1", false)
        nowMs = at(12, 9).toEpochMilli()
        assertEquals(0, repo.reconcile(at(12, 9)).newRuns)
        repo.setEnabled("t1", true) // resume: cursor = now
        nowMs = at(13, 8, 0, 2).toEpochMilli()
        val out = repo.reconcile(at(13, 8, 0, 2))
        assertEquals(1, out.queued)
        assertTrue(repo.observeRuns().first().none { it.state == RunState.MISSED.name })
        Unit
    }

    @Test
    fun `orphan sweep interrupts stale in-flight runs but spares owned and fresh ones`() = runBlocking {
        repo.saveTask(daily())
        repo.reconcile(at(10, 8, 0, 1))
        val run = repo.claimNext()!! // STARTING with heartbeat = now
        // fresh heartbeat -> spared even though this process does not own it (closes the claim/register window)
        assertEquals(0, repo.sweepOrphans(emptySet()))
        nowMs += 60_000 // a minute passes with no heartbeat: the process died
        assertEquals(0, repo.sweepOrphans(setOf(run.id))) // still owned by a live executor -> spared
        assertEquals(1, repo.sweepOrphans(emptySet()))
        assertEquals(RunState.INTERRUPTED.name, repo.run(run.id)!!.state)
    }

    @Test
    fun `manual run is queued immediately`() = runBlocking {
        repo.saveTask(daily())
        val id = repo.enqueueManual("t1")
        assertNotNull(id)
        assertEquals(RunState.QUEUED.name, repo.run(id)!!.state)
        assertTrue(repo.run(id)!!.manual)
    }

    @Test
    fun `finish rejects non terminal states`() = runBlocking {
        repo.saveTask(daily())
        val id = repo.enqueueManual("t1")!!
        val failed = runCatching { repo.finish(id, RunState.RUNNING, null, null, null, 0) }.exceptionOrNull()
        assertTrue(failed is IllegalArgumentException)
    }
}
