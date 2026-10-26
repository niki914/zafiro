// 保护：备份恢复后安装戳不一致时，旧账页还在就会和另一台设备共用 day_key
package com.niki914.zafiro.business.remote.ledger

import com.niki914.logging.Backend
import com.niki914.logging.Level
import com.niki914.logging.Logger
import com.niki914.zafiro.api.telemetry.TelemetryContract
import org.json.JSONObject
import java.io.File
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InstallFactsStoreTest {
    /** JVM 单测没有 Android 框架，默认的 LogcatBackend 会在业务代码打日志时抛异常。 */
    @BeforeTest
    fun silenceLogger() {
        Logger.install(object : Backend {
            override fun emit(level: Level, tag: String, msg: String, throwable: Throwable?) = Unit
        })
    }

    @Test
    fun `a different install stamp clears the telemetry directory and writes the new stamp`() {
        val directory = createTempDirectory("install").toFile()
        try {
            val previous = Instant.parse("2026-03-02T00:00:00Z").toEpochMilli()
            val current = Instant.parse("2026-10-26T00:00:00Z").toEpochMilli()
            File(directory, "install.json").writeText(installJson(previous))
            File(directory, "ledger.json").writeText("ledger-body")
            File(directory, "config.json").writeText("config-body")
            InstallFactsStore(directory).resolve(
                firstInstallTimeMs = current,
                lastUpdateTimeMs = current + 1,
                versionCode = 16,
                isDebug = false,
                stampedChannel = TelemetryContract.CHANNEL_GITHUB_NAME,
            )
            val json = JSONObject(File(directory, "install.json").readText())
            assertEquals(listOf("install.json"), directory.list()?.sorted())
            assertEquals(current, json.getLong("install_stamp_ms"))
            assertTrue(json.isNull("first_version_code"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `the same install stamp keeps the stored channel and the ledger file`() {
        val directory = createTempDirectory("install").toFile()
        try {
            val stamp = Instant.parse("2026-03-02T00:00:00Z").toEpochMilli()
            File(directory, "install.json").writeText(installJson(stamp))
            File(directory, "ledger.json").writeText("ledger-body")
            val facts = InstallFactsStore(directory).resolve(
                firstInstallTimeMs = stamp,
                lastUpdateTimeMs = Instant.parse("2026-10-26T00:00:00Z").toEpochMilli(),
                versionCode = 16,
                isDebug = true,
                stampedChannel = TelemetryContract.CHANNEL_XPOSED_NAME,
            )
            assertEquals(TelemetryContract.CHANNEL_GITHUB_NAME, facts.firstChannel)
            assertEquals(15, facts.firstVersionCode)
            assertEquals("ledger-body", File(directory, "ledger.json").readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `an unreadable install file does not delete the ledger`() {
        val directory = createTempDirectory("install").toFile()
        try {
            File(directory, "install.json").writeText("{")
            File(directory, "ledger.json").writeText("ledger-body")
            val stamp = Instant.parse("2026-10-26T01:00:00Z").toEpochMilli()
            InstallFactsStore(directory).resolve(
                firstInstallTimeMs = stamp,
                lastUpdateTimeMs = stamp,
                versionCode = 16,
                isDebug = false,
                stampedChannel = TelemetryContract.CHANNEL_GITHUB_NAME,
            )
            assertEquals("{", File(directory, "install.json").readText())
            assertEquals("ledger-body", File(directory, "ledger.json").readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `a missing install file does not delete an existing ledger`() {
        val directory = createTempDirectory("install").toFile()
        try {
            File(directory, "ledger.json").writeText("ledger-body")
            val stamp = Instant.parse("2026-10-26T01:00:00Z").toEpochMilli()
            InstallFactsStore(directory).resolve(
                firstInstallTimeMs = stamp,
                lastUpdateTimeMs = stamp,
                versionCode = 16,
                isDebug = false,
                stampedChannel = TelemetryContract.CHANNEL_GITHUB_NAME,
            )
            val json = JSONObject(File(directory, "install.json").readText())
            assertEquals("ledger-body", File(directory, "ledger.json").readText())
            assertEquals(INSTALL_KEYS, keys(json))
        } finally {
            directory.deleteRecursively()
        }
    }
}

private val INSTALL_KEYS = setOf(
    "v",
    "install_stamp_ms",
    "first_channel",
    "first_version_code",
    "install_day",
)

private fun installJson(stamp: Long): String {
    return """{"v":1,"install_stamp_ms":$stamp,"first_channel":"${TelemetryContract.CHANNEL_GITHUB_NAME}","first_version_code":15,"install_day":"2026-03-02"}"""
}

private fun keys(obj: JSONObject): Set<String> {
    val names = mutableSetOf<String>()
    val iterator = obj.keys()
    while (iterator.hasNext()) names.add(iterator.next())
    return names
}
