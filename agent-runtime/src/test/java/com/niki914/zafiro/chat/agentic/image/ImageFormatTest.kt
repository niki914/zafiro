package com.niki914.zafiro.chat.agentic.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageFormatTest {

    // ── 魔数校验 ─────────────────────────────────────────────────────────

    @Test
    fun hasImageMagic_jpeg() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(4)
        assertTrue(ImageFormat.hasImageMagic(jpeg))
    }

    @Test
    fun hasImageMagic_png() {
        val png = byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte()) + ByteArray(4)
        assertTrue(ImageFormat.hasImageMagic(png))
    }

    @Test
    fun hasImageMagic_gif() {
        val gif = byteArrayOf('G'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), '8'.code.toByte()) + ByteArray(4)
        assertTrue(ImageFormat.hasImageMagic(gif))
    }

    @Test
    fun hasImageMagic_webp() {
        val webp = byteArrayOf(
            'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
            0, 0, 0, 0,
            'W'.code.toByte(), 'E'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte()
        ) + ByteArray(4)
        assertTrue(ImageFormat.hasImageMagic(webp))
    }

    @Test
    fun hasImageMagic_heic() {
        val heic = ByteArray(4) + byteArrayOf(
            'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte()
        ) + "heic".toByteArray() + ByteArray(4)
        assertTrue(ImageFormat.hasImageMagic(heic))
    }

    @Test
    fun hasImageMagic_randomBytes_returnsFalse() {
        val random = ByteArray(16) { it.toByte() }
        assertFalse(ImageFormat.hasImageMagic(random))
    }

    @Test
    fun hasImageMagic_tooShort_returnsFalse() {
        assertFalse(ImageFormat.hasImageMagic(byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
    }

    // ── data URL ─────────────────────────────────────────────────────────

    @Test
    fun stripDataUrlPrefix_validPng() {
        val input = "data:image/png;base64,iVBORw0KGgo="
        assertEquals("iVBORw0KGgo=", ImageFormat.stripDataUrlPrefix(input))
    }

    @Test
    fun stripDataUrlPrefix_validJpeg() {
        val input = "data:image/jpeg;base64,/9j/4AAQ="
        assertEquals("/9j/4AAQ=", ImageFormat.stripDataUrlPrefix(input))
    }

    @Test
    fun stripDataUrlPrefix_noPrefix_returnsOriginal() {
        assertEquals("iVBORw0KGgo=", ImageFormat.stripDataUrlPrefix("iVBORw0KGgo="))
    }

    @Test
    fun stripDataUrlPrefix_notBase64_returnsOriginal() {
        val input = "data:image/plain,texthello"
        assertEquals(input, ImageFormat.stripDataUrlPrefix(input))
    }

    @Test
    fun stripDataUrlPrefix_whitespace_trimmed() {
        val input = "  data:image/png;base64,ABC==  "
        assertEquals("ABC==", ImageFormat.stripDataUrlPrefix(input))
    }

    @Test
    fun extractMimeFromDataUrl_valid() {
        assertEquals("image/png", ImageFormat.extractMimeFromDataUrl("data:image/png;base64,ABC"))
        assertEquals("image/webp", ImageFormat.extractMimeFromDataUrl("data:IMAGE/WEBP;base64,ABC"))
    }

    @Test
    fun extractMimeFromDataUrl_noPrefix_returnsNull() {
        assertNull(ImageFormat.extractMimeFromDataUrl("iVBORw0KGgo="))
    }

    // ── mime 规范化 ──────────────────────────────────────────────────────

    @Test
    fun normalizeMime_stripsParams() {
        assertEquals("image/jpeg", ImageFormat.normalizeMime("image/jpeg; charset=binary"))
    }

    @Test
    fun normalizeMime_wildcard_fallsBack() {
        assertEquals("image/jpeg", ImageFormat.normalizeMime("image/*"))
    }

    @Test
    fun normalizeMime_empty_fallsBack() {
        assertEquals("image/jpeg", ImageFormat.normalizeMime(""))
    }

    // ── 尺寸计算 ─────────────────────────────────────────────────────────

    @Test
    fun targetSize_smallImage_unchanged() {
        assertEquals(800 to 600, ImageFormat.targetSize(800, 600))
    }

    @Test
    fun targetSize_longEdgeClamped() {
        // 4000x3000 → 长边 4000→1600 (scale 0.4), 像素 4000*3000*0.4²=1.92M > 1.5M
        // 二次 scale: sqrt(1.5/1.92)=0.8839, 最终 scale=0.4*0.8839=0.3536
        // w=4000*0.3536≈1414, h=3000*0.3536≈1061
        val (w, h) = ImageFormat.targetSize(4000, 3000)
        assertTrue(w <= ImageFormat.MAX_LONG_EDGE)
        assertTrue(h <= ImageFormat.MAX_LONG_EDGE)
        assertTrue(w.toLong() * h <= ImageFormat.MAX_PIXELS)
    }

    @Test
    fun targetSize_portrait_clampsLongEdge() {
        val (w, h) = ImageFormat.targetSize(3000, 4000)
        assertTrue(h <= ImageFormat.MAX_LONG_EDGE)
        assertTrue(w.toLong() * h <= ImageFormat.MAX_PIXELS)
    }

    @Test
    fun targetSize_invalidSize_unchanged() {
        assertEquals(0 to 0, ImageFormat.targetSize(0, 0))
        assertEquals(-1 to 100, ImageFormat.targetSize(-1, 100))
    }

    // ── inSampleSize ─────────────────────────────────────────────────────

    @Test
    fun sampleSize_alreadySmall_returnsOne() {
        assertEquals(1, ImageFormat.sampleSize(800, 600, 1600, 1600))
    }

    @Test
    fun sampleSize_largeImage_picksPowerOfTwo() {
        // 4000x3000 target 1414x1061
        // sample=2 → 2000x1500 (≥target), sample=4 → 1000x750 (<target) → 返回 2
        assertEquals(2, ImageFormat.sampleSize(4000, 3000, 1414, 1061))
    }
}
