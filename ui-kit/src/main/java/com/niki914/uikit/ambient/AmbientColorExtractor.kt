package com.niki914.uikit.ambient

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.hct.Hct
import com.materialkolor.quantize.QuantizerCelebi
import com.materialkolor.score.Score
import androidx.core.graphics.scale

private const val SAMPLE_SIZE = 32
private const val QUANTIZE_MAX_COLORS = 32
private const val AMBIENT_COLOR_COUNT = 4

/**
 * 复用现有 `material-kolor`（`QuantizerCelebi` + `Score` + `Hct`）从位图提取 4 个调和氛围色。
 *
 * - 当输入图标偏单色（如仅提取出 1~2 个色）时，在 HCT 空间做邻近色相偏移补齐至 4 色，保证光斑层次；
 * - 按深浅色模式钳制 `tone` 与 `chroma`，保证前景文字与输入内容稳定可读。
 */
object AmbientColorExtractor {

    fun extractFromBitmap(
        bitmap: Bitmap,
        isDarkTheme: Boolean,
        fallbackColor: Color,
    ): List<Color> {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            return fromSeedColor(fallbackColor, isDarkTheme)
        }
        val scaled = if (bitmap.width > SAMPLE_SIZE || bitmap.height > SAMPLE_SIZE) {
            bitmap.scale(SAMPLE_SIZE, SAMPLE_SIZE)
        } else {
            bitmap
        }
        val pixels = IntArray(scaled.width * scaled.height)
        scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
        if (scaled !== bitmap) {
            scaled.recycle()
        }

        val opaquePixels = pixels.filter { (it ushr 24) >= 128 }.toIntArray()
        if (opaquePixels.isEmpty()) {
            return fromSeedColor(fallbackColor, isDarkTheme)
        }

        val quantized = QuantizerCelebi.quantize(opaquePixels, QUANTIZE_MAX_COLORS)
        val scored = Score.score(
            colorsToPopulation = quantized,
            desired = AMBIENT_COLOR_COUNT,
            fallbackColorArgb = fallbackColor.toArgb(),
        )
        val baseHcts = scored.map(Hct::fromInt)
        return buildHarmonizedPalette(baseHcts, isDarkTheme)
    }

    fun fromSeedColor(
        seedColor: Color,
        isDarkTheme: Boolean,
    ): List<Color> {
        return buildHarmonizedPalette(listOf(Hct.fromInt(seedColor.toArgb())), isDarkTheme)
    }

    private fun buildHarmonizedPalette(
        seeds: List<Hct>,
        isDarkTheme: Boolean,
    ): List<Color> {
        val primary = seeds.first()
        val tones = if (isDarkTheme) {
            doubleArrayOf(24.0, 34.0, 28.0, 38.0)
        } else {
            doubleArrayOf(86.0, 80.0, 90.0, 84.0)
        }
        val hueOffsets = doubleArrayOf(0.0, 24.0, -24.0, 44.0)

        return List(AMBIENT_COLOR_COUNT) { index ->
            val source = seeds.getOrNull(index)
            val hue = if (source != null) {
                source.hue
            } else {
                (primary.hue + hueOffsets[index] + 360.0) % 360.0
            }
            val rawChroma = source?.chroma ?: primary.chroma
            val targetChroma = if (rawChroma < 8.0) {
                rawChroma.coerceIn(4.0, 12.0)
            } else if (isDarkTheme) {
                rawChroma.coerceIn(26.0, 58.0)
            } else {
                rawChroma.coerceIn(20.0, 46.0)
            }
            Color(Hct.from(hue, targetChroma, tones[index]).toInt())
        }
    }
}
