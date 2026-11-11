package com.tricreta.zafiro.autonomy.core

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TriggerMathTest {
    private val nairobi = ZoneId.of("Africa/Nairobi")
    private val newYork = ZoneId.of("America/New_York")
    private val utc = ZoneId.of("UTC")

    private fun at(zone: ZoneId, y: Int, mo: Int, d: Int, h: Int, mi: Int = 0): Instant =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant()

    private fun TimeTrigger.nextAfter(zone: ZoneId, after: Instant) = TriggerMath.next(this, zone, after)

    @Test
    fun `daily 0800 Nairobi fires at 05 00 UTC and rolls to next day`() {
        val t = TimeTrigger.Daily(LocalTime.of(8, 0))
        val first = t.nextAfter(nairobi, at(nairobi, 2026, 11, 10, 7, 0))
        assertEquals(Instant.parse("2026-11-10T05:00:00Z"), first)
        val second = t.nextAfter(nairobi, first!!)
        assertEquals(Instant.parse("2026-11-11T05:00:00Z"), second)
    }

    @Test
    fun `daily is strictly after the given instant`() {
        val t = TimeTrigger.Daily(LocalTime.of(8, 0))
        val due = at(nairobi, 2026, 11, 10, 8, 0)
        assertEquals(at(nairobi, 2026, 11, 11, 8, 0), t.nextAfter(nairobi, due))
    }

    @Test
    fun `same wall time in a different zone is a different instant`() {
        val t = TimeTrigger.Daily(LocalTime.of(8, 0))
        val after = Instant.parse("2026-11-10T00:00:00Z")
        assertEquals(Instant.parse("2026-11-10T05:00:00Z"), t.nextAfter(nairobi, after))
        assertEquals(Instant.parse("2026-11-10T13:00:00Z"), t.nextAfter(newYork, after)) // EST, UTC-5
    }

    @Test
    fun `weekly weekdays skips the weekend`() {
        val days = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
        val t = TimeTrigger.Weekly(days, LocalTime.of(8, 0))
        // 2026-11-13 is a Friday; after Friday 08:00 the next is Monday 2026-11-16
        val fri = at(nairobi, 2026, 11, 13, 8, 0)
        assertEquals(at(nairobi, 2026, 11, 16, 8, 0), t.nextAfter(nairobi, fri))
    }

    @Test
    fun `weekly requires at least one day`() {
        assertFailsWith<IllegalArgumentException> { TimeTrigger.Weekly(emptySet(), LocalTime.NOON) }
    }

    @Test
    fun `monthly 31st clamps to the last day of shorter months`() {
        val t = TimeTrigger.Monthly(31, LocalTime.of(9, 0))
        assertEquals(at(utc, 2026, 2, 28, 9), t.nextAfter(utc, at(utc, 2026, 2, 1, 0)))
        assertEquals(at(utc, 2026, 4, 30, 9), t.nextAfter(utc, at(utc, 2026, 4, 1, 0)))
        assertEquals(at(utc, 2026, 5, 31, 9), t.nextAfter(utc, at(utc, 2026, 5, 1, 0)))
    }

    @Test
    fun `monthly LAST_DAY handles leap february`() {
        val t = TimeTrigger.Monthly(TimeTrigger.LAST_DAY, LocalTime.of(9, 0))
        assertEquals(at(utc, 2028, 2, 29, 9), t.nextAfter(utc, at(utc, 2028, 2, 1, 0))) // 2028 is a leap year
        assertEquals(at(utc, 2027, 2, 28, 9), t.nextAfter(utc, at(utc, 2027, 2, 1, 0)))
    }

    @Test
    fun `monthly 29th in non leap february clamps to 28`() {
        val t = TimeTrigger.Monthly(29, LocalTime.of(9, 0))
        assertEquals(at(utc, 2027, 2, 28, 9), t.nextAfter(utc, at(utc, 2027, 2, 1, 0)))
        assertEquals(at(utc, 2028, 2, 29, 9), t.nextAfter(utc, at(utc, 2028, 2, 1, 0)))
    }

    @Test
    fun `daily across the new york spring forward gap fires at first valid instant`() {
        // 2026-03-08 02:30 does not exist in New York (clocks jump 02:00 -> 03:00)
        val t = TimeTrigger.Daily(LocalTime.of(2, 30))
        val fired = t.nextAfter(newYork, at(newYork, 2026, 3, 7, 3, 0))!!
        assertEquals(ZonedDateTime.of(2026, 3, 8, 3, 30, 0, 0, newYork).toInstant(), fired)
    }

    @Test
    fun `daily across the fall back overlap fires once on the first occurrence`() {
        // 2026-11-01 01:30 happens twice in New York
        val t = TimeTrigger.Daily(LocalTime.of(1, 30))
        val first = t.nextAfter(newYork, at(newYork, 2026, 10, 31, 12, 0))!!
        assertEquals(Instant.parse("2026-11-01T05:30:00Z"), first) // EDT, UTC-4: the earlier one
        val next = t.nextAfter(newYork, first)!!
        assertEquals(ZonedDateTime.of(2026, 11, 2, 1, 30, 0, 0, newYork).toInstant(), next) // not the repeated 01:30 EST
    }

    @Test
    fun `once fires only while in the future`() {
        val t = TimeTrigger.Once(LocalDateTime.of(2026, 11, 10, 8, 0))
        assertEquals(at(nairobi, 2026, 11, 10, 8), t.nextAfter(nairobi, at(nairobi, 2026, 11, 10, 7, 59)))
        assertNull(t.nextAfter(nairobi, at(nairobi, 2026, 11, 10, 8)))
        assertNull(t.nextAfter(nairobi, at(nairobi, 2026, 11, 11, 8)))
    }

    @Test
    fun `interval is anchored and does not drift`() {
        val anchor = Instant.parse("2026-11-10T00:00:00Z")
        val t = TimeTrigger.Interval(Duration.ofMinutes(45), anchor)
        assertEquals(Instant.parse("2026-11-10T00:45:00Z"), t.nextAfter(utc, anchor))
        // asked late (00:50): next is 01:30, not 00:50+45
        assertEquals(Instant.parse("2026-11-10T01:30:00Z"), t.nextAfter(utc, Instant.parse("2026-11-10T00:50:00Z")))
        // before the anchor: first occurrence is the anchor itself
        assertEquals(anchor, t.nextAfter(utc, anchor.minusSeconds(10)))
    }

    @Test
    fun `interval below one minute is rejected`() {
        assertFailsWith<IllegalArgumentException> { TimeTrigger.Interval(Duration.ofSeconds(30), Instant.EPOCH) }
    }

    @Test
    fun `window counts daily occurrences and returns the latest`() {
        val t = TimeTrigger.Daily(LocalTime.of(8, 0))
        val w = TriggerMath.window(t, nairobi, at(nairobi, 2026, 11, 1, 12), at(nairobi, 2026, 11, 5, 9))
        // 2,3,4,5 Nov at 08:00
        assertEquals(4, w.count)
        assertEquals(at(nairobi, 2026, 11, 5, 8), w.latest)
    }

    @Test
    fun `window is half open (from exclusive, to inclusive)`() {
        val t = TimeTrigger.Daily(LocalTime.of(8, 0))
        val due = at(nairobi, 2026, 11, 5, 8)
        assertEquals(0, TriggerMath.window(t, nairobi, due, due.plusSeconds(60)).count)
        assertEquals(1, TriggerMath.window(t, nairobi, due.minusSeconds(1), due).count)
    }

    @Test
    fun `interval window is computed arithmetically even over a month`() {
        val anchor = Instant.parse("2026-01-01T00:00:00Z")
        val t = TimeTrigger.Interval(Duration.ofMinutes(1), anchor)
        val w = TriggerMath.window(t, utc, Instant.parse("2026-02-01T00:00:00Z"), Instant.parse("2026-03-01T00:00:00Z"))
        assertEquals(28L * 24 * 60, w.count)
        assertEquals(Instant.parse("2026-03-01T00:00:00Z"), w.latest)
    }
}
