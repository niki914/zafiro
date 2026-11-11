package com.tricreta.zafiro.autonomy.form

import com.tricreta.zafiro.autonomy.core.OnceCheck
import com.tricreta.zafiro.autonomy.core.OnceTimeCheck
import com.tricreta.zafiro.autonomy.data.TaskEntity
import com.tricreta.zafiro.autonomy.data.TriggerTypes
import com.tricreta.zafiro.autonomy.data.WEEKDAYS
import com.tricreta.zafiro.autonomy.data.daysToMask
import com.tricreta.zafiro.autonomy.data.maskToDays
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

enum class ScheduleKind { MANUAL, ONCE, DAILY, WEEKDAYS, WEEKLY, MONTHLY, INTERVAL }

enum class DraftField { NAME, PROMPT, TIME, INTERVAL, STEPS, MINUTES, TOKENS, RECOVERIES, DAYS, DAY_OF_MONTH }

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
    /** For WEEKLY: the chosen days. */
    val days: Set<DayOfWeek> = WEEKDAYS,
    /** For MONTHLY: 1..31, or "last". Short months clamp (31 runs on the 30th, or 28th/29th in February). */
    val dayOfMonth: String = "1",
    /** Thousands of billable tokens a run may use; blank = unlimited. */
    val maxTokensK: String = "300",
    /** Extra turns allowed after the first (failures continued, unmet success criteria retried). */
    val recoveries: String = "2",
    /** Plain-language definition of done; verified by the agent before a run counts as a success. Blank = no check. */
    val successCriteria: String = "",
    /** Saved model configuration to use; blank = whichever is active. */
    val configId: String = "",
    /** Agent profile that performs the task; blank = a plain run. */
    val agentId: String = "",
    /** After the run the agent saves lessons to memory. */
    val learn: Boolean = true,
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
        val tokensK: Long = if (maxTokensK.isBlank()) {
            -1L
        } else {
            maxTokensK.trim().toLongOrNull()?.takeIf { it in 10..10_000 }
                ?: return DraftResult.Invalid(DraftField.TOKENS, "Token limit must be 10 to 10000 (thousands), or empty for no limit.")
        }
        val recoveryCount = recoveries.trim().toIntOrNull()
        if (recoveryCount == null || recoveryCount !in 0..5) return DraftResult.Invalid(DraftField.RECOVERIES, "Recovery attempts must be a number from 0 to 5.")
        var monthDay = 1

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
            ScheduleKind.WEEKLY -> {
                if (days.isEmpty()) return DraftResult.Invalid(DraftField.DAYS, "Choose at least one day.")
                type = TriggerTypes.WEEKLY
                tod = time.toString()
                mask = daysToMask(days)
            }
            ScheduleKind.MONTHLY -> {
                val text = dayOfMonth.trim().lowercase()
                monthDay = if (text == "last") {
                    -1
                } else {
                    text.toIntOrNull()?.takeIf { it in 1..31 }
                        ?: return DraftResult.Invalid(DraftField.DAY_OF_MONTH, "Day of month must be 1 to 31, or \"last\".")
                }
                type = TriggerTypes.MONTHLY
                tod = time.toString()
            }
        }
        return DraftResult.Ok(
            TaskEntity(
                id = id, name = name.trim(), prompt = prompt.trim(), enabled = enabled,
                triggerType = type, onceAt = onceAt, intervalMinutes = interval, anchorMs = anchor, timeOfDay = tod,
                daysMask = mask, dayOfMonth = monthDay,
                zoneId = "", // follow the device's time zone
                createdAtMs = createdAtMs ?: nowMs, updatedAtMs = nowMs,
                misfireGraceMs = Duration.ofHours(2).toMillis(), overlap = "SKIP",
                maxSteps = steps, maxDurationMs = minutes * 60_000L, configId = configId,
                approvalTimeoutMs = Duration.ofMinutes(10).toMillis(),
                maxTokens = if (tokensK < 0) -1L else tokensK * 1_000L,
                successCriteria = successCriteria.trim(),
                recoveries = recoveryCount,
                agentId = agentId,
                learn = learn,
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
                    TriggerTypes.WEEKLY -> if (maskToDays(t.daysMask) == WEEKDAYS) ScheduleKind.WEEKDAYS else ScheduleKind.WEEKLY
                    TriggerTypes.MONTHLY -> ScheduleKind.MONTHLY
                    else -> ScheduleKind.MANUAL
                },
                days = maskToDays(t.daysMask).ifEmpty { WEEKDAYS },
                dayOfMonth = if (t.dayOfMonth < 0) "last" else t.dayOfMonth.toString(),
                maxTokensK = if (t.maxTokens < 0) "" else (t.maxTokens / 1_000L).coerceAtLeast(10).toString(),
                recoveries = t.recoveries.toString(),
                successCriteria = t.successCriteria,
                configId = t.configId,
                agentId = t.agentId,
                learn = t.learn,
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
