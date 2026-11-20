package com.niki914.zafiro.app.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.niki914.uikit.button.ZafiroButton
import com.niki914.uikit.control.ZafiroLoadingIndicator
import com.niki914.uikit.infra.shape.G2CardShape
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.screen.AppIconBadge
import com.niki914.zafiro.app.screen.ForegroundVirtualDisplayState
import com.niki914.zafiro.app.screen.VirtualDisplayStage

private val CardCornerRadius = 24.dp
private val CardActionButtonHeight = 46.dp
private val CardBackdropBlurRadius = 24.dp

/**
 * 主对话页虚拟屏卡片：单一形态，按 [VirtualDisplayStage] 切换视觉表达。
 * - AppPicker / Launching：使用 Material secondaryContainer 纯色底；
 * - Running：以极低画质实时缩略帧 + 重度模糊 + Material colorScheme 半透明遮罩作背景。
 */
@Composable
internal fun HomeVirtualDisplayCard(
    state: ForegroundVirtualDisplayState,
    onOpen: () -> Unit,
    onTerminate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stage by state.stage.collectAsState()
    val backdropFrame by state.backdropFrames.collectAsState()
    val colors = MaterialTheme.colorScheme
    val cardShape = remember { G2CardShape(CardCornerRadius) }

    val runningStage = stage as? VirtualDisplayStage.Running
    val liveBackdrop = if (runningStage != null) {
        backdropFrame?.takeIf { !it.isRecycled }
    } else {
        null
    }
    val backdropBitmap = remember(liveBackdrop) { liveBackdrop?.asImageBitmap() }
    val usesBackdrop = runningStage != null && backdropBitmap != null
    val titleColor = if (usesBackdrop) colors.onSurface else colors.onSecondaryContainer

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(cardShape)
            .background(if (usesBackdrop) colors.surfaceContainer else colors.secondaryContainer),
    ) {
        if (backdropBitmap != null) {
            Image(
                bitmap = backdropBitmap,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(CardBackdropBlurRadius),
                contentScale = ContentScale.Crop,
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                colors.surfaceContainer.copy(alpha = 0.56f),
                                colors.surfaceContainer.copy(alpha = 0.76f),
                            ),
                        ),
                    ),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (val current = stage) {
                    is VirtualDisplayStage.Running -> {
                        AppIconBadge(
                            packageName = current.packageName,
                            size = 48.dp,
                            cornerRadius = 14.dp,
                        )
                    }

                    is VirtualDisplayStage.Launching -> {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(G2CardShape(14.dp))
                                .background(colors.onSecondaryContainer.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            ZafiroLoadingIndicator(
                                color = colors.onSecondaryContainer,
                                size = 24.dp,
                                petalSize = 24.dp,
                                strokeWidth = 2.4.dp,
                            )
                        }
                    }

                    is VirtualDisplayStage.AppPicker -> {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(G2CardShape(14.dp))
                                .background(colors.onSecondaryContainer.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Apps,
                                contentDescription = null,
                                tint = colors.onSecondaryContainer,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(14.dp))

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
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ZafiroButton(
                    text = stringResource(R.string.ui_virtual_display_card_open),
                    onClick = onOpen,
                    containerColor = colors.primary,
                    contentColor = colors.onPrimary,
                    buttonHeight = CardActionButtonHeight,
                    modifier = Modifier.weight(1f),
                )
                ZafiroButton(
                    text = stringResource(R.string.ui_virtual_display_card_terminate),
                    onClick = onTerminate,
                    containerColor = colors.errorContainer,
                    contentColor = colors.onErrorContainer,
                    buttonHeight = CardActionButtonHeight,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
