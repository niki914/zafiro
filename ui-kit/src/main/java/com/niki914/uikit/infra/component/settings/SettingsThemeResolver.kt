package com.niki914.uikit.infra.component.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.niki914.uikit.base.LocalAppDarkTheme
import com.niki914.uikit.base.UiStylePart
import com.niki914.uikit.base.resolvedUiStyle
import com.niki914.uikit.base.ThemeStyle
import com.niki914.uikit.infra.component.settings.item.SettingsTokens
import com.niki914.uikit.infra.shape.G2FieldShape

/**
 * 依据项目既定接缝卡片范式 (对齐 CodeToolBody / UserMessageBubble)：
 * - outerCorner: 严格继承系统全局默认 G2CardShape 连续曲率大圆角 (SettingsTokens.CardCornerRadius，28dp)，严禁随意改动；
 * - innerCorner: 接缝侧微圆角 (3dp)，形成上下贴合的流线接缝；
 * - 首项：上圆下平 (topStart/topEnd = outer, bottomStart/bottomEnd = inner)；
 * - 中项：四周接缝微圆角 (all = inner)；
 * - 末项：上平下圆 (topStart/topEnd = inner, bottomStart/bottomEnd = outer)；
 * - 单项：四周独立大圆角 (all = outer)。
 */
fun resolveConnectedShape(
    index: Int,
    totalCount: Int,
    outerCorner: Dp = SettingsTokens.CardCornerRadius,
    innerCorner: Dp = 3.dp,
): Shape {
    if (totalCount <= 1) return G2FieldShape(outerCorner)
    return when (index) {
        0 -> G2FieldShape(
            topStart = outerCorner,
            topEnd = outerCorner,
            bottomEnd = innerCorner,
            bottomStart = innerCorner,
        )

        totalCount - 1 -> G2FieldShape(
            topStart = innerCorner,
            topEnd = innerCorner,
            bottomEnd = outerCorner,
            bottomStart = outerCorner,
        )

        else -> G2FieldShape(
            topStart = innerCorner,
            topEnd = innerCorner,
            bottomEnd = innerCorner,
            bottomStart = innerCorner,
        )
    }
}

/**
 * M3 Expressive 图标配对色：容器底座色与前景图标色，预先校验 WCAG 对比度。
 */
data class ExpressiveIconTone(
    val containerColor: Color,
    val iconTint: Color,
)

/**
 * 依据昼夜模式构建精心调优的 M3 Expressive 调色板。
 * 10 组涵盖色环主要色相区间的鲜明宝石/粉彩色对，杜绝褪色肉色或灰褐色：
 * - 深色模式：低明度/高辨识度宝石容器底座（Luminance ~ 22-30%）+ 浅柔和高对比图标（Luminance ~ 85-92%）；
 * - 浅色模式：鲜明粉彩底座（Luminance ~ 85-92%）+ 深沉高对比图标（Luminance ~ 15-25%）。
 */
