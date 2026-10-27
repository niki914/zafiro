package com.niki914.uikit.infra

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow

/**
 * 集中管理应用内所有液体毛玻璃（Backdrop Glass）的光学、投影与材质参数。
 */
@Immutable
data class LiquidGlassStyle(
    /** 模糊半径（根据深色/浅色模式自适应）。 */
    val blurRadius: (isDarkTheme: Boolean) -> Dp,
    /** 边缘折射高。 */
    val refractionHeight: Dp = 6.dp,
    /** 边缘折射量。默认为高度的一半。支持在 Scope 内使用 Dp 转换。 */
    val refractionAmount: BackdropEffectScope.(size: Size) -> Float = { it.height / 2f },
    /** 是否开启景深增强。 */
    val depthEffect: Boolean = true,
    /** 是否开启边缘色散（色差）。 */
    val chromaticAberration: Boolean = true,
    /** 是否开启色彩增益 / Vibrancy。 */
    val vibrancy: Boolean = true,
    /** 亮度增强（默认为 0，浅色模式下可设如 0.2f 提升通透感）。 */
    val brightness: (isDarkTheme: Boolean) -> Float = { 0f },
    /** 对比度增益。 */
    val contrast: (isDarkTheme: Boolean) -> Float = { 1f },
    /** 饱和度增益（默认为 1.5f）。 */
    val saturation: (isDarkTheme: Boolean) -> Float = { 1.5f },
    /** 表面底色（受深浅色模式与 Material ColorScheme 驱动）。 */
    val surfaceColor: (isDarkTheme: Boolean, colorScheme: ColorScheme) -> Color,
    /** 环境高光透明度。 */
    val highlightAlpha: (isDarkTheme: Boolean) -> Float = { if (it) 1f else 0.4f },
    /** 自定义高光实现（若提供则优先使用）。 */
    val customHighlight: ((isDarkTheme: Boolean) -> Highlight)? = null,
    /** 自定义阴影实现（若提供则优先使用）。 */
    val customShadow: ((isDarkTheme: Boolean) -> Shadow)? = null,
    /** 投影半径。 */
    val shadowRadius: Dp = 12.dp,
    /** 投影颜色。 */
    val shadowColor: (isDarkTheme: Boolean) -> Color = { isDark ->
        Color.Black.copy(if (isDark) 0.08f else 0.2f)
    },
) {
    /**
     * 在 BackdropEffectScope 内直接应用配置的光学效果（vibrancy、colorControls、blur、lens）。
     */
    fun applyEffects(scope: BackdropEffectScope, isDarkTheme: Boolean) {
        with(scope) {
            if (vibrancy) {
                val b = brightness(isDarkTheme)
                val c = contrast(isDarkTheme)
                val s = saturation(isDarkTheme)
                if (b != 0f || c != 1f || s != 1.5f) {
                    colorControls(brightness = b, contrast = c, saturation = s)
                } else {
                    vibrancy()
                }
            }
            blur(blurRadius(isDarkTheme).toPx())
            lens(
                refractionHeight = refractionHeight.toPx(),
                refractionAmount = refractionAmount(size),
                depthEffect = depthEffect,
                chromaticAberration = chromaticAberration,
            )
        }
    }

    /**
     * 生成环境边缘高光（Highlight）。
     */
    fun highlight(isDarkTheme: Boolean): Highlight {
        return customHighlight?.invoke(isDarkTheme)
            ?: Highlight.Ambient.copy(alpha = highlightAlpha(isDarkTheme))
    }

    /**
     * 生成柔和环境阴影（Shadow）。
     */
    fun shadow(isDarkTheme: Boolean): Shadow {
        return customShadow?.invoke(isDarkTheme)
            ?: Shadow(
                radius = shadowRadius,
                color = shadowColor(isDarkTheme),
            )
    }
}

/**
 * 应用内各组件类别的液体玻璃标准预设。
 */
object LiquidGlassPresets {
    /**
     * 模态弹窗（LiquidDialog）预设：
     * 与 Catalog Dialog 光学规格 1:1 对齐（24dp 折射高、48dp 折射量、16dp/8dp 模糊、Plain 高光），
     * 同时保留符合预期的 76% surfaceContainerHigh 表面底色与沉浸 Hue 色相染色。
     */
    val Dialog = LiquidGlassStyle(
        blurRadius = { isDark -> if (isDark) 8.dp else 12.dp },
        refractionHeight = 24.dp,
        refractionAmount = { 48.dp.toPx() },
        depthEffect = true,
        chromaticAberration = false,
        brightness = { isDark -> if (isDark) 0f else 0.2f },
        customHighlight = { Highlight.Plain },
        surfaceColor = { _, colorScheme -> colorScheme.surfaceContainerHigh.copy(alpha = 0.76f) },
        shadowRadius = 16.dp,
        shadowColor = { isDark -> Color.Black.copy(if (isDark) 0.16f else 0.24f) },
    )

