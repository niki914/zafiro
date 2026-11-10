package com.tricreta.zafiro.autonomy.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnceTimeCheckTest {
    private val nairobi = ZoneId.of("Africa/Nairobi")
    private val utc = ZoneId.of("UTC")

    // The reported scenario: 6:48 PM in Kenya on 10 Nov 2026 (15:48 UTC).
    private val now = ZonedDateTime.of(2026, 11, 10, 18, 48, 0, 0, nairobi).toInstant()
    private val today = LocalDate.of(2026, 11, 10)

    private fun check(h: Int, m: Int, zone: ZoneId = nairobi, date: LocalDate = today, at: Instant = now) =
        OnceTimeCheck.check(date, LocalTime.of(h, m), zone, at)

    @Test
    fun `the reported case - 18_53 at 18_48 is accepted for 5 10 and 20 minutes ahead`() {
        for (mins in listOf(5, 10, 20, 60)) {
            val t = ZonedDateTime.of(2026, 11, 10, 18, 48, 0, 0, nairobi).plusMinutes(mins.toLong())
            val r = check(t.hour, t.minute)
            assertIs<OnceCheck.Accepted>(r, "+$mins min")
            assertEquals(t.toInstant(), r.at.toInstant())
        }
    }

    @Test
    fun `accepted instant is the Nairobi wall time, which is UTC plus 3`() {
        val r = check(18, 53) as OnceCheck.Accepted
        assertEquals(Instant.parse("2026-11-10T15:53:00Z"), r.at.toInstant())
    }

    @Test
    fun `typing 6_53 (a morning time) at 6_48 PM is rejected with an exact explanation and the 18_53 suggestion`() {
        val r = check(6, 53)
        assertIs<OnceCheck.InPast>(r)
        assertEquals(18, r.suggestion!!.hour)
        assertEquals(53, r.suggestion!!.minute)
        val msg = OnceTimeCheck.explain(r)
        assertTrue(msg.contains("06:53"), msg)
        assertTrue(msg.contains("18:48"), msg)
        assertTrue(msg.contains("18:53"), msg)
    }

    @Test
    fun `a genuinely past evening time has no misleading suggestion`() {
        val r = check(18, 30)
        assertIs<OnceCheck.InPast>(r)
        assertNull(r.suggestion)
    }

    @Test
    fun `exactly now is not in the future`() {
        assertIs<OnceCheck.InPast>(check(18, 48))
    }

    @Test
    fun `yesterday is rejected even if the clock time is ahead`() {
        assertIs<OnceCheck.InPast>(check(19, 0, date = today.minusDays(1)))
    }

    @Test
    fun `tomorrow morning is accepted`() {
        assertIs<OnceCheck.Accepted>(check(6, 53, date = today.plusDays(1)))
    }

    @Test
    fun `the zone decides - the same digits are a different instant in UTC`() {
        // Same wall digits 18:53 on the same date: in Nairobi it is 5 min away, in UTC it is 3 h away (still future),
        // and 15:53 UTC-digits is the one that is in the past in UTC but in the future in Nairobi.
        assertIs<OnceCheck.Accepted>(check(15, 53, zone = utc))     // 15:53 UTC is 5 min after now (15:48 UTC)
        assertIs<OnceCheck.InPast>(check(15, 53, zone = nairobi))    // 15:53 Nairobi (12:53 UTC) already passed
    }

    @Test
    fun `daylight saving gap resolves to the first valid instant`() {
        val ny = ZoneId.of("America/New_York")
        val at = ZonedDateTime.of(2026, 3, 8, 1, 0, 0, 0, ny).toInstant()
        val r = OnceTimeCheck.check(LocalDate.of(2026, 3, 8), LocalTime.of(2, 30), ny, at) as OnceCheck.Accepted
        assertEquals(3, r.at.hour)
        assertEquals(30, r.at.minute)
    }

    @Test
    fun `blank stored zone follows the device zone and a stored zone is respected`() {
        assertEquals(nairobi, ZoneResolver.resolve("", nairobi))
        assertEquals(nairobi, ZoneResolver.resolve("  ", nairobi))
        assertEquals(utc, ZoneResolver.resolve("UTC", nairobi))
        val london = ZoneId.of("Europe/London")
        assertEquals(london, ZoneResolver.resolve("", london)) // the phone travelled
    }
}
