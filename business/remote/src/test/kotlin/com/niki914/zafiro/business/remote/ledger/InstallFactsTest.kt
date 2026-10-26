// 保护：没有可读本地记录时，存量安装被记成 legacy 且版本为空；已有记录或全新安装不改写首装渠道
package com.niki914.zafiro.business.remote.ledger

import com.niki914.zafiro.api.telemetry.TelemetryContract
import java.time.Instant
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InstallFactsTest {
    @Test
    fun `equal install and update times record the stamped channel and current version`() {
        val at = Instant.parse("2026-10-26T01:00:00Z").toEpochMilli()
        val facts = resolve(at, at, stamped = TelemetryContract.CHANNEL_GITHUB_NAME)
        assertEquals(InstallFacts.VERSION, facts.v)
        assertEquals(TelemetryContract.CHANNEL_GITHUB_NAME, facts.firstChannel)
        assertEquals(16, facts.firstVersionCode)
    }

    @Test
    fun `install day is the utc calendar date of firstInstallTime`() {
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        try {
            val at = Instant.parse("2026-10-25T16:30:00Z").toEpochMilli()
            val facts = resolve(at, at, stamped = TelemetryContract.CHANNEL_GITHUB_NAME)
            assertEquals("2026-10-25", facts.installDay)
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    @Test
    fun `earlier first install without a record is legacy with a null version`() {
        val first = Instant.parse("2026-03-02T00:00:00Z").toEpochMilli()
        val updated = Instant.parse("2026-10-26T00:00:00Z").toEpochMilli()
        val facts = resolve(first, updated, stamped = TelemetryContract.CHANNEL_GITHUB_NAME)
        assertEquals(TelemetryContract.CHANNEL_LEGACY_NAME, facts.firstChannel)
        assertNull(facts.firstVersionCode)
        assertEquals("2026-03-02", facts.installDay)
    }

    @Test
    fun `legacy wins over the debug channel`() {
        val first = Instant.parse("2026-03-02T00:00:00Z").toEpochMilli()
        val updated = Instant.parse("2026-10-26T00:00:00Z").toEpochMilli()
        val facts = resolve(first, updated, isDebug = true, stamped = TelemetryContract.CHANNEL_GITHUB_NAME)
        assertEquals(TelemetryContract.CHANNEL_LEGACY_NAME, facts.firstChannel)
        assertNull(facts.firstVersionCode)
    }

    @Test
    fun `debug fresh install uses dev and still records the current version`() {
        val at = Instant.parse("2026-10-26T01:00:00Z").toEpochMilli()
        val facts = resolve(at, at, isDebug = true, stamped = TelemetryContract.CHANNEL_GITHUB_NAME)
        assertEquals(TelemetryContract.CHANNEL_DEV_NAME, facts.firstChannel)
        assertEquals(16, facts.firstVersionCode)
    }

    @Test
    fun `missing or illegal stamp on a fresh release install is unknown`() {
        val at = Instant.parse("2026-10-26T01:00:00Z").toEpochMilli()
        assertEquals(
            TelemetryContract.CHANNEL_UNKNOWN_NAME,
            resolve(at, at, stamped = null).firstChannel,
        )
        assertEquals(
            TelemetryContract.CHANNEL_UNKNOWN_NAME,
            resolve(at, at, stamped = "GitHub").firstChannel,
        )
    }

    @Test
    fun `stamped legacy on a fresh install keeps the current version`() {
        val at = Instant.parse("2026-10-26T01:00:00Z").toEpochMilli()
        val facts = resolve(at, at, stamped = TelemetryContract.CHANNEL_LEGACY_NAME)
        assertEquals(TelemetryContract.CHANNEL_LEGACY_NAME, facts.firstChannel)
        assertEquals(16, facts.firstVersionCode)
    }

    @Test
    fun `first install after the latest update is a fresh install stamped with that time`() {
        val first = Instant.parse("2026-03-02T00:00:00Z").toEpochMilli()
        val updated = Instant.parse("2026-10-26T00:00:00Z").toEpochMilli()
        val facts = resolve(updated, first, stamped = TelemetryContract.CHANNEL_GITHUB_NAME)
        assertEquals(TelemetryContract.CHANNEL_GITHUB_NAME, facts.firstChannel)
        assertEquals(16, facts.firstVersionCode)
        assertEquals(updated, facts.installStampMs)
    }

    @Test
    fun `a version 1 record with the same install stamp is kept`() {
        val first = Instant.parse("2026-03-02T00:00:00Z").toEpochMilli()
        val updated = Instant.parse("2026-10-26T00:00:00Z").toEpochMilli()
        val stored = InstallFacts(
            v = InstallFacts.VERSION,
            installStampMs = first,
            firstChannel = TelemetryContract.CHANNEL_GITHUB_NAME,
            firstVersionCode = 15,
            installDay = "2020-01-01",
        )
        val facts = resolve(
            first,
            updated,
            isDebug = true,
            stamped = TelemetryContract.CHANNEL_XPOSED_NAME,
            stored = stored,
        )
        assertEquals(TelemetryContract.CHANNEL_GITHUB_NAME, facts.firstChannel)
        assertEquals(15, facts.firstVersionCode)
        assertEquals("2020-01-01", facts.installDay)
    }

    @Test
    fun `a different install stamp is computed again`() {
        val at = Instant.parse("2026-10-26T01:00:00Z").toEpochMilli()
        val mismatched = InstallFacts(
            v = InstallFacts.VERSION,
            installStampMs = at - 1,
            firstChannel = TelemetryContract.CHANNEL_GITHUB_NAME,
            firstVersionCode = 15,
            installDay = "2020-01-01",
        )
        val facts = resolve(at, at, stamped = TelemetryContract.CHANNEL_XPOSED_NAME, stored = mismatched)
        assertEquals(TelemetryContract.CHANNEL_XPOSED_NAME, facts.firstChannel)
        assertEquals(16, facts.firstVersionCode)
        assertEquals(at, facts.installStampMs)
    }

    @Test
    fun `a different file version is computed again`() {
        val at = Instant.parse("2026-10-26T01:00:00Z").toEpochMilli()
        val stored = InstallFacts(
            v = 2,
            installStampMs = at,
            firstChannel = TelemetryContract.CHANNEL_GITHUB_NAME,
            firstVersionCode = 1,
            installDay = "1999-01-01",
        )
        val facts = resolve(at, at, stamped = TelemetryContract.CHANNEL_GITHUB_NAME, stored = stored)
        assertEquals(InstallFacts.VERSION, facts.v)
        assertEquals(16, facts.firstVersionCode)
        assertEquals("2026-10-26", facts.installDay)
    }

    private fun resolve(
        firstInstallTimeMs: Long,
        lastUpdateTimeMs: Long,
        versionCode: Int = 16,
        isDebug: Boolean = false,
        stamped: String?,
        stored: InstallFacts? = null,
    ): InstallFacts = InstallFacts.resolve(
        firstInstallTimeMs = firstInstallTimeMs,
        lastUpdateTimeMs = lastUpdateTimeMs,
        versionCode = versionCode,
        isDebug = isDebug,
        stampedChannel = stamped,
        stored = stored,
    )
}
