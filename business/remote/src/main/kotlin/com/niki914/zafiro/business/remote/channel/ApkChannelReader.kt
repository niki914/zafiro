package com.niki914.zafiro.business.remote.channel

import com.niki914.zafiro.api.telemetry.TelemetryContract
import com.niki914.zafiro.api.util.tryOrNull
import java.io.File
import java.io.RandomAccessFile

/**
 * 从 APK Signing Block 读取渠道。
 *
 * 与 `tools/apk_channel.py` 的读回规则一致：块 ID 为 0x7A616672，
 * 值是无空白的 ASCII JSON `{"channel":"<name>"}`，name 匹配
 * [TelemetryContract.LIMIT_CHANNEL_NAME_PATTERN]，且整个块里恰好一对。
 * 读不到就不返回名字，调用方不会因为坏 APK 崩溃。
 */
internal object ApkChannelReader {
    private const val CHANNEL_ID = 0x7A616672L
    private const val EOCD_WINDOW = 65535 + 22
    private val SIGNING_BLOCK_MAGIC = "APK Sig Block 42".encodeToByteArray()
    private val ZIP64_LOCATOR = byteArrayOf(0x50, 0x4b, 0x06, 0x07)
    private val CHANNEL_NAME = Regex(TelemetryContract.LIMIT_CHANNEL_NAME_PATTERN)
    private const val CHANNEL_JSON_WRAPPER = 14 // {"channel":""}
    private val channelValueLength =
        (CHANNEL_JSON_WRAPPER + TelemetryContract.LIMIT_CHANNEL_NAME_MIN).toLong()..
            (CHANNEL_JSON_WRAPPER + TelemetryContract.LIMIT_CHANNEL_NAME_MAX).toLong()

    /** 签名块里的渠道名。没有、重复或字节不合法时返回 null。 */
    fun readStampedName(apk: File): String? = tryOrNull {
        RandomAccessFile(apk, "r").use { readStampedName(FileSource(it)) }
    }

    /** 签名块里的渠道名。没有、重复或字节不合法时返回 null。 */
    fun readStampedName(bytes: ByteArray): String? = readStampedName(BytesSource(bytes))

    /**
     * `app.current_channel`。
     * 调试包固定为 [TelemetryContract.CHANNEL_DEV_NAME]，不打开 APK。
     * 发布包读不到、名字不合法，或签名块里是 legacy 时，记 [TelemetryContract.CHANNEL_UNKNOWN_NAME]。
     */
    fun currentChannel(isDebug: Boolean, apk: File): String {
        if (isDebug) return TelemetryContract.CHANNEL_DEV_NAME
        return releaseChannel(readStampedName(apk))
    }

    /**
     * 全新安装的 `install.first_channel`。
     * 调试包为 dev。发布包用签名块里的合法名字（legacy 可以留在首装渠道）；否则为 unknown。
     */
    fun freshInstallChannel(isDebug: Boolean, stamped: String?): String {
        if (isDebug) return TelemetryContract.CHANNEL_DEV_NAME
        if (stamped != null && CHANNEL_NAME.matches(stamped)) return stamped
        return TelemetryContract.CHANNEL_UNKNOWN_NAME
    }

    private fun releaseChannel(stamped: String?): String {
        if (stamped == null || stamped == TelemetryContract.CHANNEL_LEGACY_NAME) {
            return TelemetryContract.CHANNEL_UNKNOWN_NAME
        }
        if (!CHANNEL_NAME.matches(stamped)) return TelemetryContract.CHANNEL_UNKNOWN_NAME
        return stamped
    }

    private fun readStampedName(source: ApkSource): String? {
        if (source.size < 22) return null
        val tailLength = minOf(source.size, EOCD_WINDOW.toLong()).toInt()
        val tailOffset = source.size - tailLength
        val tail = source.readAt(tailOffset, tailLength) ?: return null
        val eocdInTail = findEocd(tail) ?: return null
        val eocd = tailOffset + eocdInTail
        if (eocd >= 20) {
            val mark = source.readAt(eocd - 20, 4) ?: return null
            if (mark.contentEquals(ZIP64_LOCATOR)) return null
        }
        val cdOffset = uint32(tail, eocdInTail + 16) ?: return null
        if (cdOffset == 0xFFFFFFFFL || cdOffset > eocd || cdOffset < 32) return null
        val sizeAndMagic = source.readAt(cdOffset - 24, 24) ?: return null
        val sizeTail = uint64(sizeAndMagic, 0) ?: return null
        if (!sizeAndMagic.copyOfRange(8, 24).contentEquals(SIGNING_BLOCK_MAGIC)) return null
        if (sizeTail > cdOffset - 8) return null
        val blockStart = cdOffset - sizeTail - 8
        val sizeHeadBytes = source.readAt(blockStart, 8) ?: return null
        val sizeHead = uint64(sizeHeadBytes, 0) ?: return null
        if (sizeHead != sizeTail) return null
        return channelInPairs(source, blockStart + 8, cdOffset - 24)
    }

