package com.niki914.uikit.infra.internal

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.runtimeShaderEffect
import com.niki914.uikit.base.LocalAppDarkTheme
import com.niki914.uikit.base.LocalLowEndDevice
import com.niki914.uikit.infra.LiquidGlassPresets

/**
 * Liquid Glass 顶栏渐变毛玻璃背景。
 *
 * 方案 A 隔离舱：内聚 Android 版本与设备渲染档位门禁及三阶硬件模糊级联（Multi-Octave Progressive Blur）。
 * - Android 13+ (API 33 / TIRAMISU) 且非中低端机：用 Skia 原生硬件 `blur()` 分三档半径（轻 / 中 / 重）逐层叠合，
 *   相邻档位通过轻量 AGSL Alpha 遮罩平滑过渡，再在顶层叠加与页面一致的 `background` 多段渐变遮罩。
 * - Android 13 以下或中低端机：强制降级为纯 Compose `background` 渐变遮罩，零 GPU 离屏模糊开销。
 */
@Composable
fun LiquidTopBarBackground(
    contentBackdrop: LayerBackdrop,
    chromeBackdrop: LayerBackdrop,
    chromeHeight: Dp,
    buttonSlotHeight: Dp,
    topInset: Dp,
    barAlpha: Float,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val tintColor = MaterialTheme.colorScheme.background
    val topBarStyle = LiquidGlassPresets.TopBar
    val totalHeight = chromeHeight + topBarStyle.bottomBleed
    val useProgressiveBlur =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !LocalLowEndDevice.current

    if (useProgressiveBlur) {
        val isDarkTheme = LocalAppDarkTheme.current
        val heavyRadius = topBarStyle.blurRadius(isDarkTheme)
        val mediumRadius = heavyRadius * 0.45f
        val lightRadius = heavyRadius * 0.175f
        val midTint = topBarStyle.tintIntensity(isDarkTheme)
        val topTint = topBarStyle.topTintIntensity(isDarkTheme)

        // 三阶模糊的起止高度交错排布，避免各层斜率在同一水平线上叠加形成细微横切感
        val lightFadeStartPx = remember(density, chromeHeight, topInset) {
            with(density) { (chromeHeight - 18.dp).coerceAtLeast(topInset).toPx() }
        }
        val mediumHeight = chromeHeight + 4.dp
        val mediumFadeStartPx = remember(density, topInset, buttonSlotHeight) {
            with(density) { (topInset + buttonSlotHeight * 0.45f).toPx() }
        }
        val heavyHeight = (chromeHeight - 8.dp).coerceAtLeast(topInset)
        val heavyFadeStartPx = remember(density, topInset, buttonSlotHeight) {
            with(density) { (topInset + buttonSlotHeight * 0.25f).toPx() }
        }

        val scrimBrush = remember(tintColor, topTint, midTint) {
            Brush.verticalGradient(
                0.00f to tintColor.copy(alpha = topTint),
                0.34f to tintColor.copy(alpha = (topTint + midTint) * 0.52f),
                0.58f to tintColor.copy(alpha = midTint),
                0.80f to tintColor.copy(alpha = midTint * 0.18f),
                1.00f to Color.Transparent,
            )
        }

        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(totalHeight)
                .layerBackdrop(chromeBackdrop)
                .graphicsLayer { alpha = barAlpha },
        ) {
            // 顶栏完全透明时跳过三阶离屏模糊与遮罩，但保留外层空 Box 刷新 chromeBackdrop 防止残留旧帧
            if (barAlpha > 0.001f) {
                // 第 1 阶（轻模糊，0 -> 3.5dp）：负责最底部与锐利内容的无感羽化衔接
                BlurOctaveLayer(
                    contentBackdrop = contentBackdrop,
                    height = totalHeight,
                    blurRadius = lightRadius,
                    fadeStartPx = lightFadeStartPx,
                    shaderKey = "ActionBarBlurOctaveLight_v8",
                )
                // 第 2 阶（中模糊，3.5dp -> 9dp）：承接按钮下沿到标题区的过渡
                BlurOctaveLayer(
                    contentBackdrop = contentBackdrop,
                    height = mediumHeight,
                    blurRadius = mediumRadius,
                    fadeStartPx = mediumFadeStartPx,
                    shaderKey = "ActionBarBlurOctaveMedium_v8",
                )
                // 第 3 阶（重模糊，9dp -> 20dp）：覆盖状态栏与标题核心区，彻底柔化文字
                BlurOctaveLayer(
                    contentBackdrop = contentBackdrop,
                    height = heavyHeight,
                    blurRadius = heavyRadius,
                    fadeStartPx = heavyFadeStartPx,
                    shaderKey = "ActionBarBlurOctaveHeavy_v8",
                )
                // 顶层页面底色渐变遮罩：与模糊层解耦，保证状态栏与标题对比度同时保留通透流动感
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(totalHeight)
                        .background(scrimBrush),
                )
            }
        }
    } else {
        // Android 13 以下或中低端机保底：纯 Compose 页面底色渐变遮罩，零离屏模糊开销
        val fallbackBrush = remember(tintColor) {
            Brush.verticalGradient(
                0.00f to tintColor,
                0.58f to tintColor.copy(alpha = 0.94f),
                0.82f to tintColor.copy(alpha = 0.62f),
                1.00f to Color.Transparent,
            )
        }

        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(totalHeight)
                .layerBackdrop(chromeBackdrop)
                .graphicsLayer { alpha = barAlpha },
        ) {
            if (barAlpha > 0.001f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(totalHeight)
                        .background(fallbackBrush),
                )
            }
        }
    }
}

@Composable
private fun BlurOctaveLayer(
    contentBackdrop: LayerBackdrop,
    height: Dp,
    blurRadius: Dp,
    fadeStartPx: Float,
    shaderKey: String,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .drawPlainBackdrop(
                backdrop = contentBackdrop,
                shape = { RectangleShape },
                effects = {
                    blur(blurRadius.toPx())
                    runtimeShaderEffect(
                        shaderKey,
                        VERTICAL_FADE_MASK_SHADER,
                        "content",
                    ) {
                        setFloatUniform("fadeStartY", fadeStartPx)
                        setFloatUniform("fadeEndY", size.height)
                    }
                },
            ),
    )
}

private val VERTICAL_FADE_MASK_SHADER = """
    uniform shader content;
    uniform float fadeStartY;
    uniform float fadeEndY;

    half4 main(float2 coord) {
        float safeEndY = max(fadeEndY, fadeStartY + 1.0);
        float t = 1.0 - smoothstep(fadeStartY, safeEndY, coord.y);
        float alpha = t * mix(1.0, t, 0.35);
        return content.eval(coord) * half(alpha);
    }
""".trimIndent()
