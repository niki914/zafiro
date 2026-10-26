// 保护：账页迁移写错时，同一天会发出两行，或把开页时写死的 prev、回拨、过期页和删过的日期改掉
package com.niki914.zafiro.business.remote.ledger

import com.niki914.logging.Backend
import com.niki914.logging.Level
import com.niki914.logging.Logger
import com.niki914.zafiro.api.telemetry.CustomValue
import com.niki914.zafiro.api.telemetry.TelemetryContract
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LedgerTest {
    /** JVM 单测没有 Android 框架，默认的 LogcatBackend 会在业务代码打日志时抛异常。 */
    @BeforeTest
    fun silenceLogger() {
        Logger.install(object : Backend {
            override fun emit(level: Level, tag: String, msg: String, throwable: Throwable?) = Unit
        })
    }

    @Test
    fun `the first active day opens one page at rev 1 with no previous day`() = runBlocking {
        val fx = Fixture("2026-10-26")
        fx.ledger.markActive(16)
        val page = fx.saved().pages.single()
        assertEquals(TelemetryContract.LIMIT_REV_MIN, page.rev)
        assertNull(page.prevActiveDay)
        assertNull(page.prevVersionCode)
    }

    @Test
    fun `a new day key is a lowercase uuid v4`() = runBlocking {
        val fx = Fixture("2026-10-26")
        fx.ledger.markActive(16)
        assertTrue(UUID_V4.matches(fx.saved().pages.single().dayKey))
    }

    @Test
    fun `marking the same day and version again leaves the page alone`() = runBlocking {
        val fx = Fixture("2026-10-26")
        fx.ledger.markActive(16)
        val saved = fx.saved()
        fx.ledger.markActive(16)
        assertEquals(saved, fx.saved())
    }

    @Test
    fun `a version change on the same day bumps rev and keeps the day key`() = runBlocking {
        val fx = Fixture("2026-10-26")
        fx.ledger.markActive(16)
        val key = fx.saved().pages.single().dayKey
        fx.ledger.markActive(17)
        val page = fx.saved().pages.single()
        assertEquals(key, page.dayKey)
        assertEquals(TelemetryContract.LIMIT_REV_MIN + 1, page.rev)
        assertEquals(17, page.versionCode)
    }

    @Test
    fun `a version below the contract minimum does not open a page`() = runBlocking {
        val fx = Fixture("2026-10-26")
        fx.ledger.markActive(TelemetryContract.LIMIT_VERSION_CODE_MIN - 1)
        assertNull(fx.store.saved)
    }

    @Test
    fun `an ack whose rev matches a past day deletes that page and keeps last active`() = runBlocking {
        val day = date("2026-10-26")
        val fx = Fixture(day.toString(), active(day))
        val key = fx.saved().pages.single().dayKey
        fx.ledger.onAck(mapOf(key to TelemetryContract.LIMIT_REV_MIN), day.plusDays(1))
        assertEquals(emptyList(), fx.saved().pages)
        assertEquals(day, fx.saved().lastActive?.day)
        assertEquals(listOf(day), fx.saved().deletedDays)
    }

    @Test
    fun `an ack rev below the local rev keeps the page pending`() = runBlocking {
        val day = date("2026-10-26")
        val page = sample(day).copy(rev = 2, versionCode = 17)
        val fx = Fixture(day.toString(), LedgerState(lastActive = LastActive(day, 17), pages = listOf(page)))
        fx.ledger.onAck(mapOf(page.dayKey to TelemetryContract.LIMIT_REV_MIN), day.plusDays(1))
        val kept = fx.saved().pages.single()
        assertEquals(TelemetryContract.LIMIT_REV_MIN, kept.ackedRev)
        assertEquals(2, kept.rev)
        assertEquals(listOf(page.dayKey), fx.ledger.snapshotForSend(day.plusDays(1)).days.map { it.dayKey })
    }

    @Test
    fun `acking the current day records the sent rev and leaves the page in place`() = runBlocking {
        val day = date("2026-10-26")
        val fx = Fixture(day.toString(), active(day))
        val key = fx.saved().pages.single().dayKey
        fx.ledger.onAck(mapOf(key to TelemetryContract.LIMIT_REV_MIN), day)
        assertEquals(TelemetryContract.LIMIT_REV_MIN, fx.saved().pages.single().ackedRev)
        assertTrue(fx.ledger.snapshotForSend(day).days.isEmpty())
    }

    @Test
    fun `a change after ack publishes the next rev on the same day key`() = runBlocking {
        val fx = Fixture("2026-10-26")
        fx.ledger.markActive(16)
        val key = fx.saved().pages.single().dayKey
        fx.ledger.onAck(mapOf(key to TelemetryContract.LIMIT_REV_MIN), fx.clock.day)
        fx.ledger.markActive(17)
        val page = fx.saved().pages.single()
        assertEquals(key, page.dayKey)
        assertEquals(TelemetryContract.LIMIT_REV_MIN + 1, page.rev)
        assertEquals(TelemetryContract.LIMIT_REV_MIN, page.ackedRev)
    }

    @Test
    fun `a dropped page is counted once and that active day does not reopen`() = runBlocking {
        val fx = Fixture("2026-10-26")
        fx.ledger.markActive(16)
        val key = fx.saved().pages.single().dayKey
        fx.ledger.onDropped(listOf(key))
        fx.ledger.markActive(16)
        assertEquals(emptyList(), fx.saved().pages)
        assertEquals(1, fx.saved().droppedDays)
        assertEquals(fx.clock.day, fx.saved().lastActive?.day)
        assertEquals(listOf(fx.clock.day), fx.saved().deletedDays)
    }

    @Test
    fun `disabling clears pending pages counts them and does not reopen that day`() = runBlocking {
        val confirmed = date("2026-10-25")
        val today = date("2026-10-26")
        val fx = Fixture(confirmed.toString())
        fx.ledger.markActive(16)
        val confirmedKey = fx.saved().pages.single().dayKey
        fx.ledger.onAck(mapOf(confirmedKey to TelemetryContract.LIMIT_REV_MIN), confirmed)
        fx.clock.day = today
        fx.ledger.markActive(16)
        fx.ledger.clearForDisabled()
        fx.ledger.markActive(16)
        assertEquals(listOf(confirmed), fx.saved().pages.map { it.day })
        assertEquals(1, fx.saved().droppedDays)
        assertEquals(today, fx.saved().lastActive?.day)
        assertEquals(listOf(today), fx.saved().deletedDays)
    }

    @Test
    fun `ack subtracts the dropped count from the snapshot and keeps later drops`() = runBlocking {
        val fx = Fixture("2026-10-01")
        fx.ledger.markActive(16)
        fx.clock.day = date("2026-10-20")
        fx.ledger.markActive(16)
        val snap = fx.ledger.snapshotForSend(fx.clock.day)
        assertEquals(1, snap.droppedDays)
        fx.ledger.onDropped(snap.days.map { it.dayKey })
        fx.ledger.onAck(emptyMap(), fx.clock.day)
        assertEquals(1, fx.saved().droppedDays)
    }

    @Test
    fun `ack without a snapshot does not clear dropped days`() = runBlocking {
        val fx = Fixture("2026-10-01")
        fx.ledger.markActive(16)
        fx.clock.day = date("2026-10-20")
        fx.ledger.markActive(16)
        val key = fx.saved().pages.single().dayKey
        fx.ledger.onAck(mapOf(key to TelemetryContract.LIMIT_REV_MIN), fx.clock.day)
        assertEquals(1, fx.saved().droppedDays)
    }

    @Test
    fun `snapshot writes the pruned ledger before it returns`() = runBlocking {
        val today = date("2026-10-26")
        val stale = today.minusDays(TelemetryContract.LIMIT_CLIENT_RETENTION_PAST_DAYS.toLong() + 1)
        val fx = Fixture(
            today.toString(),
            LedgerState(
                lastActive = LastActive(today, 16),
                pages = listOf(sample(stale, "stale-key"), sample(today, "today-key")),
            ),
        )
        fx.ledger.snapshotForSend(today)
        assertEquals(listOf(today), fx.saved().pages.map { it.day })
    }

    @Test
    fun `a new ledger resends the same day key`() = runBlocking {
        val fx = Fixture("2026-10-26")
        fx.ledger.markActive(16)
        val key = fx.saved().pages.single().dayKey
        val revived = Ledger(fx.clock, fx.store)
        revived.markActive(16)
        assertEquals(listOf(key), revived.snapshotForSend(fx.clock.day).days.map { it.dayKey })
    }

    @Test
    fun `prev stays at the value written when the page opened`() = runBlocking {
        val fx = Fixture("2026-10-20")
        fx.ledger.markActive(16)
        val earlierKey = fx.saved().pages.single().dayKey
        fx.clock.day = date("2026-10-24")
        fx.ledger.markActive(17)
        fx.ledger.onDropped(listOf(earlierKey))
        val page = fx.saved().pages.single()
        assertEquals(date("2026-10-20"), page.prevActiveDay)
        assertEquals(16, page.prevVersionCode)
        assertEquals(17, page.versionCode)
    }

    @Test
    fun `opening a later day keeps counts already stored on the earlier page`() = runBlocking {
        val earlier = date("2026-10-20")
        val fx = Fixture(
            earlier.toString(),
            LedgerState(
                lastActive = LastActive(earlier, 16),
                pages = listOf(
                    sample(earlier).copy(
                        counts = listOf(PageCount("tool_call", null, 4, emptyMap())),
                    ),
                ),
            ),
        )
        fx.clock.day = date("2026-10-24")
        fx.ledger.markActive(16)
        val kept = fx.saved().pages.first { it.day == earlier }
        assertEquals(4, kept.counts.single().n)
    }

    @Test
    fun `rolling the clock back by the contract limit does not change the ledger`() = runBlocking {
        val today = date("2026-10-26")
        val ancient = today.minusDays(
            TelemetryContract.LIMIT_CLOCK_ROLLBACK_DAYS.toLong() +
                TelemetryContract.LIMIT_CLIENT_RETENTION_PAST_DAYS.toLong() + 1,
        )
        val initial = LedgerState(
            lastActive = LastActive(today, 16),
            pages = listOf(sample(ancient, "ancient"), sample(today, "today")),
            deletedDays = listOf(today.minusDays(1)),
        )
        val fx = Fixture(today.toString(), initial)
        fx.clock.day = today.minusDays(TelemetryContract.LIMIT_CLOCK_ROLLBACK_DAYS.toLong())
        fx.ledger.markActive(99)
        assertEquals(initial, fx.saved())
    }

    @Test
    fun `rolling the clock back past the contract limit clears later pages and opens with null prev`() = runBlocking {
        val today = date("2026-10-26")
        val rolled = today.minusDays(TelemetryContract.LIMIT_CLOCK_ROLLBACK_DAYS.toLong() + 1)
        val confirmed = sample(today.minusDays(2), "confirmed").copy(ackedRev = TelemetryContract.LIMIT_REV_MIN)
        val pending = sample(today, "pending")
        val fx = Fixture(
            rolled.toString(),
            LedgerState(lastActive = LastActive(today, 16), pages = listOf(confirmed, pending)),
        )
        fx.ledger.markActive(16)
        val page = fx.saved().pages.single()
        assertEquals(rolled, page.day)
        assertNull(page.prevActiveDay)
        assertEquals(1, fx.saved().droppedDays)
    }

    @Test
    fun `rolling back onto a day that still has a page reuses that day key`() = runBlocking {
        val today = date("2026-10-26")
        val rolled = today.minusDays(TelemetryContract.LIMIT_CLOCK_ROLLBACK_DAYS.toLong() + 1)
        val fx = Fixture(
            rolled.toString(),
            LedgerState(
                lastActive = LastActive(today, 16),
                pages = listOf(sample(rolled, "rolled-key"), sample(today, "later-key")),
            ),
        )
        fx.ledger.markActive(16)
        assertEquals(listOf("rolled-key"), fx.saved().pages.map { it.dayKey })
    }

    @Test
    fun `a deleted day stays closed when the clock rolls back past the limit and returns`() = runBlocking {
        val today = date("2026-10-26")
        val rolled = today.minusDays(TelemetryContract.LIMIT_CLOCK_ROLLBACK_DAYS.toLong() + 1)
        val fx = Fixture(
            rolled.toString(),
            LedgerState(
                lastActive = LastActive(today, 16),
                pages = listOf(sample(today, "later-key")),
                deletedDays = listOf(rolled),
            ),
        )
        fx.ledger.markActive(16)
        assertEquals(emptyList(), fx.saved().pages)
        fx.clock.day = today
        fx.ledger.markActive(16)
        assertEquals(emptyList(), fx.saved().pages)
        assertNull(fx.saved().lastActive)
    }

    @Test
    fun `deleted days older than the memory window are removed`() = runBlocking {
        val today = date("2026-10-26")
        val kept = today.minusDays(TelemetryContract.LIMIT_DELETED_DAY_MEMORY_DAYS.toLong())
        val stale = kept.minusDays(1)
        val fx = Fixture(
            today.toString(),
            LedgerState(
                lastActive = LastActive(today, 16),
                pages = listOf(sample(today)),
                deletedDays = listOf(stale, kept),
            ),
        )
        fx.ledger.markActive(16)
        assertEquals(listOf(kept), fx.saved().deletedDays)
    }

    @Test
    fun `a page older than the retained week is dropped and a page inside it stays`() = runBlocking {
        val today = date("2026-10-26")
        val inside = today.minusDays(TelemetryContract.LIMIT_CLIENT_RETENTION_PAST_DAYS.toLong())
        val outside = inside.minusDays(1)
        val fx = Fixture(
            today.toString(),
            LedgerState(
                lastActive = LastActive(today, 16),
                pages = listOf(
                    sample(outside, "outside"),
                    sample(inside, "inside").copy(ackedRev = TelemetryContract.LIMIT_REV_MIN),
                    sample(today, "today"),
                ),
            ),
        )
        val snap = fx.ledger.snapshotForSend(today)
        assertEquals(listOf(inside, today), fx.saved().pages.map { it.day })
        assertEquals(1, fx.saved().droppedDays)
        assertEquals(listOf(outside), fx.saved().deletedDays)
        assertEquals(listOf(today), snap.days.map { it.day })
    }

    @Test
    fun `snapshot days are ordered by activity day`() = runBlocking {
        val earlier = date("2026-10-24")
        val later = date("2026-10-26")
        val fx = Fixture(
            later.toString(),
            LedgerState(
                lastActive = LastActive(later, 16),
                pages = listOf(sample(later, "later"), sample(earlier, "earlier")),
            ),
        )
        val days = fx.ledger.snapshotForSend(later).days.map { it.day }
        assertEquals(listOf(earlier, later), days)
    }

    @Test
    fun `snapshot returns envelope custom stored on the ledger`() = runBlocking {
        val today = date("2026-10-26")
        val fx = Fixture(
            today.toString(),
            LedgerState(
                lastActive = LastActive(today, 16),
                pages = listOf(sample(today)),
                envelopeCustom = mapOf("n" to CustomValue.Num(3)),
            ),
        )
        assertEquals(CustomValue.Num(3), fx.ledger.snapshotForSend(today).envelopeCustom["n"])
    }

    @Test
    fun `a failed save does not publish a page the restart would miss`() = runBlocking {
        val fx = Fixture("2026-10-26")
        fx.store.failSaves = true
        fx.ledger.markActive(16)
        assertTrue(fx.ledger.snapshotForSend(fx.clock.day).days.isEmpty())
    }
}

