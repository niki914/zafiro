package com.niki914.zafiro.business.remote.ledger

import com.niki914.zafiro.api.telemetry.TelemetryContract
import com.niki914.zafiro.business.remote.channel.ApkChannelReader
import java.time.Instant
import java.time.ZoneOffset

/**
 * `install.json` 的内存形态：`v`、`install_stamp_ms`、`first_channel`、
 * `first_version_code`、`install_day`。只计算，不写文件。
 */
internal data class InstallFacts(
    val v: Int,
    val installStampMs: Long,
    val firstChannel: String,
    val firstVersionCode: Int?,
    val installDay: String,
) {
    companion object {
        /** 文件格式版本。schema 里 `install.json` 的 `v`。 */
        const val VERSION = 1

        /**
         * 还没有可读的本地记录时：
         * `firstInstallTime < lastUpdateTime` 记为 legacy，`first_version_code` 为 null；
         * 其余情况按全新安装，渠道来自签名块（调试包为 dev），版本是当前版本。
         * 已有版本 1 且 `install_stamp_ms` 仍等于本次 `firstInstallTime` 的记录原样返回。
         */
        fun resolve(
            firstInstallTimeMs: Long,
            lastUpdateTimeMs: Long,
            versionCode: Int,
            isDebug: Boolean,
            stampedChannel: String?,
            stored: InstallFacts? = null,
        ): InstallFacts {
            if (stored != null &&
                stored.v == VERSION &&
                stored.installStampMs == firstInstallTimeMs
            ) {
                return stored
            }
            val legacy = firstInstallTimeMs < lastUpdateTimeMs
            return InstallFacts(
                v = VERSION,
                installStampMs = firstInstallTimeMs,
                firstChannel = if (legacy) {
                    TelemetryContract.CHANNEL_LEGACY_NAME
                } else {
                    ApkChannelReader.freshInstallChannel(isDebug, stampedChannel)
                },
                firstVersionCode = if (legacy) null else versionCode,
                installDay = utcDay(firstInstallTimeMs),
            )
        }

        private fun utcDay(epochMs: Long): String =
            Instant.ofEpochMilli(epochMs).atOffset(ZoneOffset.UTC).toLocalDate().toString()
    }
}
