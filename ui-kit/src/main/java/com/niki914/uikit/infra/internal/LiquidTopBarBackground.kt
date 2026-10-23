package com.niki914.uikit.infra.internal

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.runtimeShaderEffect
import com.niki914.uikit.base.LocalAppDarkTheme
import com.niki914.uikit.infra.LiquidGlassPresets

/**
 * Liquid Glass 顶栏渐变毛玻璃背景。
 *
 * 方案 A 隔离舱：内聚 Android 版本感知与 AGSL Progressive Blur。
 * - Android 13+ (API 33 / TIRAMISU)：执行 Progressive Blur RuntimeShader 渲染。
 * - Android 13 以下：安全降级为 surfaceContainerHigh 实体纯色背景，防止 ClassNotFoundException / NoClassDefFoundError 崩溃。
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

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val isDarkTheme = LocalAppDarkTheme.current
        val tintColor = MaterialTheme.colorScheme.surfaceContainerHigh
        val blurRadius = LiquidGlassPresets.TopBar.blurRadius(isDarkTheme)
        val baseTintIntensity = LiquidGlassPresets.TopBar.tintIntensity(isDarkTheme)
        val topInsetPx = with(density) { topInset.toPx() }
        val fadeStartY = with(density) { (topInset + buttonSlotHeight / 2).toPx() }

        Box(
            modifier
                .fillMaxWidth()
                .height(chromeHeight)
                .graphicsLayer { alpha = barAlpha }
                .drawPlainBackdrop(
                    backdrop = contentBackdrop,
                    shape = { RectangleShape },
                    exportedBackdrop = chromeBackdrop,
                    effects = {
                        blur(blurRadius.toPx())
                        runtimeShaderEffect(
                            "ActionBarProgressiveBlur_v3",
                            """
                            uniform shader content;
                            uniform float2 size;
                            layout(color) uniform half4 tint;
                            uniform float tintIntensity;
                            uniform float progress;
                            uniform float fadeStartY;
                            uniform float topInset;

                            half4 main(float2 coord) {
                                // 从 fadeStartY（标题中线）向下衰减至 size.y 归零完全透明
                                float blurAlpha = smoothstep(size.y, fadeStartY, coord.y) * progress;
                                float tintAlpha = smoothstep(size.y, fadeStartY, coord.y) * progress;

                                // 向上到达屏幕顶部（0 ~ topInset 状态栏区间）时，底色浓度平滑升至 1.0，呈现纯正的 surfaceContainerHigh 原生背景色
                                float topSolidFactor = smoothstep(topInset, 0.0, coord.y);
                                float currentTintIntensity = mix(tintIntensity, 1.0, topSolidFactor);

                                return mix(content.eval(coord) * blurAlpha, tint * tintAlpha, currentTintIntensity);
                            }
                            """.trimIndent(),
                            "content",
                        ) {
                            setFloatUniform("size", size.width, size.height)
                            setColorUniform("tint", tintColor)
                            setFloatUniform("tintIntensity", baseTintIntensity)
                            setFloatUniform("progress", barAlpha)
                            setFloatUniform("fadeStartY", fadeStartY)
                            setFloatUniform("topInset", topInsetPx)
                        }
                    },
                ),
        )
    } else {
        // Android 13 以下安全保底：采用 surfaceContainerHigh 整体容器背景
        Box(
            modifier
                .fillMaxWidth()
                .height(chromeHeight)
                .layerBackdrop(chromeBackdrop)
                .background(
                    MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = barAlpha),
                ),
        )
    }
}
