package com.tricreta.zafiro.autonomy.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Result of validating a one-shot schedule time. */
sealed interface OnceCheck {
    data class Accepted(val at: ZonedDateTime) : OnceCheck

    /**
     * @property suggestion set when the chosen time looks like an AM/PM slip: e.g. it is 18:48, "06:53" was chosen, and 18:53 is
     * the next occurrence of those digits.
     */
    data class InPast(val chosen: ZonedDateTime, val now: ZonedDateTime, val suggestion: ZonedDateTime?) : OnceCheck
}

object OnceTimeCheck {

    /**
     * Validates [date] + [time] interpreted in [zone] against [now]. The ONLY inputs are real calendar values (from pickers) and
     * the zone the task will actually run in, so there is no string parsing and no hidden UTC conversion: both sides of the
     * comparison are instants computed from the same zone.
     */
    fun check(date: LocalDate, time: LocalTime, zone: ZoneId, now: Instant): OnceCheck {
        // DST gap → first valid instant after the gap; overlap → earlier offset (same rules the scheduler uses).
        val chosen = ZonedDateTime.ofLocal(LocalDateTime.of(date, time), zone, null)
        val nowZoned = now.atZone(zone)
        if (chosen.toInstant().isAfter(now)) return OnceCheck.Accepted(chosen)

        // "6:53" typed at 6:48 PM means 06:53 (morning). If +12h is still ahead, say so.
        val plus12 = chosen.plusHours(12)
        val looksLikeAmPmSlip = chosen.hour < 12 && plus12.toInstant().isAfter(now) && plus12.toLocalDate() == chosen.toLocalDate()
        return OnceCheck.InPast(chosen, nowZoned, plus12.takeIf { looksLikeAmPmSlip })
    }

    private val fmt: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm (zzz)", Locale.ENGLISH)

    /** Message that shows both sides of the comparison, so any mismatch is visible instead of mysterious. */
    fun explain(past: OnceCheck.InPast): String = buildString {
        append("${past.chosen.format(fmt)} has already passed; it is now ${past.now.format(fmt)}.")
        past.suggestion?.let { append(" Did you mean ${it.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH))}? (the picker uses the phone's clock)") }
    }
}

object ZoneResolver {
    /** A blank stored zone means "follow the device", so travelling or changing the phone's zone moves the task with it. */
    fun resolve(stored: String, deviceZone: ZoneId): ZoneId = if (stored.isBlank()) deviceZone else ZoneId.of(stored)
}
