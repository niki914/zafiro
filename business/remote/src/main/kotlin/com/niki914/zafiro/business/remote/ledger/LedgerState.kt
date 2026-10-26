package com.niki914.zafiro.business.remote.ledger

import com.niki914.zafiro.api.telemetry.CustomValue
import java.time.LocalDate

/** `ledger.json` 的 `v`。schema §8 的文件格式版本。 */
internal const val LEDGER_FILE_VERSION = 1

internal data class LastActive(
    val day: LocalDate,
    val versionCode: Int,
)

/** 账页上的一条计数。本期打开的新页没有计数。 */
internal data class PageCount(
    val event: String,
    val label: String?,
    val n: Int,
    val custom: Map<String, CustomValue>,
)

/**
 * 本地账页。`day` 落盘时用这个名字；上报时对应 `activity_day`。
 * `ackedRev` 为 0 表示这页还没有确认过。
 */
internal data class DayPage(
    val day: LocalDate,
    val dayKey: String,
    val rev: Int,
    val ackedRev: Int,
    val versionCode: Int,
    val prevActiveDay: LocalDate?,
    val prevVersionCode: Int?,
    val counts: List<PageCount>,
    val countsTruncated: Int,
    val firsts: List<String>,
    val custom: Map<String, CustomValue>,
)

/** `ledger.json` 的 `send`。发送节流由下一期写入，这里只原样保留。 */
internal data class LedgerSend(
    val lastOkMs: Long?,
    val nextRetryMs: Long,
    val failures: Int,
)

internal data class LedgerState(
    val v: Int = LEDGER_FILE_VERSION,
    val lastActive: LastActive? = null,
    val pages: List<DayPage> = emptyList(),
    /** 删过账页的 UTC 日。缺省为空；超过删除记忆窗口的日期在账本读写时去掉。 */
    val deletedDays: List<LocalDate> = emptyList(),
    val seenEvents: List<String> = emptyList(),
    val envelopeCustom: Map<String, CustomValue> = emptyMap(),
    val droppedDays: Int = 0,
    val send: LedgerSend = LedgerSend(lastOkMs = null, nextRetryMs = 0L, failures = 0),
) {
    companion object {
        fun empty(): LedgerState = LedgerState()
    }
}

/** 本次可发送的账页：`rev` 大于 `ackedRev`，按 `activity_day` 升序。 */
internal data class SendSnapshot(
    val days: List<DayPage>,
    val droppedDays: Int,
    val envelopeCustom: Map<String, CustomValue>,
)
