// 保护：落盘字段名写错，或坏文件被覆盖后，重发会换掉 day_key
package com.niki914.zafiro.business.remote.ledger

import com.niki914.logging.Backend
import com.niki914.logging.Level
import com.niki914.logging.Logger
import com.niki914.zafiro.api.telemetry.CustomValue
import com.niki914.zafiro.api.telemetry.TelemetryContract
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.io.path.createTempDirectory
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LedgerJsonTest {
    /** JVM 单测没有 Android 框架，默认的 LogcatBackend 会在业务代码打日志时抛异常。 */
    @BeforeTest
    fun silenceLogger() {
        Logger.install(object : Backend {
            override fun emit(level: Level, tag: String, msg: String, throwable: Throwable?) = Unit
        })
    }

    @Test
    fun `ledger json uses the schema file names`() {
        val today = LocalDate.parse("2026-10-26")
        val obj = JSONObject(
            LedgerJson.encode(
                LedgerState(
                    pages = listOf(
                        DayPage(
                            day = today,
                            dayKey = "6f1c2a4e-8d3b-4c1a-9e2f-0b7d5a3c9e11",
                            rev = TelemetryContract.LIMIT_REV_MIN,
                            ackedRev = 0,
                            versionCode = 16,
                            prevActiveDay = null,
                            prevVersionCode = null,
                            counts = emptyList(),
                            countsTruncated = TelemetryContract.LIMIT_COUNTS_TRUNCATED_MIN,
                            firsts = emptyList(),
                            custom = emptyMap(),
                        ),
                    ),
                ),
            ),
        )
        val page = obj.getJSONArray("pages").getJSONObject(0)
        assertEquals(TOP_KEYS, keys(obj))
        assertEquals(PAGE_KEYS, keys(page))
        assertEquals(SEND_KEYS, keys(obj.getJSONObject("send")))
    }

    @Test
    fun `a ledger file roundtrips through the store`() = runBlocking {
        val today = LocalDate.parse("2026-10-26")
        val earlier = LocalDate.parse("2026-10-20")
        val state = LedgerState(
            lastActive = LastActive(today, 16),
            pages = listOf(
                DayPage(
                    day = today,
                    dayKey = "6f1c2a4e-8d3b-4c1a-9e2f-0b7d5a3c9e11",
                    rev = 2,
                    ackedRev = 1,
                    versionCode = 16,
                    prevActiveDay = earlier,
                    prevVersionCode = 15,
                    counts = listOf(PageCount("tool_call", null, 4, mapOf("ok" to CustomValue.Bool(true)))),
                    countsTruncated = 2,
                    firsts = listOf("tool_call"),
                    custom = mapOf("k" to CustomValue.Str("v")),
                ),
            ),
            seenEvents = listOf("tool_call"),
            envelopeCustom = mapOf("n" to CustomValue.Num(3)),
            droppedDays = 4,
            send = LedgerSend(lastOkMs = 10L, nextRetryMs = 20L, failures = 2),
        )
        val directory = createTempDirectory("ledger").toFile()
        try {
            val store = FileLedgerStore(directory)
            store.save(state)
            assertEquals(LedgerLoad.Ready(state), store.load())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `an unreadable ledger file is left untouched`() = runBlocking {
        val directory = createTempDirectory("ledger").toFile()
        try {
            val file = File(directory, "ledger.json")
            file.writeText("{")
            val ledger = Ledger(
                Clock.fixed(Instant.parse("2026-10-26T00:00:00Z"), ZoneOffset.UTC),
                FileLedgerStore(directory),
            )
            ledger.markActive(16)
            ledger.snapshotForSend(LocalDate.parse("2026-10-26"))
            assertEquals("{", file.readText())
        } finally {
            directory.deleteRecursively()
        }
    }
}

private val TOP_KEYS = setOf(
    "v",
    "last_active",
    "pages",
    "seen_events",
    "envelope_custom",
    "dropped_days",
    "send",
)

private val PAGE_KEYS = setOf(
    "day",
    "day_key",
    "rev",
    "acked_rev",
    "version_code",
    "prev_active_day",
    "prev_version_code",
    "counts",
    "counts_truncated",
    "firsts",
    "custom",
)

private val SEND_KEYS = setOf("last_ok_ms", "next_retry_ms", "failures")

private fun keys(obj: JSONObject): Set<String> {
    val names = mutableSetOf<String>()
    val iterator = obj.keys()
    while (iterator.hasNext()) names.add(iterator.next())
    return names
}