@Composable
internal fun rememberExpressivePalette(isDark: Boolean): List<ExpressiveIconTone> {
    return remember(isDark) {
        if (isDark) {
            listOf(
                // 1. 蔚蓝 (Blue - Music Management 同款)
                ExpressiveIconTone(Color(0xFF004B87), Color(0xFFB8D8FF)),
                // 2. 玫粉 (Rose - Appearance 同款)
                ExpressiveIconTone(Color(0xFF75213E), Color(0xFFFFB2C5)),
                // 3. 梅紫 (Plum / Purple - Playback 同款)
                ExpressiveIconTone(Color(0xFF562577), Color(0xFFE2B8FF)),
                // 4. 青海 (Teal - Behavior 同款)
                ExpressiveIconTone(Color(0xFF004F56), Color(0xFF79D5DF)),
                // 5. 琥珀 (Amber / Gold)
                ExpressiveIconTone(Color(0xFF613B00), Color(0xFFFFBA53)),
                // 6. 翠绿 (Emerald / Mint)
                ExpressiveIconTone(Color(0xFF135B2E), Color(0xFF86E0A6)),
                // 7. 珊瑚 (Coral / Tangerine - 鲜活亮橙红，拒绝土褐)
                ExpressiveIconTone(Color(0xFF772810), Color(0xFFFFB5A0)),
                // 8. 靛青 (Indigo / Cobalt)
                ExpressiveIconTone(Color(0xFF2B3A8E), Color(0xFFBDC7FF)),
                // 9. 绯红 (Crimson / Ruby)
                ExpressiveIconTone(Color(0xFF751822), Color(0xFFFFB3B8)),
                // 10. 青柠 (Lime / Olive)
                ExpressiveIconTone(Color(0xFF424B00), Color(0xFFD3E066)),
            )
        } else {
            listOf(
                // 1. 蔚蓝 (Blue)
                ExpressiveIconTone(Color(0xFFCCE5FF), Color(0xFF004B87)),
                // 2. 玫粉 (Rose)
                ExpressiveIconTone(Color(0xFFFFD1DE), Color(0xFF8C1D40)),
                // 3. 梅紫 (Plum / Purple)
                ExpressiveIconTone(Color(0xFFEADBFF), Color(0xFF622A88)),
                // 4. 青海 (Teal)
                ExpressiveIconTone(Color(0xFFBCEEEF), Color(0xFF00535B)),
                // 5. 琥珀 (Amber / Gold)
                ExpressiveIconTone(Color(0xFFFFE0A8), Color(0xFF7A4B00)),
                // 6. 翠绿 (Emerald / Mint)
                ExpressiveIconTone(Color(0xFFC4F2D2), Color(0xFF136433)),
                // 7. 珊瑚 (Coral / Tangerine - 鲜亮珊瑚粉橙)
                ExpressiveIconTone(Color(0xFFFFD7CA), Color(0xFF8E2E14)),
                // 8. 靛青 (Indigo / Cobalt)
                ExpressiveIconTone(Color(0xFFD4DBFF), Color(0xFF283994)),
                // 9. 绯红 (Crimson / Ruby)
                ExpressiveIconTone(Color(0xFFFFD8D6), Color(0xFF901824)),
                // 10. 青柠 (Lime / Olive)
                ExpressiveIconTone(Color(0xFFE4F0A0), Color(0xFF4A5500)),
            )
        }
    }
}

/**
 * 基于互质步长（Stride = 3, PaletteSize = 10）的色彩拾取器：
 * - 纯确定性（Steady）：给定 groupKey 恒定返回相同序列，屏幕旋转、重组绝不突变；
 * - 绝对不撞色（Anti-collision）：同一组内相邻两项步长恒为 3（在色环上跳跃约 108°），相邻碰撞率严格为 0%；
 * - 10 项以内互斥：因为 gcd(3, 10) = 1，10 项以内任意两项绝不相同。
 */
@Composable
fun resolveExpressiveIconTone(
    groupKey: String,
    index: Int = 0,
    isDark: Boolean = LocalAppDarkTheme.current,
): ExpressiveIconTone {
    val palette = rememberExpressivePalette(isDark)
    var h = groupKey.hashCode()
    h = h xor (h ushr 16)
    h *= 0x85ebca6b.toInt()
    h = h xor (h ushr 13)
    val base = h and 0x7FFFFFFF
    val colorIndex = (base + index * 3) % palette.size
    return palette[colorIndex]
}

/**
 * 设置项图标多模色彩分流器：
 * - [ThemeStyle.LiquidGlass]: 中性单色底座 (surfaceContainerHighest) + 中性图标 (onSurfaceVariant)；
 * - [ThemeStyle.Material3]: 统一 M3 品牌色调 (primaryContainer) + 对比图标 (onPrimaryContainer)；
 * - [ThemeStyle.Material3Expressive]: 依据 key 与 index 基于互质步长（Stride = 3）绝对不撞色地映射。
 */
@Composable
fun resolveSettingIconColors(
    key: String,
    index: Int = 0,
): Pair<Color, Color> {
    val themeStyle = resolvedUiStyle(UiStylePart.Settings)
    val isDark = LocalAppDarkTheme.current
    val colorScheme = MaterialTheme.colorScheme

    return when (themeStyle) {
        ThemeStyle.LiquidGlass -> {
            colorScheme.surfaceContainerHighest to colorScheme.onSurfaceVariant
        }

        ThemeStyle.Material3 -> {
            colorScheme.primaryContainer to colorScheme.onPrimaryContainer
        }

        ThemeStyle.Material3Expressive -> {
            val tone = resolveExpressiveIconTone(groupKey = key, index = index, isDark = isDark)
            tone.containerColor to tone.iconTint
        }
    }
}

/**
 * 设置项图标色彩分流包装层：从 [SettingsRowSpec] 中提取 key 驱动。
 */
@Composable
fun resolveSettingIconColors(
    row: SettingsRowSpec,
    index: Int = 0,
    groupKey: String? = null,
): Pair<Color, Color> {
    val effectiveKey = groupKey ?: row.id?.substringBeforeLast('.') ?: row.id ?: row.title
    return resolveSettingIconColors(key = effectiveKey, index = index)
}
