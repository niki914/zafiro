package com.niki914.zafiro.business.remote.ledger

import com.niki914.logging.Logger
import com.niki914.zafiro.api.telemetry.CustomValue
import com.niki914.zafiro.api.telemetry.TelemetryContract
import com.niki914.zafiro.api.util.runCatchingCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * 账页状态机。
 *
 * 一天一页。`prev_*` 在开页时写入，之后不重算。
 * 删过账页的日期记在 `deleted_days`，还在删除记忆窗口内就不给这一天开新页。
 * 发送前先落盘。磁盘失败只记日志，内存停在上一份已经落盘的状态。
 */
internal class Ledger(
    private val clock: Clock,
    private val store: LedgerStore,
) {
    private val mutex = Mutex()
    private var state = LedgerState.empty()
    private var loaded = false
    private var writable = true
    private var inflightDroppedDays: Int? = null

    suspend fun markActive(versionCode: Int) {
        mutex.withLock {
            val current = ready() ?: return@withLock
            val next = reduceMark(current, today(), versionCode)
            if (next != current) persist(next)
        }
    }

    suspend fun record(
        event: String,
        label: String? = null,
        n: Int = 1,
        custom: Map<String, CustomValue> = emptyMap(),
        versionCode: Int,
    ) {
        mutex.withLock {
            val current = ready() ?: return@withLock
            val next = reduceRecord(current, today(), event, label, n, custom, versionCode)
            if (next != current) persist(next)
        }
    }

    suspend fun setDayCustom(key: String, value: CustomValue?, versionCode: Int) {
        mutex.withLock {
            val current = ready() ?: return@withLock
            val next = reduceDayCustom(current, today(), key, value, versionCode)
            if (next != current) persist(next)
        }
    }

    suspend fun setEnvelopeCustom(key: String, value: CustomValue?) {
        mutex.withLock {
            val current = ready() ?: return@withLock
            val next = if (value != null) {
                current.copy(envelopeCustom = current.envelopeCustom + (key to value))
            } else {
                current.copy(envelopeCustom = current.envelopeCustom - key)
            }
            if (next != current) persist(next)
        }
    }

    suspend fun snapshotForSend(today: LocalDate): SendSnapshot {
        return mutex.withLock {
            val current = ready() ?: return@withLock emptySnapshot()
            val pruned = prune(current, today)
            if (!persist(pruned)) return@withLock sendSnapshot(current)
            inflightDroppedDays = pruned.droppedDays
            sendSnapshot(pruned)
        }
    }

    suspend fun onAck(
        sent: Map<String, Int>,
        today: LocalDate,
        nowMs: Long? = null,
        nextRetryMs: Long? = null,
    ) {
        mutex.withLock {
            val current = ready() ?: return@withLock
            val inflight = inflightDroppedDays
            val acked = applyAck(current, sent, today, inflight)
            val withSend = if (nowMs != null && nextRetryMs != null) {
                acked.copy(send = LedgerSend(lastOkMs = nowMs, nextRetryMs = nextRetryMs, failures = 0))
            } else {
                acked
            }
            val pruned = prune(withSend, today)
            if (pruned == current) {
                if (inflight != null) inflightDroppedDays = null
                return@withLock
            }
            if (persist(pruned)) inflightDroppedDays = null
        }
    }

    suspend fun sendState(): LedgerSend? {
        return mutex.withLock { ready()?.send }
    }

    suspend fun recordSendSuccess(nowMs: Long, nextRetryMs: Long) {
        mutex.withLock {
            val current = ready() ?: return@withLock
            val next = current.copy(
                send = LedgerSend(
                    lastOkMs = nowMs,
                    nextRetryMs = nextRetryMs,
                    failures = 0,
                )
            )
            persist(next)
        }
    }

    suspend fun recordSendFailure(nextRetryMs: Long) {
        mutex.withLock {
            val current = ready() ?: return@withLock
            val next = current.copy(
                send = current.send.copy(
                    failures = current.send.failures + 1,
                    nextRetryMs = nextRetryMs,
                )
            )
            persist(next)
        }
    }

    suspend fun onDropped(dayKeys: Collection<String>) {
        mutex.withLock {
            val current = ready() ?: return@withLock
            val next = dropPages(current, dayKeys)
            if (next != current) persist(next)
        }
    }

    suspend fun clearForDisabled() {
        mutex.withLock {
            val current = ready() ?: return@withLock
            val next = clearPending(current)
            if (next != current) persist(next)
        }
    }

    private suspend fun ready(): LedgerState? {
        if (!loaded) {
            val result = runCatchingCancellable { store.load() }
            result.onFailure { error -> Logger.w(LOG_TAG, "账页读出失败", error) }
            if (result.isFailure) return null
            loaded = true
            when (val load = result.getOrThrow()) {
                is LedgerLoad.Ready -> {
                    state = load.state
                    val trimmed = trimDeleted(load.state, today())
                    if (trimmed != load.state) persist(trimmed)
                }
                LedgerLoad.Missing -> state = LedgerState.empty()
                LedgerLoad.Unreadable -> {
                    writable = false
                    Logger.w(LOG_TAG, "账页文件无法解析")
                }
            }
        }
        if (!writable) return null
        return state
    }

    private suspend fun persist(next: LedgerState): Boolean {
        val trimmed = trimDeleted(next, today())
        val saved = runCatchingCancellable { store.save(trimmed) }
        saved.onFailure { error -> Logger.w(LOG_TAG, "账页写入失败", error) }
        if (saved.isFailure) return false
        state = trimmed
        return true
    }

    private fun today(): LocalDate = clock.instant().atOffset(ZoneOffset.UTC).toLocalDate()

    private companion object {
        const val LOG_TAG = "niki914_zafiro_Ledger"
    }
}

