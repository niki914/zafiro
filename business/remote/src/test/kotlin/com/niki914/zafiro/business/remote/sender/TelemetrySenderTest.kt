// githooks:ignore-file file-token-limit
package com.niki914.zafiro.business.remote.sender

import com.niki914.zafiro.api.telemetry.CustomValue
import com.niki914.zafiro.api.telemetry.TelemetryContract
import com.niki914.zafiro.business.remote.config.RemoteConfig
import com.niki914.zafiro.business.remote.config.RemoteConfigClient
import com.niki914.zafiro.business.remote.config.RemoteConfigStore
import com.niki914.zafiro.business.remote.ledger.DayPage
import com.niki914.zafiro.business.remote.ledger.InstallFacts
import com.niki914.zafiro.business.remote.ledger.Ledger
import com.niki914.zafiro.business.remote.ledger.LedgerLoad
import com.niki914.zafiro.business.remote.ledger.LedgerSend
import com.niki914.zafiro.business.remote.ledger.LedgerState
import com.niki914.zafiro.business.remote.ledger.LedgerStore
import com.niki914.zafiro.business.remote.ledger.PageCount
import com.niki914.zafiro.business.remote.profile.DeviceSignals
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import java.util.TimeZone

class TelemetrySenderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `backoff progression doubles up to 24h ceiling`() {
        assertEquals(3600_000L, computeBackoffMillis(1))
        assertEquals(2 * 3600_000L, computeBackoffMillis(2))
        assertEquals(4 * 3600_000L, computeBackoffMillis(3))
        assertEquals(8 * 3600_000L, computeBackoffMillis(4))
        assertEquals(16 * 3600_000L, computeBackoffMillis(5))
        assertEquals(24 * 3600_000L, computeBackoffMillis(6))
        assertEquals(24 * 3600_000L, computeBackoffMillis(7))
        assertEquals(24 * 3600_000L, computeBackoffMillis(20))
        // Capped by maxIntervalHours
        assertEquals(3600_000L, computeBackoffMillis(4, maxIntervalHours = 1))
    }

    @Test
    fun `maybeSend skips when next retry is in the future`() = runBlocking {
        val today = LocalDate.of(2026, 10, 30)
        val now = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + 3600_000L
        val clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)
        var networkCalled = false

        val mockClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                networkCalled = true
                throw AssertionError("Should not be called")
            })
            .build()

        val ledgerStore = MemoryLedgerStore()
        ledgerStore.save(LedgerState(send = LedgerSend(lastOkMs = null, nextRetryMs = now + 5000L, failures = 0)))
        val ledger = Ledger(clock, ledgerStore)

        val configFile = tempFolder.newFile("cfg1.json")
        val configStore = RemoteConfigStore(configFile)
        configStore.save(RemoteConfig(configVersion = 1, enabled = true, reportIntervalHours = 24, configTtlHours = 12, fetchedAtMs = now))
        val configClient = RemoteConfigClient(mockClient, "https://mock.local", configStore)

        val sender = createSender(mockClient, ledger, configClient, clock)
        sender.maybeSend()

        assertFalse(networkCalled)
    }

    @Test
    fun `maybeSend clears ledger and aborts when telemetry is disabled`() = runBlocking {
        val today = LocalDate.of(2026, 10, 30)
        val now = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + 3600_000L
        val clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)
        var networkCalled = false

        val mockClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                networkCalled = true
                throw AssertionError("Should not be called")
            })
            .build()
        val ledgerStore = MemoryLedgerStore()
        ledgerStore.save(
            LedgerState(
                pages = listOf(
                    DayPage(
                        day = today,
                        dayKey = "key1",
                        rev = 1,
                        ackedRev = 0,
                        versionCode = 16,
                        prevActiveDay = null,
                        prevVersionCode = null,
                        counts = emptyList(),
                        countsTruncated = 0,
                        firsts = emptyList(),
                        custom = emptyMap(),
                    )
                )
            )
        )
        val ledger = Ledger(clock, ledgerStore)

        val configFile = tempFolder.newFile("cfg_disabled.json")
        val configStore = RemoteConfigStore(configFile)
        configStore.save(RemoteConfig(configVersion = 1, enabled = false, reportIntervalHours = 24, configTtlHours = 12, fetchedAtMs = now))
        val configClient = RemoteConfigClient(mockClient, "https://mock.local", configStore)

        val sender = createSender(mockClient, ledger, configClient, clock)
        sender.maybeSend()

        assertFalse(networkCalled)
        // Verify ledger was cleared for disabled
        val snapshot = ledger.snapshotForSend(today)
        assertTrue(snapshot.days.isEmpty())
        assertEquals(1, snapshot.droppedDays)
    }

    @Test
    fun `maybeSend on 200 acks page and advances nextRetryMs`() = runBlocking {
        val today = LocalDate.of(2026, 10, 30)
        val now = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + 3600_000L
        val clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)
        val dayKey = "6f1c2a4e-8d3b-4c1a-9e2f-0b7d5a3c9e11"

        val mockClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        """
                        {
                          "ack": ["$dayKey"],
                          "rejected": [],
                          "config_version": 1
                        }
                        """.trimIndent().toResponseBody("application/json".toMediaType())
                    )
                    .build()
            })
            .build()

        val ledgerStore = MemoryLedgerStore()
        ledgerStore.save(
            LedgerState(
                pages = listOf(
                    DayPage(
                        day = today,
                        dayKey = dayKey,
                        rev = 1,
                        ackedRev = 0,
                        versionCode = 16,
                        prevActiveDay = null,
                        prevVersionCode = null,
                        counts = emptyList(),
                        countsTruncated = 0,
                        firsts = emptyList(),
                        custom = emptyMap(),
                    )
                )
            )
        )
        val ledger = Ledger(clock, ledgerStore)

        val configFile = tempFolder.newFile("cfg_ok.json")
        val configStore = RemoteConfigStore(configFile)
        configStore.save(RemoteConfig(configVersion = 1, enabled = true, reportIntervalHours = 24, configTtlHours = 12, fetchedAtMs = now))
        val configClient = RemoteConfigClient(mockClient, "https://mock.local", configStore)

        val sender = createSender(mockClient, ledger, configClient, clock)
        sender.maybeSend()

        // Page should now be acked (ackedRev == 1)
        val snapshot = ledger.snapshotForSend(today)
        assertTrue(snapshot.days.isEmpty())

        // Send state updated
        val sendState = ledger.sendState()
        assertNotNull(sendState)
        assertEquals(now, sendState?.lastOkMs)
        assertEquals(now + 24 * 3600_000L, sendState?.nextRetryMs)
        assertEquals(0, sendState?.failures)
    }

    @Test
    fun `maybeSend on network failure backs off exponentially`() = runBlocking {
        val today = LocalDate.of(2026, 10, 30)
        val now = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + 3600_000L
        val clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)

        val mockClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                throw IOException("connection reset")
            })
            .build()

        val ledgerStore = MemoryLedgerStore()
        ledgerStore.save(
            LedgerState(
                pages = listOf(
                    DayPage(
                        day = today,
                        dayKey = "key1",
                        rev = 1,
                        ackedRev = 0,
                        versionCode = 16,
                        prevActiveDay = null,
                        prevVersionCode = null,
                        counts = emptyList(),
                        countsTruncated = 0,
                        firsts = emptyList(),
                        custom = emptyMap(),
                    )
                ),
                send = LedgerSend(lastOkMs = null, nextRetryMs = 0L, failures = 1),
            )
        )
        val ledger = Ledger(clock, ledgerStore)

        val configFile = tempFolder.newFile("cfg_err.json")
        val configStore = RemoteConfigStore(configFile)
        configStore.save(RemoteConfig(configVersion = 1, enabled = true, reportIntervalHours = 24, configTtlHours = 12, fetchedAtMs = now))
        val configClient = RemoteConfigClient(mockClient, "https://mock.local", configStore)

        val sender = createSender(mockClient, ledger, configClient, clock)
        sender.maybeSend()

        val sendState = ledger.sendState()
        assertEquals(2, sendState?.failures)
        assertEquals(now + 2 * 3600_000L, sendState?.nextRetryMs)
    }

    @Test
    fun `maybeSend on 400 drops batch and increments dropped_days without retry`() = runBlocking {
        val today = LocalDate.of(2026, 10, 30)
        val now = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + 3600_000L
        val clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)
        val dayKey = "bad-key-1"

        val mockClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(400)
                    .message("Bad Request")
                    .body("""{"error":"bad_json"}""".toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()

        val ledgerStore = MemoryLedgerStore()
        ledgerStore.save(
            LedgerState(
                pages = listOf(
                    DayPage(
                        day = today,
                        dayKey = dayKey,
                        rev = 1,
                        ackedRev = 0,
                        versionCode = 16,
                        prevActiveDay = null,
                        prevVersionCode = null,
                        counts = emptyList(),
                        countsTruncated = 0,
                        firsts = emptyList(),
                        custom = emptyMap(),
                    )
                ),
                send = LedgerSend(lastOkMs = null, nextRetryMs = 0L, failures = 2),
            )
        )
        val ledger = Ledger(clock, ledgerStore)

        val configFile = tempFolder.newFile("cfg_400.json")
        val configStore = RemoteConfigStore(configFile)
        configStore.save(RemoteConfig(configVersion = 1, enabled = true, reportIntervalHours = 24, configTtlHours = 12, fetchedAtMs = now))
        val configClient = RemoteConfigClient(mockClient, "https://mock.local", configStore)

        val sender = createSender(mockClient, ledger, configClient, clock)
        sender.maybeSend()

        // Page should be dropped, dropped_days incremented
        val snapshot = ledger.snapshotForSend(today)
        assertTrue(snapshot.days.isEmpty())
        assertEquals(1, snapshot.droppedDays)

        // No retry backoff: failures reset to 0, next retry scheduled at report interval
        val sendState = ledger.sendState()
        assertEquals(0, sendState?.failures)
        assertEquals(now + 24 * 3600_000L, sendState?.nextRetryMs)
    }

    @Test
    fun `maybeSend on non-retryable 4xx drops batch without retry`() = runBlocking {
        for (code in listOf(404, 405, 415)) {
            val today = LocalDate.of(2026, 10, 30)
            val now = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + 3600_000L
            val clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)
            val dayKey = "key-$code"

            val mockClient = OkHttpClient.Builder()
                .addInterceptor(Interceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(code)
                        .message("Client Error")
                        .body("""{"error":"error"}""".toResponseBody("application/json".toMediaType()))
                        .build()
                })
                .build()

            val ledgerStore = MemoryLedgerStore()
            ledgerStore.save(
                LedgerState(
                    pages = listOf(
                        DayPage(
                            day = today,
                            dayKey = dayKey,
                            rev = 1,
                            ackedRev = 0,
                            versionCode = 16,
                            prevActiveDay = null,
                            prevVersionCode = null,
                            counts = emptyList(),
                            countsTruncated = 0,
                            firsts = emptyList(),
                            custom = emptyMap(),
                        )
                    )
                )
            )
            val ledger = Ledger(clock, ledgerStore)

            val configFile = tempFolder.newFile("cfg_$code.json")
            val configStore = RemoteConfigStore(configFile)
            configStore.save(RemoteConfig(configVersion = 1, enabled = true, reportIntervalHours = 24, configTtlHours = 12, fetchedAtMs = now))
            val configClient = RemoteConfigClient(mockClient, "https://mock.local", configStore)

            val sender = createSender(mockClient, ledger, configClient, clock)
            sender.maybeSend()

            val snapshot = ledger.snapshotForSend(today)
            assertTrue(snapshot.days.isEmpty())
            assertEquals(1, snapshot.droppedDays)

            val sendState = ledger.sendState()
            assertEquals(0, sendState?.failures)
            assertEquals(now + 24 * 3600_000L, sendState?.nextRetryMs)
        }
    }

    @Test
    fun `maybeSend on 429 or 5xx backs off`() = runBlocking {
        for (code in listOf(429, 500, 503)) {
            val today = LocalDate.of(2026, 10, 30)
            val now = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + 3600_000L
            val clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)

            val mockClient = OkHttpClient.Builder()
                .addInterceptor(Interceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(code)
                        .message("Server / Rate Limit")
                        .body("""{"error":"retry"}""".toResponseBody("application/json".toMediaType()))
                        .build()
                })
                .build()

            val ledgerStore = MemoryLedgerStore()
            ledgerStore.save(
                LedgerState(
                    pages = listOf(
                        DayPage(
                            day = today,
                            dayKey = "key-$code",
                            rev = 1,
                            ackedRev = 0,
                            versionCode = 16,
                            prevActiveDay = null,
                            prevVersionCode = null,
                            counts = emptyList(),
                            countsTruncated = 0,
                            firsts = emptyList(),
                            custom = emptyMap(),
                        )
                    ),
                    send = LedgerSend(lastOkMs = null, nextRetryMs = 0L, failures = 0),
                )
            )
            val ledger = Ledger(clock, ledgerStore)

            val configFile = tempFolder.newFile("cfg_retry_$code.json")
            val configStore = RemoteConfigStore(configFile)
            configStore.save(RemoteConfig(configVersion = 1, enabled = true, reportIntervalHours = 24, configTtlHours = 12, fetchedAtMs = now))
            val configClient = RemoteConfigClient(mockClient, "https://mock.local", configStore)

            val sender = createSender(mockClient, ledger, configClient, clock)
            sender.maybeSend()

            val snapshot = ledger.snapshotForSend(today)
            assertEquals(1, snapshot.days.size)

            val sendState = ledger.sendState()
            assertEquals(1, sendState?.failures)
            assertEquals(now + 3600_000L, sendState?.nextRetryMs)
        }
    }

    @Test
    fun `maybeSend splits oversized batch to fit within body limit`() = runBlocking {
        val today = LocalDate.of(2026, 10, 30)
        val now = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + 3600_000L
        val clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)

        // Create 2 large pages that together exceed 32 KB (~24 KB each)
        val customMap = (1..15).associate { "k$it" to CustomValue.Str("v".repeat(40)) }
        val counts = (1..30).map { PageCount("chat_turn_start", "label$it", 1, customMap) }

        val page1 = DayPage(
            day = today.minusDays(1),
            dayKey = "p1-key",
            rev = 1,
            ackedRev = 0,
            versionCode = 16,
            prevActiveDay = null,
            prevVersionCode = null,
            counts = counts,
            countsTruncated = 0,
            firsts = emptyList(),
            custom = emptyMap(),
        )
        val page2 = DayPage(
            day = today,
            dayKey = "p2-key",
            rev = 1,
            ackedRev = 0,
            versionCode = 16,
            prevActiveDay = today.minusDays(1),
            prevVersionCode = 16,
            counts = counts,
            countsTruncated = 0,
            firsts = emptyList(),
            custom = emptyMap(),
        )

        val batchSizes = mutableListOf<Int>()
        val mockClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val request = chain.request()
                val bodyBuffer = okio.Buffer()
                request.body?.writeTo(bodyBuffer)
                val bodyBytes = bodyBuffer.readByteArray()
                assertTrue(bodyBytes.size <= TelemetryContract.LIMIT_BODY_BYTES)

                val json = org.json.JSONObject(String(bodyBytes, Charsets.UTF_8))
                val daysArr = json.getJSONArray("days")
                batchSizes.add(daysArr.length())

                val acks = (0 until daysArr.length()).map { daysArr.getJSONObject(it).getString("day_key") }
                val ackJson = acks.joinToString(prefix = "[\"", separator = "\",\"", postfix = "\"]")

                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"ack":$ackJson,"rejected":[],"config_version":1}""".toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()

        val ledgerStore = MemoryLedgerStore()
        ledgerStore.save(LedgerState(pages = listOf(page1, page2)))
        val ledger = Ledger(clock, ledgerStore)

        val configFile = tempFolder.newFile("cfg_split.json")
        val configStore = RemoteConfigStore(configFile)
        configStore.save(RemoteConfig(configVersion = 1, enabled = true, reportIntervalHours = 24, configTtlHours = 12, fetchedAtMs = now))
        val configClient = RemoteConfigClient(mockClient, "https://mock.local", configStore)

        val sender = createSender(mockClient, ledger, configClient, clock)
        sender.maybeSend()

        // Verify that the batch of 2 was split into two 1-page requests
        assertEquals(listOf(1, 1), batchSizes)

        // All pages should be acked
        val snapshot = ledger.snapshotForSend(today)
        assertTrue(snapshot.days.isEmpty())
    }

    private fun createSender(
        http: OkHttpClient,
        ledger: Ledger,
        configClient: RemoteConfigClient,
        clock: Clock,
    ): TelemetrySender {
        val installFacts = InstallFacts(
            v = 1,
            installStampMs = 123L,
            firstChannel = TelemetryContract.CHANNEL_GITHUB_NAME,
            firstVersionCode = 16,
            installDay = "2026-10-30",
        )
        val signals = DeviceSignals(
            apiLevel = 34,
            systemLocale = Locale.CHINA,
            manufacturer = "Xiaomi",
            model = "23127PN0CC",
            abi = "arm64-v8a",
            totalMemBytes = 12L * 1024 * 1024 * 1024,
            smallestScreenWidthDp = 400,
            densityDpi = 480,
            timeZone = TimeZone.getTimeZone("Asia/Shanghai"),
        )
        return TelemetrySender(
            http = http,
            baseUrl = "https://mock.local",
            ledger = ledger,
            configClient = configClient,
            versionCode = 16,
            isDebug = false,
            apkFile = null,
            installFacts = installFacts,
            installer = "com.android.vending",
            signalsProvider = { signals },
            appLangProvider = { "zh-CN" },
            clock = clock,
        )
    }

    private class MemoryLedgerStore : LedgerStore {
        private var state: LedgerState? = null
        override suspend fun load(): LedgerLoad {
            return state?.let { LedgerLoad.Ready(it) }
                ?: LedgerLoad.Missing
        }

        override suspend fun save(state: LedgerState) {
            this.state = state
        }
    }
}
