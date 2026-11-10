package com.tricreta.zafiro.autonomy.form

import com.tricreta.zafiro.autonomy.core.OnceCheck
import com.tricreta.zafiro.autonomy.core.OnceTimeCheck
import com.tricreta.zafiro.autonomy.data.TaskEntity
import com.tricreta.zafiro.autonomy.data.TriggerTypes
import com.tricreta.zafiro.autonomy.data.WEEKDAYS
import com.tricreta.zafiro.autonomy.data.daysToMask
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

enum class ScheduleKind { MANUAL, ONCE, DAILY, WEEKDAYS, INTERVAL }

enum class DraftField { NAME, PROMPT, TIME, INTERVAL, STEPS, MINUTES }

sealed interface DraftResult {
    data class Ok(val task: TaskEntity) : DraftResult
    data class Invalid(val field: DraftField, val message: String) : DraftResult
}

/**
 * Editable state of a task, independent of any UI toolkit (so it is unit-tested on the JVM). Date and time are real calendar
 * values chosen with pickers, never parsed from text: that removes the 12h/24h and format ambiguity that made valid future
 * times look "already passed".
 */
data class TaskDraft(
    val name: String = "",
    val prompt: String = "",
    val kind: ScheduleKind = ScheduleKind.DAILY,
    val date: LocalDate,
    val time: LocalTime = LocalTime.of(8, 0),
    val everyMinutes: String = "60",
    val maxSteps: String = "30",
    val maxMinutes: String = "10",
) {

    /**
     * @param deviceZone the zone the phone is in RIGHT NOW. Tasks store a blank zone = "follow the device", so a task keeps
     * meaning "08:00 where I am" and is re-evaluated automatically when the phone's time zone changes.
     */
    fun toEntity(
        id: String,
        createdAtMs: Long?,
        nowMs: Long,
        deviceZone: ZoneId,
        enabled: Boolean = true,
    ): DraftResult {
        if (name.isBlank()) return DraftResult.Invalid(DraftField.NAME, "Give the task a name.")
        if (prompt.isBlank()) return DraftResult.Invalid(DraftField.PROMPT, "Describe what Zafiro should do.")
        val steps = maxSteps.trim().toIntOrNull()
        if (steps == null || steps !in 1..500) return DraftResult.Invalid(DraftField.STEPS, "Max tool calls must be a number from 1 to 500.")
        val minutes = maxMinutes.trim().toLongOrNull()
        if (minutes == null || minutes !in 1..240) return DraftResult.Invalid(DraftField.MINUTES, "Max minutes must be a number from 1 to 240.")

        var type = TriggerTypes.NONE
        var onceAt: String? = null
        var interval: Long? = null
        var anchor: Long? = null
        var tod: String? = null
        var mask = 0
        when (kind) {
            ScheduleKind.MANUAL -> Unit
            ScheduleKind.ONCE -> {
                when (val c = OnceTimeCheck.check(date, time, deviceZone, Instant.ofEpochMilli(nowMs))) {
                    is OnceCheck.InPast -> return DraftResult.Invalid(DraftField.TIME, OnceTimeCheck.explain(c))
                    is OnceCheck.Accepted -> Unit
                }
                type = TriggerTypes.ONCE
                onceAt = LocalDateTime.of(date, time).toString()
            }
            ScheduleKind.INTERVAL -> {
                val every = everyMinutes.trim().toLongOrNull()
                if (every == null || every < 1) return DraftResult.Invalid(DraftField.INTERVAL, "Repeat interval must be at least 1 minute.")
                type = TriggerTypes.INTERVAL
                interval = every
                anchor = nowMs
            }
            ScheduleKind.DAILY -> {
                type = TriggerTypes.DAILY
                tod = time.toString()
            }
            ScheduleKind.WEEKDAYS -> {
                type = TriggerTypes.WEEKLY
                tod = time.toString()
                mask = daysToMask(WEEKDAYS)
            }
        }
        return DraftResult.Ok(
            TaskEntity(
                id = id, name = name.trim(), prompt = prompt.trim(), enabled = enabled,
                triggerType = type, onceAt = onceAt, intervalMinutes = interval, anchorMs = anchor, timeOfDay = tod,
                daysMask = mask, dayOfMonth = 1,
                zoneId = "", // follow the device's time zone
                createdAtMs = createdAtMs ?: nowMs, updatedAtMs = nowMs,
                misfireGraceMs = Duration.ofHours(2).toMillis(), overlap = "SKIP",
                maxSteps = steps, maxDurationMs = minutes * 60_000L, configId = "",
                approvalTimeoutMs = Duration.ofMinutes(10).toMillis(),
            ),
        )
    }

    companion object {
        fun new(today: LocalDate): TaskDraft = TaskDraft(date = today)

        fun from(t: TaskEntity, today: LocalDate): TaskDraft {
            val onceDateTime = t.onceAt?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
            return TaskDraft(
                name = t.name, prompt = t.prompt,
                kind = when (t.triggerType) {
                    TriggerTypes.ONCE -> ScheduleKind.ONCE
                    TriggerTypes.INTERVAL -> ScheduleKind.INTERVAL
                    TriggerTypes.DAILY -> ScheduleKind.DAILY
                    TriggerTypes.WEEKLY -> ScheduleKind.WEEKDAYS
                    else -> ScheduleKind.MANUAL
                },
                date = onceDateTime?.toLocalDate() ?: today,
                time = onceDateTime?.toLocalTime()
                    ?: t.timeOfDay?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
                    ?: LocalTime.of(8, 0),
                everyMinutes = (t.intervalMinutes ?: 60).toString(),
                maxSteps = t.maxSteps.coerceAtLeast(1).toString(),
                maxMinutes = (t.maxDurationMs / 60_000L).coerceAtLeast(1).toString(),
            )
        }

        /** A one-minute-ahead task that only posts a notification: proves alarm → service → agent → notify end to end. */
        fun selfTest(nowMs: Long, deviceZone: ZoneId): TaskEntity {
            val at = Instant.ofEpochMilli(nowMs).atZone(deviceZone).toLocalDateTime().plusMinutes(1).withSecond(0).withNano(0)
            return TaskEntity(
                id = UUID.randomUUID().toString(), name = "Self-test", enabled = true,
                prompt = "Use the notify tool to send a notification titled 'Zafiro self-test' with the text 'Hello from your scheduled task'. Then reply with one short sentence.",
                triggerType = TriggerTypes.ONCE, onceAt = at.toString(), intervalMinutes = null, anchorMs = null, timeOfDay = null,
                daysMask = 0, dayOfMonth = 1, zoneId = "", createdAtMs = nowMs, updatedAtMs = nowMs,
                misfireGraceMs = Duration.ofHours(2).toMillis(), overlap = "SKIP",
                maxSteps = 5, maxDurationMs = Duration.ofMinutes(3).toMillis(), configId = "",
                approvalTimeoutMs = Duration.ofMinutes(5).toMillis(),
            )
        }
    }
}