private fun emptySnapshot(): SendSnapshot {
    return SendSnapshot(
        days = emptyList(),
        droppedDays = TelemetryContract.LIMIT_DROPPED_DAYS_MIN,
        envelopeCustom = emptyMap(),
    )
}

private fun sendSnapshot(state: LedgerState): SendSnapshot {
    return SendSnapshot(
        days = state.pages.filter { it.rev > it.ackedRev }.sortedBy { it.day },
        droppedDays = state.droppedDays,
        envelopeCustom = state.envelopeCustom,
    )
}

private fun reduceMark(state: LedgerState, day: LocalDate, versionCode: Int): LedgerState {
    if (versionCode < TelemetryContract.LIMIT_VERSION_CODE_MIN) return state
    val last = state.lastActive ?: return openPage(prune(state, day), day, versionCode, prev = null)
    if (day > last.day) return openPage(prune(state, day), day, versionCode, prev = last)
    val daysBack = last.day.toEpochDay() - day.toEpochDay()
    if (daysBack == 0L) {
        val pruned = prune(state, day)
        val page = pruned.pages.find { it.day == day }
        if (page == null) return pruned
        return touchPage(pruned, page, versionCode)
    }
    if (daysBack <= TelemetryContract.LIMIT_CLOCK_ROLLBACK_DAYS.toLong()) return state
    return rollBack(state, day, versionCode)
}

/** 回拨超过契约天数：清空最近活跃日，删掉这一天之后和保留期之外的页。已有账页则复用；删除记忆里有这一天则不记。 */
private fun rollBack(state: LedgerState, day: LocalDate, versionCode: Int): LedgerState {
    val base = trimDeleted(state, day)
    val oldest = day.minusDays(TelemetryContract.LIMIT_CLIENT_RETENTION_PAST_DAYS.toLong())
    var dropped = base.droppedDays
    val removed = ArrayList<LocalDate>()
    val kept = base.pages.filter { page ->
        val remove = page.day > day || page.day < oldest
        if (!remove) return@filter true
        if (page.ackedRev != page.rev) dropped += 1
        removed.add(page.day)
        false
    }
    val cleared = base.copy(
        lastActive = null,
        pages = kept,
        droppedDays = dropped,
        deletedDays = rememberDeleted(base.deletedDays, removed),
    )
    val existing = kept.find { it.day == day }
    if (existing != null) {
        val restored = cleared.copy(lastActive = LastActive(existing.day, existing.versionCode))
        return touchPage(restored, existing, versionCode)
    }
    if (day in cleared.deletedDays) return cleared
    return openPage(cleared, day, versionCode, prev = null)
}

