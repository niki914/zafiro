package com.niki914.zafiro.business.remote.config

import com.niki914.zafiro.api.telemetry.TelemetryContract
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RemoteConfigTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `clamping clamps interval and ttl to contract bounds`() {
        val clampedUnder = clampRemoteConfig(
            configVersion = -1,
            rawEnabled = false,
            rawInterval = 0,
            rawTtl = 5,
            fetchedAtMs = 100L,
        )
        assertNull(clampedUnder.configVersion)
        assertFalse(clampedUnder.enabled)
        assertEquals(TelemetryContract.CONFIG_KEY_REPORT_INTERVAL_HOURS_MIN, clampedUnder.reportIntervalHours)
        assertEquals(TelemetryContract.CONFIG_KEY_CONFIG_TTL_HOURS_MIN, clampedUnder.configTtlHours)

        val clampedOver = clampRemoteConfig(
            configVersion = 5,
            rawEnabled = true,
            rawInterval = 999,
            rawTtl = 999,
            fetchedAtMs = 200L,
        )
        assertEquals(5, clampedOver.configVersion)
        assertTrue(clampedOver.enabled)
        assertEquals(TelemetryContract.CONFIG_KEY_REPORT_INTERVAL_HOURS_MAX, clampedOver.reportIntervalHours)
        assertEquals(TelemetryContract.CONFIG_KEY_CONFIG_TTL_HOURS_MAX, clampedOver.configTtlHours)
    }

    @Test
    fun `store roundtrip preserves values`() {
        val file = tempFolder.newFile("config.json")
        val store = RemoteConfigStore(file)

        val original = RemoteConfig(
            configVersion = 2,
            enabled = true,
            reportIntervalHours = 48,
            configTtlHours = 24,
            fetchedAtMs = 1234567L,
        )
        store.save(original)

        val loaded = store.load()
        assertEquals(original, loaded)
    }

    @Test
    fun `store recovers gracefully on corrupt json`() {
        val file = tempFolder.newFile("corrupt.json")
        file.writeText("not a json at all")
        val store = RemoteConfigStore(file)

        val loaded = store.load()
        assertEquals(RemoteConfig.default(), loaded)
    }

    @Test
    fun `client returns 200 values and caches to store`() = runBlocking {
        val file = tempFolder.newFile("client_config.json")
        val store = RemoteConfigStore(file)

        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("ETag", "\"c3\"")
                    .body(
                        """
                        {
                          "config_version": 3,
                          "values": {
                            "telemetry_enabled": true,
                            "report_interval_hours": 36,
                            "config_ttl_hours": 18
                          }
                        }
                        """.trimIndent().toResponseBody("application/json".toMediaType())
                    )
                    .build()
            }
            .build()

        val client = RemoteConfigClient(mockClient, "https://mock.local", store)
        val config = client.getOrFetch(versionCode = 16, nowMs = 1000L, force = true)

        assertEquals(3, config.configVersion)
        assertTrue(config.enabled)
        assertEquals(36, config.reportIntervalHours)
        assertEquals(18, config.configTtlHours)
        assertEquals(1000L, config.fetchedAtMs)

        // Verify store was written
        val fromDisk = store.load()
        assertEquals(config, fromDisk)
    }

    @Test
    fun `client on 304 updates fetched time without changing values`() = runBlocking {
        val file = tempFolder.newFile("client_304.json")
        val store = RemoteConfigStore(file)
        store.save(RemoteConfig(configVersion = 2, enabled = true, reportIntervalHours = 24, configTtlHours = 12, fetchedAtMs = 500L))

        var interceptedIfNoneMatch: String? = null
        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                interceptedIfNoneMatch = chain.request().header("If-None-Match")
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(304)
                    .message("Not Modified")
                    .body("".toResponseBody())
                    .build()
            }
            .build()

        val client = RemoteConfigClient(mockClient, "https://mock.local", store)
        val config = client.getOrFetch(versionCode = 16, nowMs = 2000L, force = true)

        assertEquals("\"c2\"", interceptedIfNoneMatch)
        assertEquals(2, config.configVersion)
        assertEquals(2000L, config.fetchedAtMs)
    }
}
