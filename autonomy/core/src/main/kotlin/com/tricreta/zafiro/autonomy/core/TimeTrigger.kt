package com.tricreta.zafiro.autonomy.core

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * A wall-clock trigger. Local times are interpreted in the task's [ZoneId], so "08:00 Africa/Nairobi" stays 08:00
 * local whatever the phone's current zone is.
 *
 * Daylight-saving rules (java.time semantics, tested):
 * - a local time that does not exist (spring-forward gap) fires at the first valid instant after the gap;
 * - a local time that exists twice (fall-back overlap) fires once, on the first occurrence.
 */
sealed interface TimeTrigger {

    /** One-shot at a local date-time. */
    data class Once(val at: LocalDateTime) : TimeTrigger

    /** Every [every] since [anchor]. Anchored, so it never drifts when a run is late or the phone reboots. */
    data class Interval(val every: Duration, val anchor: Instant) : TimeTrigger {
        init {
            require(every >= MIN_INTERVAL) { "interval must be at least $MIN_INTERVAL" }
        }
    }

    /** Every day at [time]. */
    data class Daily(val time: LocalTime) : TimeTrigger

    /** On the selected weekdays at [time]. */
    data class Weekly(val days: Set<DayOfWeek>, val time: LocalTime) : TimeTrigger {
        init {
            require(days.isNotEmpty()) { "at least one weekday is required" }
        }
    }

    /**
     * Monthly on [dayOfMonth] (1..31) at [time]. If the month is shorter the last day of that month is used
     * (31st → Feb 28/29). [LAST_DAY] always means the last day of the month.
     */
    data class Monthly(val dayOfMonth: Int, val time: LocalTime) : TimeTrigger {
        init {
            require(dayOfMonth == LAST_DAY || dayOfMonth in 1..31) { "dayOfMonth must be 1..31 or LAST_DAY" }
        }
    }

    companion object {
        const val LAST_DAY = -1
        val MIN_INTERVAL: Duration = Duration.ofMinutes(1)
    }
}

/** Occurrences of a trigger in a time window. [latest] is the last one in the window, [count] how many there were. */
data class OccurrenceWindow(val count: Long, val latest: Instant?) {
    companion object {
        val EMPTY = OccurrenceWindow(0, null)
    }
}

object TriggerMath {

    /** Safety valve for pathological windows (e.g. a daily task after years of the phone being off). */
    private const val MAX_STEPS = 20_000

    /** First occurrence strictly after [after], or null if the trigger will never fire again. */
    fun next(trigger: TimeTrigger, zone: ZoneId, after: Instant): Instant? = when (trigger) {
        is TimeTrigger.Once -> trigger.at.resolve(zone).toInstant().takeIf { it.isAfter(after) }
        is TimeTrigger.Interval -> nextInterval(trigger, after)
        is TimeTrigger.Daily -> nextByDate(zone, after) { date -> date.at(trigger.time, zone) }
        is TimeTrigger.Weekly -> nextByDate(zone, after) { date ->
            if (date.dayOfWeek in trigger.days) date.at(trigger.time, zone) else null
        }
        is TimeTrigger.Monthly -> nextByDate(zone, after) { date ->
            if (date.dayOfMonth == monthlyDay(trigger, YearMonth.from(date))) date.at(trigger.time, zone) else null
        }
    }

    /** Occurrences in the half-open window (from, to]. */
    fun window(trigger: TimeTrigger, zone: ZoneId, from: Instant, to: Instant): OccurrenceWindow {
        if (!to.isAfter(from)) return OccurrenceWindow.EMPTY
        return when (trigger) {
            is TimeTrigger.Interval -> intervalWindow(trigger, from, to)
            else -> {
                var count = 0L
                var latest: Instant? = null
                var cursor = from
                var steps = 0
                while (steps++ < MAX_STEPS) {
                    val n = next(trigger, zone, cursor) ?: break
                    if (n.isAfter(to)) break
                    count++
                    latest = n
                    cursor = n
                }
                OccurrenceWindow(count, latest)
            }
        }
    }

    private fun LocalDateTime.resolve(zone: ZoneId): ZonedDateTime = ZonedDateTime.ofLocal(this, zone, null)

    private fun LocalDate.at(time: LocalTime, zone: ZoneId): Instant =
        LocalDateTime.of(this, time).resolve(zone).toInstant()

    private fun monthlyDay(trigger: TimeTrigger.Monthly, month: YearMonth): Int {
        val last = month.lengthOfMonth()
        return if (trigger.dayOfMonth == TimeTrigger.LAST_DAY) last else minOf(trigger.dayOfMonth, last)
    }

    /**
     * Walks local dates starting one day before [after]'s local date (an instant can map to different local dates
     * in different zones, and gap-shifted times can slip past midnight) and returns the first candidate after it.
     */
    private fun nextByDate(zone: ZoneId, after: Instant, candidate: (LocalDate) -> Instant?): Instant? {
        var date = after.atZone(zone).toLocalDate().minusDays(1)
        repeat(MAX_DAYS_SCAN) {
            val c = candidate(date)
            if (c != null && c.isAfter(after)) return c
            date = date.plusDays(1)
        }
        return null
    }

    private const val MAX_DAYS_SCAN = 800 // > 2 years: covers monthly-on-LAST and sparse weekly selections

    private fun nextInterval(t: TimeTrigger.Interval, after: Instant): Instant {
        val step = t.every.toMillis()
        val anchor = t.anchor.toEpochMilli()
        val now = after.toEpochMilli()
        if (now < anchor) return t.anchor
        val k = Math.floorDiv(now - anchor, step) + 1
        return Instant.ofEpochMilli(anchor + k * step)
    }

    private fun intervalWindow(t: TimeTrigger.Interval, from: Instant, to: Instant): OccurrenceWindow {
        val step = t.every.toMillis()
        val anchor = t.anchor.toEpochMilli()
        val lo = from.toEpochMilli()
        val hi = to.toEpochMilli()
        if (hi < anchor) return OccurrenceWindow.EMPTY
        // number of occurrences <= x is floor((x-anchor)/step)+1 for x >= anchor, else 0
        fun upTo(x: Long): Long = if (x < anchor) 0 else Math.floorDiv(x - anchor, step) + 1
        val count = upTo(hi) - upTo(lo)
        if (count <= 0) return OccurrenceWindow.EMPTY
        val latest = anchor + Math.floorDiv(hi - anchor, step) * step
        return OccurrenceWindow(count, Instant.ofEpochMilli(latest))
    }
}