private class Fixture(
    day: String,
    initial: LedgerState? = null,
) {
    val clock = ManualClock(date(day))
    val store = MemoryLedgerStore(initial)
    val ledger = Ledger(clock, store)

    fun saved(): LedgerState = checkNotNull(store.saved)
}

private class ManualClock(var day: LocalDate) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = day.atStartOfDay(ZoneOffset.UTC).toInstant()
}

private class MemoryLedgerStore(initial: LedgerState?) : LedgerStore {
    var saved: LedgerState? = initial
    var failSaves: Boolean = false

    override suspend fun load(): LedgerLoad {
        val current = saved
        return if (current == null) LedgerLoad.Missing else LedgerLoad.Ready(current)
    }

    override suspend fun save(state: LedgerState) {
        if (failSaves) throw IOException("disk")
        saved = state
    }
}

private fun date(text: String): LocalDate = LocalDate.parse(text)

private fun sample(day: LocalDate, key: String = "6f1c2a4e-8d3b-4c1a-9e2f-0b7d5a3c9e11"): DayPage {
    return DayPage(
        day = day,
        dayKey = key,
        rev = TelemetryContract.LIMIT_REV_MIN,
        ackedRev = 0,
        versionCode = 16,
        prevActiveDay = null,
        prevVersionCode = null,
        counts = emptyList(),
        countsTruncated = TelemetryContract.LIMIT_COUNTS_TRUNCATED_MIN,
        firsts = emptyList(),
        custom = emptyMap(),
    )
}

private fun active(day: LocalDate): LedgerState {
    return LedgerState(lastActive = LastActive(day, 16), pages = listOf(sample(day)))
}

private val UUID_V4 = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
