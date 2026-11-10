package com.tricreta.zafiro.autonomy.data

import com.tricreta.zafiro.autonomy.core.Budget
import com.tricreta.zafiro.autonomy.core.MisfirePolicy
import com.tricreta.zafiro.autonomy.core.OverlapPolicy
import com.tricreta.zafiro.autonomy.core.ZoneResolver
import com.tricreta.zafiro.autonomy.core.TaskSpec
import com.tricreta.zafiro.autonomy.core.TimeTrigger
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

object TriggerTypes {
    const val NONE = "NONE"
    const val ONCE = "ONCE"
    const val INTERVAL = "INTERVAL"
    const val DAILY = "DAILY"
    const val WEEKLY = "WEEKLY"
    const val MONTHLY = "MONTHLY"
}

/** Bit 0 = Monday … bit 6 = Sunday. */
fun daysToMask(days: Set<DayOfWeek>): Int = days.fold(0) { acc, d -> acc or (1 shl (d.value - 1)) }

fun maskToDays(mask: Int): Set<DayOfWeek> = DayOfWeek.entries.filter { mask and (1 shl (it.value - 1)) != 0 }.toSet()

val WEEKDAYS: Set<DayOfWeek> = setOf(
    DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
)

/** Null for manual-only tasks. Throws IllegalArgumentException on corrupt rows so bad data fails loudly in tests. */
fun TaskEntity.toTrigger(): TimeTrigger? = when (triggerType) {
    TriggerTypes.NONE -> null
    TriggerTypes.ONCE -> TimeTrigger.Once(LocalDateTime.parse(requireNotNull(onceAt) { "onceAt missing" }))
    TriggerTypes.INTERVAL -> TimeTrigger.Interval(
        Duration.ofMinutes(requireNotNull(intervalMinutes) { "intervalMinutes missing" }),
        Instant.ofEpochMilli(requireNotNull(anchorMs) { "anchorMs missing" }),
    )
    TriggerTypes.DAILY -> TimeTrigger.Daily(LocalTime.parse(requireNotNull(timeOfDay) { "timeOfDay missing" }))
    TriggerTypes.WEEKLY -> TimeTrigger.Weekly(maskToDays(daysMask), LocalTime.parse(requireNotNull(timeOfDay)))
    TriggerTypes.MONTHLY -> TimeTrigger.Monthly(dayOfMonth, LocalTime.parse(requireNotNull(timeOfDay)))
    else -> throw IllegalArgumentException("unknown trigger type $triggerType")
}

/** [deviceZone] is injected so tests can simulate a phone in any zone; production uses the device's current zone. */
fun TaskEntity.toSpec(deviceZone: ZoneId = ZoneId.systemDefault()): TaskSpec = TaskSpec(
    id = id,
    enabled = enabled,
    trigger = toTrigger(),
    zone = ZoneResolver.resolve(zoneId, deviceZone),
    createdAt = Instant.ofEpochMilli(createdAtMs),
    misfire = if (misfireGraceMs < 0) MisfirePolicy.Skip else MisfirePolicy.RunLate(Duration.ofMillis(misfireGraceMs)),
    overlap = OverlapPolicy.valueOf(overlap),
)

fun TaskEntity.toBudget(): Budget = Budget(
    maxSteps = maxSteps.takeIf { it >= 0 },
    maxDurationMs = maxDurationMs.takeIf { it >= 0 },
    // Token budgets are not enforced yet: the engine exposes context size, not cumulative usage (see ARCHITECTURE 2.5).
    maxTokens = null,
)

/** Human-readable schedule for lists. */
fun TaskEntity.describeSchedule(): String = when (triggerType) {
    TriggerTypes.NONE -> "Manual only"
    TriggerTypes.ONCE -> "Once at ${onceAt?.replace('T', ' ')}"
    TriggerTypes.INTERVAL -> "Every ${intervalMinutes} min"
    TriggerTypes.DAILY -> "Daily at $timeOfDay"
    TriggerTypes.WEEKLY -> if (maskToDays(daysMask) == WEEKDAYS) "Weekdays at $timeOfDay" else "Weekly at $timeOfDay"
    TriggerTypes.MONTHLY -> "Monthly (day ${if (dayOfMonth < 0) "last" else dayOfMonth.toString()}) at $timeOfDay"
    else -> triggerType
}