    /**
     * 浮动菜单（LiquidPopover）预设：
     * 适中模糊（深色 8dp / 浅色 12dp），轻薄白/灰底色，与顶栏按钮风格一致。
     */
    val Popover = LiquidGlassStyle(
        blurRadius = { isDark -> if (isDark) 20.dp else 24.dp },
        refractionHeight = 2.dp,
        refractionAmount = { it.height / 2f },
        surfaceColor = { isDark, _ ->
            if (isDark) Color(0xFFAFAFAF).copy(alpha = 0.15f)
            else Color.White.copy(alpha = 0.22f)
        },
        highlightAlpha = { isDark -> if (isDark) 1f else 0.4f },
        shadowRadius = 20.dp,
        shadowColor = { isDark -> Color.Black.copy(if (isDark) 0.08f else 0.2f) },
    )

    /**
     * 动作按钮（ActionBarButton）预设：
     * 精致现代质感，4dp 局部模糊，6dp 边缘折射，搭配细白边 Default 高光（0.5dp 细白轮廓线）与沉稳 surfaceContainerHigh 底色，
     * 与 ZafiroSearchBar 等顶栏组件达成 100% 同构一体化视觉。
     */
    val Button = LiquidGlassStyle(
        blurRadius = { 4.dp },
        refractionHeight = 6.dp,
        refractionAmount = { it.height / 2f },
        surfaceColor = { _, colorScheme -> colorScheme.surfaceContainerHigh.copy(alpha = 0.64f) },
        customHighlight = { Highlight.Default },
        shadowRadius = 12.dp,
        shadowColor = { isDark -> Color.Black.copy(if (isDark) 0.08f else 0.2f) },
    )

    /**
     * 普通输入框（LiquidTextField / LiquidSecretTextField）标准预设：
     * 精致卡片内嵌质感，2dp 极浅清晰模糊，12dp 边缘高折射与 24dp 折射量，
     * 搭配带细白边的 Default 高光（0.5dp 细白轮廓线）与标准阴影。
     */
    val TextField = LiquidGlassStyle(
        blurRadius = { 2.dp },
        refractionHeight = 12.dp,
        refractionAmount = { 24.dp.toPx() },
        depthEffect = true,
        chromaticAberration = true,
        surfaceColor = { _, colorScheme -> colorScheme.surfaceContainerHigh.copy(alpha = 0.64f) },
        customHighlight = { Highlight.Default },
        customShadow = { Shadow.Default },
    )

    /**
     * 主页输入框条（LiquidTextFieldContainer / ComposerBar）预设：
     * 深色 8dp / 浅色 12dp 模糊，柔和雾化下方流经的聊天气泡，保持输入框内文字高度可读。
     */
    val ComposerBar = LiquidGlassStyle(
        blurRadius = { isDark -> if (isDark) 12.dp else 16.dp },
        refractionHeight = 4.dp,
        refractionAmount = { it.height / 2f },
        surfaceColor = { isDark, colorScheme ->
            colorScheme.surfaceContainerHigh.copy(alpha = if (isDark) 0.72f else 0.64f)
        },
        highlightAlpha = { isDark -> if (isDark) 0.3f else 0.15f },
        shadowRadius = 20.dp,
        shadowColor = { isDark -> Color.Black.copy(if (isDark) 0.06f else 0.12f) },
    )

    /**
     * 滚底悬浮按钮（ScrollToBottom）预设。
     */
    val ScrollToBottom = LiquidGlassStyle(
        blurRadius = { isDark -> if (isDark) 12.dp else 16.dp },
        refractionHeight = 4.dp,
        refractionAmount = { it.height / 2f },
        surfaceColor = { isDark, colorScheme ->
            colorScheme.surfaceContainerHigh.copy(alpha = if (isDark) 0.72f else 0.64f)
        },
        highlightAlpha = { isDark -> if (isDark) 0.3f else 0.15f },
        shadowRadius = 20.dp,
        shadowColor = { isDark -> Color.Black.copy(if (isDark) 0.06f else 0.12f) },
    )

    /**
     * 顶栏动作条背景（LiquidTopBar / ActionBar）预设：
     * Progressive Blur 渐变毛玻璃，由状态栏向下至顶栏底部平滑淡出，
     * 消除顶栏与内容区的硬分割线。
     */
    val TopBar = LiquidTopBarStyle()
}

/**
 * 顶栏动作条（LiquidTopBar / ActionBar）渐变毛玻璃样式规范。
 */
@Immutable
data class LiquidTopBarStyle(
    val blurRadius: (isDarkTheme: Boolean) -> Dp = { 16.dp },
    val tintIntensity: (isDarkTheme: Boolean) -> Float = { if (it) 0.72f else 0.65f },
)