    private fun channelInPairs(source: ApkSource, start: Long, end: Long): String? {
        var cursor = start
        var channelCount = 0
        var pending: ByteArray? = null
        while (cursor < end) {
            if (end - cursor < 12) return null
            val header = source.readAt(cursor, 12) ?: return null
            val pairLength = uint64(header, 0) ?: return null
            val pairId = uint32(header, 8) ?: return null
            if (pairLength < 4 || pairLength > end - cursor - 8) return null
            if (pairId == CHANNEL_ID) {
                channelCount += 1
                val valueLength = pairLength - 4
                if (channelCount == 1 && valueLength in channelValueLength) {
                    pending = source.readAt(cursor + 12, valueLength.toInt()) ?: return null
                }
            }
            cursor += 8 + pairLength
        }
        if (cursor != end || channelCount != 1) return null
        return pending?.let(::canonicalName)
    }

    private fun canonicalName(raw: ByteArray): String? {
        if (raw.any { it < 0 }) return null
        val text = raw.decodeToString()
        val prefix = "{\"channel\":\""
        val suffix = "\"}"
        if (!text.startsWith(prefix) || !text.endsWith(suffix)) return null
        val name = text.substring(prefix.length, text.length - suffix.length)
        if (!CHANNEL_NAME.matches(name)) return null
        return name
    }

    private fun findEocd(tail: ByteArray): Int? {
        if (tail.size < 22) return null
        for (index in tail.size - 22 downTo 0) {
            if (tail[index] != 0x50.toByte() ||
                tail[index + 1] != 0x4b.toByte() ||
                tail[index + 2] != 0x05.toByte() ||
                tail[index + 3] != 0x06.toByte()
            ) {
                continue
            }
            val commentLength = (tail[index + 20].toInt() and 0xff) or
                ((tail[index + 21].toInt() and 0xff) shl 8)
            if (index + 22 + commentLength == tail.size) return index
        }
        return null
    }

    private fun uint32(bytes: ByteArray, offset: Int): Long? {
        if (offset < 0 || offset + 4 > bytes.size) return null
        var value = 0L
        for (index in 0 until 4) {
            value = value or ((bytes[offset + index].toLong() and 0xff) shl (8 * index))
        }
        return value
    }

    private fun uint64(bytes: ByteArray, offset: Int): Long? {
        if (offset < 0 || offset + 8 > bytes.size) return null
        var value = 0L
        for (index in 0 until 8) {
            value = value or ((bytes[offset + index].toLong() and 0xff) shl (8 * index))
        }
        if (value < 0) return null
        return value
    }
}

private interface ApkSource {
    val size: Long
    fun readAt(offset: Long, length: Int): ByteArray?
}

private class BytesSource(private val data: ByteArray) : ApkSource {
    override val size: Long = data.size.toLong()

    override fun readAt(offset: Long, length: Int): ByteArray? {
        if (length == 0) return ByteArray(0)
        if (length < 0 || offset < 0 || offset > size || length.toLong() > size - offset) return null
        val start = offset.toInt()
        return data.copyOfRange(start, start + length)
    }
}

private class FileSource(private val file: RandomAccessFile) : ApkSource {
    override val size: Long
        get() = file.length()

    override fun readAt(offset: Long, length: Int): ByteArray? {
        if (length == 0) return ByteArray(0)
        if (length < 0 || offset < 0) return null
        val fileSize = file.length()
        if (offset > fileSize || length.toLong() > fileSize - offset) return null
        val buffer = ByteArray(length)
        file.seek(offset)
        var filled = 0
        while (filled < length) {
            val count = file.read(buffer, filled, length - filled)
            if (count < 0) return null
            filled += count
        }
        return buffer
    }
}