private fun touchPage(state: LedgerState, page: DayPage, versionCode: Int): LedgerState {
    if (page.versionCode == versionCode) return state
    val updated = page.copy(versionCode = versionCode, rev = page.rev + 1)
    return state.copy(
        pages = state.pages.map { if (it.dayKey == page.dayKey) updated else it },
        lastActive = LastActive(page.day, versionCode),
    )
}

private fun openPage(
    state: LedgerState,
    day: LocalDate,
    versionCode: Int,
    prev: LastActive?,
): LedgerState {
    if (day in state.deletedDays) return state
    val page = DayPage(
        day = day,
        dayKey = UUID.randomUUID().toString(),
        rev = TelemetryContract.LIMIT_REV_MIN,
        ackedRev = 0,
        versionCode = versionCode,
        prevActiveDay = prev?.day,
        prevVersionCode = prev?.versionCode,
        counts = emptyList(),
        countsTruncated = TelemetryContract.LIMIT_COUNTS_TRUNCATED_MIN,
        firsts = emptyList(),
        custom = emptyMap(),
    )
    return state.copy(
        pages = state.pages + page,
        lastActive = LastActive(day, versionCode),
    )
}

private fun reduceRecord(
    state: LedgerState,
    day: LocalDate,
    event: String,
    label: String?,
    n: Int,
    custom: Map<String, CustomValue>,
    versionCode: Int,
): LedgerState {
    if (n < TelemetryContract.LIMIT_COUNT_N_MIN) return state
    val marked = reduceMark(state, day, versionCode)
    val page = marked.pages.find { it.day == day } ?: return marked

    if (event.startsWith("first_")) {
        if (event in marked.seenEvents) return marked
        val updated = page.copy(firsts = page.firsts + event, rev = page.rev + 1)
        return marked.copy(
            pages = marked.pages.map { if (it.dayKey == page.dayKey) updated else it },
            seenEvents = marked.seenEvents + event,
        )
    }

    val existingIndex = page.counts.indexOfFirst { it.event == event && it.label == label }
    val updatedCounts: List<PageCount>
    var truncated = page.countsTruncated

    if (existingIndex >= 0) {
        val existing = page.counts[existingIndex]
        val newN = (existing.n + n).coerceAtMost(TelemetryContract.LIMIT_COUNT_N_MAX)
        val mergedCustom = if (custom.isEmpty()) existing.custom else existing.custom + custom
        val updatedCount = existing.copy(n = newN, custom = mergedCustom)
        updatedCounts = page.counts.toMutableList().apply { set(existingIndex, updatedCount) }
    } else {
        if (page.counts.size < TelemetryContract.LIMIT_COUNTS_PER_DAY) {
            updatedCounts = page.counts + PageCount(
                event = event,
                label = label,
                n = n.coerceAtMost(TelemetryContract.LIMIT_COUNT_N_MAX),
                custom = custom,
            )
        } else {
            updatedCounts = page.counts
            truncated += 1
        }
    }

    val updated = page.copy(
        counts = updatedCounts,
        countsTruncated = truncated,
        rev = page.rev + 1,
    )
    return marked.copy(
        pages = marked.pages.map { if (it.dayKey == page.dayKey) updated else it },
    )
}

private fun reduceDayCustom(
    state: LedgerState,
    day: LocalDate,
    key: String,
    value: CustomValue?,
    versionCode: Int,
): LedgerState {
    val marked = reduceMark(state, day, versionCode)
    val page = marked.pages.find { it.day == day } ?: return marked
    val existing = page.custom[key]
    if (key == "user_cohort" && existing is CustomValue.Str && existing.value.startsWith("first_") && value is CustomValue.Str && !value.value.startsWith("first_")) {
        return marked
    }
    val nextCustom = if (value != null) page.custom + (key to value) else page.custom - key
    if (nextCustom == page.custom) return marked
    val updated = page.copy(custom = nextCustom, rev = page.rev + 1)
    return marked.copy(
        pages = marked.pages.map { if (it.dayKey == page.dayKey) updated else it },
    )
}

