package com.tricreta.zafiro.autonomy.ui

import com.tricreta.zafiro.autonomy.data.TaskEntity
import com.tricreta.zafiro.autonomy.data.TriggerTypes
import com.tricreta.zafiro.autonomy.data.WEEKDAYS
import com.tricreta.zafiro.autonomy.data.daysToMask
import java.time.DateTimeException
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

enum class ScheduleKind(val label: String) {
    MANUAL("Manual"), ONCE("Once"), INTERVAL("Every N min"), DAILY("Daily"), WEEKDAYS("Weekdays"),
}

/** Plain-text form state with validation; kept separate from Compose so it is unit-testable. */
data class TaskForm(
    val name: String = "",
    val prompt: String = "",
    val kind: ScheduleKind = ScheduleKind.DAILY,
    val date: String = LocalDate.now().toString(),
    val time: String = "08:00",
    val everyMinutes: String = "60",
    val zone: String = ZoneId.systemDefault().id,
    val maxSteps: String = "30",
    val maxMinutes: String = "10",
) {

    fun toEntity(id: String, createdAtMs: Long? = null, nowMs: Long = System.currentTimeMillis()): Result<TaskEntity> = runCatching {
        require(name.isNotBlank()) { "Give the task a name." }
        require(prompt.isNotBlank()) { "Describe what Zafiro should do." }
        val zoneId = try {
            ZoneId.of(zone.trim())
        } catch (e: DateTimeException) {
            throw IllegalArgumentException("Unknown time zone '$zone'. Example: Africa/Nairobi")
        }
        val steps = maxSteps.trim().toIntOrNull()?.also { require(it in 1..500) { "Max tool calls must be 1-500." } }
            ?: throw IllegalArgumentException("Max tool calls must be a number.")
        val minutes = maxMinutes.trim().toLongOrNull()?.also { require(it in 1..240) { "Max minutes must be 1-240." } }
            ?: throw IllegalArgumentException("Max minutes must be a number.")

        var type = TriggerTypes.NONE
        var onceAt: String? = null
        var interval: Long? = null
        var anchor: Long? = null
        var tod: String? = null
        var mask = 0
        when (kind) {
            ScheduleKind.MANUAL -> Unit
            ScheduleKind.ONCE -> {
                type = TriggerTypes.ONCE
                val at = LocalDateTime.of(parseDate(date), parseTime(time))
                require(at.atZone(zoneId).toInstant().toEpochMilli() > nowMs) { "That time is already in the past." }
                onceAt = at.toString()
            }
            ScheduleKind.INTERVAL -> {
                type = TriggerTypes.INTERVAL
                interval = everyMinutes.trim().toLongOrNull()?.also { require(it >= 1) { "Interval must be at least 1 minute." } }
                    ?: throw IllegalArgumentException("Interval must be a number of minutes.")
                anchor = nowMs
            }
            ScheduleKind.DAILY -> {
                type = TriggerTypes.DAILY
                tod = parseTime(time).toString()
            }
            ScheduleKind.WEEKDAYS -> {
                type = TriggerTypes.WEEKLY
                tod = parseTime(time).toString()
                mask = daysToMask(WEEKDAYS)
            }
        }
        TaskEntity(
            id = id, name = name.trim(), prompt = prompt.trim(), enabled = true,
            triggerType = type, onceAt = onceAt, intervalMinutes = interval, anchorMs = anchor, timeOfDay = tod,
            daysMask = mask, dayOfMonth = 1, zoneId = zoneId.id,
            createdAtMs = createdAtMs ?: nowMs, updatedAtMs = nowMs,
            misfireGraceMs = Duration.ofHours(2).toMillis(), overlap = "SKIP",
            maxSteps = steps, maxDurationMs = minutes * 60_000L, configId = "",
            approvalTimeoutMs = Duration.ofMinutes(10).toMillis(),
        )
    }

    companion object {
        private val TIME = Regex("""^\s*(\d{1,2}):(\d{2})\s*$""")

        fun parseTime(text: String): LocalTime {
            val m = TIME.matchEntire(text) ?: throw IllegalArgumentException("Time must look like 08:00.")
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            require(h in 0..23 && min in 0..59) { "Time must be between 00:00 and 23:59." }
            return LocalTime.of(h, min)
        }

        fun parseDate(text: String): LocalDate = try {
            LocalDate.parse(text.trim())
        } catch (e: DateTimeException) {
            throw IllegalArgumentException("Date must look like 2026-11-10.")
        }

        fun from(t: TaskEntity): TaskForm = TaskForm(
            name = t.name, prompt = t.prompt,
            kind = when (t.triggerType) {
                TriggerTypes.ONCE -> ScheduleKind.ONCE
                TriggerTypes.INTERVAL -> ScheduleKind.INTERVAL
                TriggerTypes.DAILY -> ScheduleKind.DAILY
                TriggerTypes.WEEKLY -> ScheduleKind.WEEKDAYS
                else -> ScheduleKind.MANUAL
            },
            date = t.onceAt?.substringBefore('T') ?: LocalDate.now().toString(),
            time = t.onceAt?.substringAfter('T')?.take(5) ?: t.timeOfDay ?: "08:00",
            everyMinutes = (t.intervalMinutes ?: 60).toString(),
            zone = t.zoneId,
            maxSteps = t.maxSteps.coerceAtLeast(1).toString(),
            maxMinutes = (t.maxDurationMs / 60_000L).coerceAtLeast(1).toString(),
        )

        /** A one-minute-ahead task that only posts a notification: proves alarm → service → agent → notify end to end. */
        fun selfTest(nowMs: Long = System.currentTimeMillis()): TaskEntity {
            val zone = ZoneId.systemDefault()
            val at = java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDateTime().plusMinutes(1).withSecond(0).withNano(0)
            return TaskEntity(
                id = UUID.randomUUID().toString(), name = "Self-test", enabled = true,
                prompt = "Use the notify tool to send a notification titled 'Zafiro self-test' with the text 'Hello from your scheduled task'. Then reply with one short sentence.",
                triggerType = TriggerTypes.ONCE, onceAt = at.toString(), intervalMinutes = null, anchorMs = null, timeOfDay = null,
                daysMask = 0, dayOfMonth = 1, zoneId = zone.id, createdAtMs = nowMs, updatedAtMs = nowMs,
                misfireGraceMs = Duration.ofHours(2).toMillis(), overlap = "SKIP",
                maxSteps = 5, maxDurationMs = Duration.ofMinutes(3).toMillis(), configId = "",
                approvalTimeoutMs = Duration.ofMinutes(5).toMillis(),
            )
        }
    }
}
