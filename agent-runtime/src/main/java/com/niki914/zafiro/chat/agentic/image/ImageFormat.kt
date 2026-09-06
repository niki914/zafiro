package com.niki914.zafiro.chat.agentic.image

import kotlin.math.max
import kotlin.math.sqrt

/**
 * 图片 ingest 管线的纯逻辑层：格式判定、mime 规范化、data URL 剥离、尺寸计算。
 * 不依赖 Android framework，JVM 直测。Bitmap 编解码在 [ImageCodec]。
 */
internal object ImageFormat {

    // ── 尺寸 / 大小护栏 ─────────────────────────────────────────────────
    const val MAX_IMAGE_BYTES = 12 * 1024 * 1024
    const val MAX_LONG_EDGE = 1600
    const val MAX_PIXELS = 1_500_000L

    // ── 魔数校验 ─────────────────────────────────────────────────────────

    /** 字节流是否为 BitmapFactory 可识别的图片格式（JPEG/PNG/GIF/WebP/HEIC/AVIF）。 */
    fun hasImageMagic(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        // JPEG: FF D8
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) return true
        // PNG: 89 50 4E 47
        if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        ) return true
        // GIF: GIF8
        if (bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte()
        ) return true
        // WebP: RIFF....WEBP
        if (bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()
        ) return true
        // HEIC / AVIF: ftype box (....ftyp heic/heix/hevc/hevx/mif1/msf1/avif/avis)
        if (bytes.size >= 12 &&
            bytes[4] == 'f'.code.toByte() && bytes[5] == 't'.code.toByte() &&
            bytes[6] == 'y'.code.toByte() && bytes[7] == 'p'.code.toByte()
        ) {
            val brand = String(bytes, 8, 4, Charsets.US_ASCII)
            if (brand in setOf("heic", "heix", "hevc", "hevx", "mif1", "msf1", "avif", "avis")) return true
        }
        return false
    }

    // ── data URL ─────────────────────────────────────────────────────────

    /**
     * 从 data URL 提取 base64 负载。接受：
     * `data:image/png;base64,iVBOR...` / `data:image/jpeg;base64,/9j/...`。
     * 非 data URL 或格式不符 → 返回原字符串。
     */
    fun stripDataUrlPrefix(value: String): String {
        val trimmed = value.trim()
        if (!trimmed.startsWith("data:", ignoreCase = true)) return value
        val comma = trimmed.indexOf(',')
        if (comma <= 0) return value
        val header = trimmed.substring(0, comma)
        if (!header.endsWith(";base64", ignoreCase = true)) return value
        return trimmed.substring(comma + 1)
    }

    /** 从 data URL 提取 mime（如 `image/png`）。非 data URL → null。 */
    fun extractMimeFromDataUrl(value: String): String? {
        val trimmed = value.trim()
        if (!trimmed.startsWith("data:", ignoreCase = true)) return null
        val comma = trimmed.indexOf(',')
        val header = if (comma > 0) trimmed.substring(0, comma) else trimmed
        // 前面已按 ignoreCase 判定过 data: 前缀，直接按固定长度截，避开混合大小写
        val afterData = header.substring(5)
        return afterData.substringBefore(';').takeIf { it.startsWith("image/", ignoreCase = true) }?.lowercase()
    }

    // ── mime 规范化 ──────────────────────────────────────────────────────

    /** mime 规范化：去掉参数、通配符降级为 image/jpeg。 */
    fun normalizeMime(mime: String): String {
        val base = mime.substringBefore(';').trim().lowercase()
        if (base.isEmpty() || base == "image/*") return "image/jpeg"
        if (!base.startsWith("image/")) return "image/jpeg"
        return base
    }

    // ── 尺寸计算 ─────────────────────────────────────────────────────────

    /**
     * 计算目标尺寸：长边 ≤ [MAX_LONG_EDGE] 且像素数 ≤ [MAX_PIXELS]。
     * 输入宽高非法时原样返回。
     */
    fun targetSize(width: Int, height: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return width to height
        val longEdge = max(width, height)
        var scale = if (longEdge > MAX_LONG_EDGE) MAX_LONG_EDGE.toDouble() / longEdge else 1.0
        val scaledPixels = width.toDouble() * height * scale * scale
        if (scaledPixels > MAX_PIXELS) {
            scale *= sqrt(MAX_PIXELS / scaledPixels)
        }
        val w = (width * scale).toInt().coerceIn(1, width)
        val h = (height * scale).toInt().coerceIn(1, height)
        return w to h
    }

    /**
     * BitmapFactory 的 inSampleSize（2 的幂次）：让解码后尺寸 ≥ target。
     * 返回 1（不降采样）当原图已够小。
     */
    fun sampleSize(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Int {
        var sample = 1
        while (sample <= 64) {
            val next = sample * 2
            val nextWidth = width.toDouble() / next
            val nextHeight = height.toDouble() / next
            if (nextWidth < targetWidth * 0.9 || nextHeight < targetHeight * 0.9) break
            sample = next
        }
        return sample
    }
}

// ── 错误模型 ─────────────────────────────────────────────────────────────

/** ingest 失败原因。 */
internal sealed interface IngestError {
    data object FileNotFound : IngestError
    data object EmptyContent : IngestError
    data class TooLarge(val bytes: Int) : IngestError
    data object UnsupportedFormat : IngestError
    data class DecodeFailed(val cause: Throwable?) : IngestError
    data class IoFailed(val cause: Throwable?) : IngestError
    data class SvgRenderFailed(val cause: Throwable?) : IngestError
}
