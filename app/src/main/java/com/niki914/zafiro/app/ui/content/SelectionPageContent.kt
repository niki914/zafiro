package com.niki914.zafiro.app.ui.content

import android.content.Intent
import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.niki914.uikit.base.LocalAppDarkTheme
import com.niki914.uikit.infra.component.SettingsGroupCard
import com.niki914.uikit.infra.component.SettingsListItem
import com.niki914.uikit.infra.component.SettingsListPageContent
import com.niki914.uikit.infra.component.settings.item.SettingsTokens
import com.niki914.uikit.infra.component.settings.resolveSettingIconColors
import com.niki914.uikit.infra.shape.G2CardShape
import com.niki914.zafiro.api.util.runCatchingCancellable
import com.niki914.zafiro.app.R
import androidx.core.net.toUri

private const val BYOK_URL = "https://www.ibm.com/think/topics/byok"

data class SelectionOption(
    val id: String,
    val title: String,
    val summary: String? = null,
    @DrawableRes val leadingIconRes: Int? = null,
    val tintLeadingIcon: Boolean = true,
    /** 非空时 leading 渲染为该颜色的圆形色板（优先于 leadingIconRes）。 */
    val leadingSwatchColor: Color? = null,
    /** Vector 图标（与 leadingIconRes 二选一）。 */
    val leadingIconVector: ImageVector? = null,
    val darkContainerColor: Color? = null,
    val lightContainerColor: Color? = null,
    val darkContentColor: Color? = null,
    val lightContentColor: Color? = null,
    /** 非空时优先于 contentColor 作为图标 tint 色。 */
    val darkIconColor: Color? = null,
    val lightIconColor: Color? = null,
    /** 选中态：trailing 渲染对勾，不渲染 chevron。 */
    val selected: Boolean = false,
    val onClick: () -> Unit,
)

/** 单个选项行：56dp 圆形 leading（色板或图标）+ 选中对勾。 */
@Composable
internal fun SelectionOptionRow(
    option: SelectionOption,
    isDarkTheme: Boolean = LocalAppDarkTheme.current,
    shape: Shape = G2CardShape(SettingsTokens.CardCornerRadius),
    isStandaloneCard: Boolean = false,
    index: Int = 0,
) {
    val brandContainer = if (isDarkTheme) option.darkContainerColor else option.lightContainerColor
    val brandContent = if (isDarkTheme) option.darkContentColor else option.lightContentColor
    val brandIcon = if (isDarkTheme) option.darkIconColor else option.lightIconColor

    val (defaultContainer, defaultTint) = resolveSettingIconColors(key = "selection_option", index = index)

    SettingsListItem(
        title = option.title,
        summary = option.summary,
        showChevron = !option.selected,
        enabled = true,
        shape = shape,
        isStandaloneCard = isStandaloneCard,
        trailingContent = if (option.selected) {
            {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            }
        } else {
            null
        },
        leadingContent = {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(
                        option.leadingSwatchColor
                            ?: brandContainer
                            ?: defaultContainer
                    ),
            ) {
                val iconRes = option.leadingIconRes
                val iconVector = option.leadingIconVector
                if (iconRes != null) {
                    Icon(
                        painter = painterResource(iconRes),
                        contentDescription = null,
                        tint = when {
                            !option.tintLeadingIcon -> Color.Unspecified
                            brandIcon != null -> brandIcon
                            brandContent != null -> brandContent
                            else -> defaultTint
                        },
                        modifier = Modifier.size(24.dp),
                    )
                } else if (iconVector != null) {
                    Icon(
                        imageVector = iconVector,
                        contentDescription = null,
                        tint = defaultTint,
                        modifier = Modifier.size(24.dp),
                    )
                } else if (option.leadingSwatchColor != null && option.selected) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        },
        onClick = option.onClick,
    )
}

/** 分组卡片形式的选项列表：在 M3 / Expressive 下自动展开为 3dp 接缝卡片，在 Liquid 下保持单体卡片 + 分割线。 */
@Composable
internal fun SelectionGroupCard(
    options: List<SelectionOption>,
    modifier: Modifier = Modifier,
    isDarkTheme: Boolean = LocalAppDarkTheme.current,
) {
    SettingsGroupCard(
        items = options,
        modifier = modifier,
    ) { option, shape, isStandalone, index ->
        SelectionOptionRow(
            option = option,
            isDarkTheme = isDarkTheme,
            shape = shape,
            isStandaloneCard = isStandalone,
            index = index,
        )
    }
}

@Composable
private fun ProviderPickHeader() {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val style = MaterialTheme.typography.titleMedium.copy(
        fontSize = 16.sp,
        lineHeight = 28.sp,
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.ui_onboard_provider_pick_description),
            style = style,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.ui_onboard_provider_pick_byok_hint),
            style = style,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Button,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                        val intent = Intent(Intent.ACTION_VIEW, BYOK_URL.toUri()).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        runCatchingCancellable { context.startActivity(intent) }
                    },
                )
                .padding(vertical = 4.dp),
        )
    }
}

@Composable
fun SelectionPageContent(
    options: List<SelectionOption>,
) {
    SettingsListPageContent {
        ProviderPickHeader()
        SelectionGroupCard(options = options, isDarkTheme = LocalAppDarkTheme.current)
    }
}
