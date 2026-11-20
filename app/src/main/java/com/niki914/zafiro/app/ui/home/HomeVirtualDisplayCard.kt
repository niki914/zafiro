package com.niki914.zafiro.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.niki914.uikit.ambient.AmbientColorExtractor
import com.niki914.uikit.base.LocalAppDarkTheme
import com.niki914.uikit.control.ZafiroLoadingIndicator
import com.niki914.uikit.infra.shape.G2CardShape
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.screen.AppIconBadge
import com.niki914.zafiro.app.screen.AppIconState
import com.niki914.zafiro.app.screen.ForegroundVirtualDisplayState
import com.niki914.zafiro.app.screen.VirtualDisplayStage
import com.niki914.zafiro.app.screen.rememberAppIconState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

private val DockIconSize = 34.dp
private val DockIconCornerRadius = 10.dp
private val DockSlotWidth = 48.dp
private val DockActionTouchHeight = 36.dp
private val DockActionIconSize = 20.dp

private val AmbientPaletteCache = ConcurrentHashMap<String, List<Color>>()

/**
 * 按当前虚拟屏阶段解析 Composer 顶部光斑色板：
 * - Running：异步从应用图标提取 4 个 HCT 调和主色并按包名 + 深浅色缓存；
 * - AppPicker / Launching：使用 secondaryContainer 派生调和色，保持切态丝滑过渡。
 */
@Composable
internal fun rememberVirtualDisplayAmbientColors(
    state: ForegroundVirtualDisplayState?,
): List<Color> {
    if (state == null) return emptyList()
    val stage by state.stage.collectAsState()
    val isDarkTheme = LocalAppDarkTheme.current
    val fallbackSeed = MaterialTheme.colorScheme.secondaryContainer
    val standbyPalette = remember(fallbackSeed, isDarkTheme) {
        AmbientColorExtractor.fromSeedColor(fallbackSeed, isDarkTheme)
    }

    val runningPackage = (stage as? VirtualDisplayStage.Running)?.packageName
    val iconState = rememberAppIconState(runningPackage)
    val cacheKey = runningPackage?.let { "$it|$isDarkTheme" }

    var resolved by remember(cacheKey, standbyPalette) {
        mutableStateOf(
            cacheKey?.let { AmbientPaletteCache[it] } ?: standbyPalette,
        )
    }

    LaunchedEffect(cacheKey, iconState, fallbackSeed, isDarkTheme) {
        if (cacheKey == null) {
            resolved = standbyPalette
            return@LaunchedEffect
        }
        AmbientPaletteCache[cacheKey]?.let { cached ->
            resolved = cached
            return@LaunchedEffect
        }
        val ready = iconState as? AppIconState.Ready ?: return@LaunchedEffect
        val extracted = withContext(Dispatchers.Default) {
            AmbientColorExtractor.extractFromBitmap(
                bitmap = ready.bitmap.asAndroidBitmap(),
                isDarkTheme = isDarkTheme,
                fallbackColor = fallbackSeed,
            )
        }
        AmbientPaletteCache[cacheKey] = extracted
        resolved = extracted
    }

    return resolved
}

/**
 * 挂在 Composer 顶槽的单行紧凑虚拟屏 Dock：
 * - 与下方 ZafiroActionButton 网格严格对齐：外边距 24dp（含顶部与底栏 24dp 对等），
 *   左右 48dp 槽位中轴距边缘 48dp（对齐加号与发送按钮圆心），应用名起点 84dp（对齐输入文本起点）；
 * - 点击左侧图标或应用名直接打开悬浮窗，右侧主色电源键无背景无波纹触发终止确认。
 */
@Composable
internal fun HomeVirtualDisplayDock(
    state: ForegroundVirtualDisplayState,
    onOpen: () -> Unit,
    onTerminate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stage by state.stage.collectAsState()
    val colors = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                        onOpen()
                    },
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.width(DockSlotWidth),
                contentAlignment = Alignment.Center,
            ) {
                when (val current = stage) {
                    is VirtualDisplayStage.Running -> {
                        AppIconBadge(
                            packageName = current.packageName,
                            size = DockIconSize,
                            cornerRadius = DockIconCornerRadius,
                        )
                    }

                    is VirtualDisplayStage.Launching -> {
                        Box(
                            modifier = Modifier
                                .size(DockIconSize)
                                .clip(G2CardShape(DockIconCornerRadius))
                                .background(colors.onSurface.copy(alpha = 0.10f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            ZafiroLoadingIndicator(
                                color = colors.onSurface,
                                size = 18.dp,
                                petalSize = 18.dp,
                                strokeWidth = 2.dp,
                            )
                        }
                    }

                    is VirtualDisplayStage.AppPicker -> {
                        Box(
                            modifier = Modifier
                                .size(DockIconSize)
                                .clip(G2CardShape(DockIconCornerRadius))
                                .background(colors.onSurface.copy(alpha = 0.10f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Apps,
                                contentDescription = null,
                                tint = colors.onSurface,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Text(
                text = when (val current = stage) {
                    is VirtualDisplayStage.Running -> current.appName
                    is VirtualDisplayStage.Launching -> stringResource(
                        R.string.ui_virtual_display_launching_app,
                        current.appName,
                    )

                    is VirtualDisplayStage.AppPicker -> stringResource(
                        R.string.ui_virtual_display_pick_app,
                    )
                },
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(modifier = Modifier.width(4.dp))

        BorderlessDockIconAction(
            icon = Icons.Rounded.PowerSettingsNew,
            contentDescription = "Terminate virtual display",
            tint = colors.primary,
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                onTerminate()
            },
        )
    }
}

@Composable
private fun BorderlessDockIconAction(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(width = DockSlotWidth, height = DockActionTouchHeight)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(DockActionIconSize),
        )
    }
}
