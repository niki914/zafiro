package com.tricreta.zafiro.autonomy

import com.tricreta.zafiro.autonomy.core.TimeTrigger
import com.tricreta.zafiro.autonomy.data.WEEKDAYS
import com.tricreta.zafiro.autonomy.data.daysToMask
import com.tricreta.zafiro.autonomy.data.describeSchedule
import com.tricreta.zafiro.autonomy.data.maskToDays
import com.tricreta.zafiro.autonomy.data.toBudget
import com.tricreta.zafiro.autonomy.data.toSpec
import com.tricreta.zafiro.autonomy.form.DraftField
import com.tricreta.zafiro.autonomy.form.DraftResult
import com.tricreta.zafiro.autonomy.form.ScheduleKind
import com.tricreta.zafiro.autonomy.form.TaskDraft
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskDraftTest {
    private val nairobi = ZoneId.of("Africa/Nairobi")
    private val london = ZoneId.of("Europe/London")
    private val now = ZonedDateTime.of(2026, 11, 10, 18, 48, 0, 0, nairobi).toInstant().toEpochMilli() // 6:48 PM in Kenya
    private val today = LocalDate.of(2026, 11, 10)
    private val base = TaskDraft(name = "Morning", prompt = "Check my email", kind = ScheduleKind.DAILY, date = today, time = LocalTime.of(8, 0))

    private fun ok(d: TaskDraft, zone: ZoneId = nairobi) = (d.toEntity("id", null, now, zone) as DraftResult.Ok).task
    private fun invalid(d: TaskDraft) = d.toEntity("id", null, now, nairobi) as DraftResult.Invalid

    @Test
    fun `daily task follows the device zone instead of pinning one`() {
        val t = ok(base)
        assertEquals("", t.zoneId)
        assertEquals(TimeTrigger.Daily(LocalTime.of(8, 0)), t.toSpec(nairobi).trigger)
        assertEquals(nairobi, t.toSpec(nairobi).zone)
        assertEquals(london, t.toSpec(london).zone) // the phone changed zone: the task moves with it
    }

    @Test
    fun `the reported scenario - scheduling 5 10 and 20 minutes ahead at 6_48 PM is accepted`() {
        for (mins in listOf(5, 10, 20)) {
            val at = ZonedDateTime.of(2026, 11, 10, 18, 48, 0, 0, nairobi).plusMinutes(mins.toLong())
            val d = base.copy(kind = ScheduleKind.ONCE, time = LocalTime.of(at.hour, at.minute))
            assertIs<DraftResult.Ok>(d.toEntity("id", null, now, nairobi), "+$mins min")
        }
    }

    @Test
    fun `a morning time at 6_48 PM is rejected with both times and the PM suggestion`() {
        val r = invalid(base.copy(kind = ScheduleKind.ONCE, time = LocalTime.of(6, 53)))
        assertEquals(DraftField.TIME, r.field)
        assertTrue(r.message.contains("06:53") && r.message.contains("18:48") && r.message.contains("18:53"), r.message)
    }

    @Test
    fun `once task stores wall time and no zone, and resolves in the device zone`() {
        val t = ok(base.copy(kind = ScheduleKind.ONCE, time = LocalTime.of(18, 53)))
        assertEquals("2026-11-10T18:53", t.onceAt)
        val trigger = t.toSpec(nairobi).trigger as TimeTrigger.Once
        assertEquals(java.time.Instant.parse("2026-11-10T15:53:00Z"), trigger.at.atZone(nairobi).toInstant())
    }

    @Test
    fun `weekdays become Weekly Monday to Friday`() {
        val t = ok(base.copy(kind = ScheduleKind.WEEKDAYS))
        assertEquals(WEEKDAYS, (t.toSpec(nairobi).trigger as TimeTrigger.Weekly).days)
        assertEquals("Weekdays at 08:00", t.describeSchedule())
    }

    @Test
    fun `manual task has no trigger`() {
        assertNull(ok(base.copy(kind = ScheduleKind.MANUAL)).toSpec(nairobi).trigger)
    }

    @Test
    fun `invalid input is rejected with the field and a readable message`() {
        assertEquals(DraftField.NAME, invalid(base.copy(name = " ")).field)
        assertEquals(DraftField.PROMPT, invalid(base.copy(prompt = "")).field)
        assertEquals(DraftField.STEPS, invalid(base.copy(maxSteps = "0")).field)
        assertEquals(DraftField.STEPS, invalid(base.copy(maxSteps = "abc")).field)
        assertEquals(DraftField.MINUTES, invalid(base.copy(maxMinutes = "999")).field)
        assertEquals(DraftField.INTERVAL, invalid(base.copy(kind = ScheduleKind.INTERVAL, everyMinutes = "0")).field)
    }

    @Test
    fun `editing round trips the picker values`() {
        val t = ok(base.copy(kind = ScheduleKind.ONCE, date = today.plusDays(2), time = LocalTime.of(9, 5)))
        val back = TaskDraft.from(t, today)
        assertEquals(ScheduleKind.ONCE, back.kind)
        assertEquals(today.plusDays(2), back.date)
        assertEquals(LocalTime.of(9, 5), back.time)
        val daily = TaskDraft.from(ok(base), today)
        assertEquals(LocalTime.of(8, 0), daily.time)
    }

    @Test
    fun `self test is one minute ahead in the device zone with tight limits`() {
        val t = TaskDraft.selfTest(now, nairobi)
        val due = (t.toSpec(nairobi).trigger as TimeTrigger.Once).at.atZone(nairobi).toInstant().toEpochMilli()
        assertTrue(due > now && due - now <= 61_000, "due in ${due - now} ms")
        assertEquals(5, t.toBudget().maxSteps)
        assertEquals("", t.zoneId)
    }

    @Test
    fun `weekday mask round trips`() {
        val days = setOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY)
        assertEquals(days, maskToDays(daysToMask(days)))
    }
}
