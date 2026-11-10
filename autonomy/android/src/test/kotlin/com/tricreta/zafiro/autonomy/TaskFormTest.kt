package com.tricreta.zafiro.autonomy

import com.tricreta.zafiro.autonomy.core.TimeTrigger
import com.tricreta.zafiro.autonomy.data.WEEKDAYS
import com.tricreta.zafiro.autonomy.data.daysToMask
import com.tricreta.zafiro.autonomy.data.describeSchedule
import com.tricreta.zafiro.autonomy.data.maskToDays
import com.tricreta.zafiro.autonomy.data.toBudget
import com.tricreta.zafiro.autonomy.data.toSpec
import com.tricreta.zafiro.autonomy.ui.ScheduleKind
import com.tricreta.zafiro.autonomy.ui.TaskForm
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskFormTest {
    private val base = TaskForm(name = "Morning", prompt = "Check my email", kind = ScheduleKind.DAILY, time = "08:00", zone = "Africa/Nairobi")

    @Test
    fun `daily form becomes a Daily trigger in the chosen zone`() {
        val t = base.toEntity("id", nowMs = 1_000).getOrThrow()
        assertEquals(TimeTrigger.Daily(LocalTime.of(8, 0)), t.toSpec().trigger)
        assertEquals("Africa/Nairobi", t.zoneId)
        assertEquals("Daily at 08:00 · Africa/Nairobi", t.describeSchedule())
    }

    @Test
    fun `single digit hour is accepted`() {
        assertEquals(LocalTime.of(7, 5), TaskForm.parseTime("7:05"))
    }

    @Test
    fun `weekdays become Weekly Monday to Friday`() {
        val t = base.copy(kind = ScheduleKind.WEEKDAYS).toEntity("id", nowMs = 1_000).getOrThrow()
        assertEquals(WEEKDAYS, (t.toSpec().trigger as TimeTrigger.Weekly).days)
        assertTrue(t.describeSchedule().startsWith("Weekdays at 08:00"))
    }

    @Test
    fun `manual task has no trigger`() {
        val t = base.copy(kind = ScheduleKind.MANUAL).toEntity("id", nowMs = 1_000).getOrThrow()
        assertNull(t.toSpec().trigger)
    }

    @Test
    fun `invalid input is rejected with a readable message`() {
        assertEquals("Give the task a name.", base.copy(name = " ").toEntity("i").exceptionOrNull()?.message)
        assertEquals("Describe what Zafiro should do.", base.copy(prompt = "").toEntity("i").exceptionOrNull()?.message)
        assertTrue(base.copy(zone = "Mars/Base").toEntity("i").exceptionOrNull()!!.message!!.contains("Unknown time zone"))
        assertEquals("Time must look like 08:00.", base.copy(time = "8am").toEntity("i").exceptionOrNull()?.message)
        assertEquals("Time must be between 00:00 and 23:59.", base.copy(time = "25:00").toEntity("i").exceptionOrNull()?.message)
        assertEquals("Max tool calls must be 1-500.", base.copy(maxSteps = "0").toEntity("i").exceptionOrNull()?.message)
    }

    @Test
    fun `once in the past is rejected`() {
        val f = base.copy(kind = ScheduleKind.ONCE, date = "2020-01-01", time = "08:00")
        assertEquals("That time is already in the past.", f.toEntity("i").exceptionOrNull()?.message)
    }

    @Test
    fun `interval below one minute is rejected`() {
        val f = base.copy(kind = ScheduleKind.INTERVAL, everyMinutes = "0")
        assertEquals("Interval must be at least 1 minute.", f.toEntity("i").exceptionOrNull()?.message)
    }

    @Test
    fun `self test is a one-shot a minute ahead with tight limits`() {
        val now = 1_700_000_000_000L
        val t = TaskForm.selfTest(now)
        val trigger = t.toSpec().trigger as TimeTrigger.Once
        val due = trigger.at.atZone(java.time.ZoneId.of(t.zoneId)).toInstant().toEpochMilli()
        assertTrue(due > now && due - now <= 61_000, "due in ${due - now} ms")
        assertEquals(5, t.toBudget().maxSteps)
    }

    @Test
    fun `weekday mask round trips`() {
        val days = setOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY)
        assertEquals(days, maskToDays(daysToMask(days)))
        assertEquals(0b1111111, daysToMask(DayOfWeek.entries.toSet()))
    }
}