private fun prune(state: LedgerState, today: LocalDate): LedgerState {
    val base = trimDeleted(state, today)
    val oldest = today.minusDays(TelemetryContract.LIMIT_CLIENT_RETENTION_PAST_DAYS.toLong())
    var dropped = base.droppedDays
    val removed = ArrayList<LocalDate>()
    val kept = base.pages.filter { page ->
        if (page.day >= oldest) return@filter true
        if (page.ackedRev != page.rev) dropped += 1
        removed.add(page.day)
        false
    }
    if (removed.isEmpty()) return base
    return base.copy(
        pages = kept,
        droppedDays = dropped,
        deletedDays = rememberDeleted(base.deletedDays, removed),
    )
}

private fun applyAck(
    state: LedgerState,
    sent: Map<String, Int>,
    today: LocalDate,
    inflightDroppedDays: Int?,
): LedgerState {
    var dropped = state.droppedDays
    if (inflightDroppedDays != null) {
        dropped = (dropped - inflightDroppedDays).coerceAtLeast(TelemetryContract.LIMIT_DROPPED_DAYS_MIN)
    }
    val removed = ArrayList<LocalDate>()
    val kept = state.pages.mapNotNull { page ->
        val sentRev = sent[page.dayKey] ?: return@mapNotNull page
        if (page.day < today && sentRev == page.rev) {
            removed.add(page.day)
            return@mapNotNull null
        }
        page.copy(ackedRev = sentRev)
    }
    return state.copy(
        pages = kept,
        droppedDays = dropped,
        deletedDays = rememberDeleted(state.deletedDays, removed),
    )
}

private fun dropPages(state: LedgerState, dayKeys: Collection<String>): LedgerState {
    if (dayKeys.isEmpty()) return state
    val drop = dayKeys.toSet()
    var dropped = state.droppedDays
    val removed = ArrayList<LocalDate>()
    val kept = state.pages.filter { page ->
        if (page.dayKey !in drop) return@filter true
        if (page.ackedRev != page.rev) dropped += 1
        removed.add(page.day)
        false
    }
    if (removed.isEmpty()) return state
    return state.copy(
        pages = kept,
        droppedDays = dropped,
        deletedDays = rememberDeleted(state.deletedDays, removed),
    )
}

/**
 * 停用时清掉还没确认的页，并把这些页计入 `dropped_days`。
 * `last_active` 保留，所以停用当天不会再开一页。
 */
private fun clearPending(state: LedgerState): LedgerState {
    var dropped = state.droppedDays
    val removed = ArrayList<LocalDate>()
    val kept = state.pages.filter { page ->
        if (page.ackedRev == page.rev) return@filter true
        dropped += 1
        removed.add(page.day)
        false
    }
    if (removed.isEmpty()) return state
    return state.copy(
        pages = kept,
        droppedDays = dropped,
        deletedDays = rememberDeleted(state.deletedDays, removed),
    )
}

private fun trimDeleted(state: LedgerState, today: LocalDate): LedgerState {
    val oldest = today.minusDays(TelemetryContract.LIMIT_DELETED_DAY_MEMORY_DAYS.toLong())
    val kept = state.deletedDays.filter { it >= oldest }
    if (kept.size == state.deletedDays.size) return state
    return state.copy(deletedDays = kept)
}

private fun rememberDeleted(existing: List<LocalDate>, removed: List<LocalDate>): List<LocalDate> {
    if (removed.isEmpty()) return existing
    val seen = HashSet<LocalDate>(existing.size + removed.size)
    seen.addAll(existing)
    val added = ArrayList<LocalDate>()
    for (day in removed) {
        if (seen.add(day)) added.add(day)
    }
    if (added.isEmpty()) return existing
    return existing + added
}
