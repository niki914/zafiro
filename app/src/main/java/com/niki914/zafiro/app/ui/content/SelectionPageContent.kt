package com.niki914.zafiro.app.ui.content

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.niki914.zafiro.app.R
import com.niki914.uikit.base.LocalAppDarkTheme
import com.niki914.uikit.infra.component.SettingsGroupCard
import com.niki914.uikit.infra.component.SettingsItemDivider
import com.niki914.uikit.infra.component.SettingsListItem
import com.niki914.uikit.infra.liquidScreenTopPadding

data class SelectionOption(
    val id: String,
    val title: String,
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
    /** 选中态：trailing 渲染对勾，不渲染 chevron。 */
    val selected: Boolean = false,
    val onClick: () -> Unit,
)

/** 单个选项行：56dp 圆形 leading（色板或图标）+ 选中对勾。 */
@Composable
internal fun SelectionOptionRow(
    option: SelectionOption,
    isDarkTheme: Boolean,
) {
    val brandContainer = if (isDarkTheme) option.darkContainerColor else option.lightContainerColor
    val brandContent = if (isDarkTheme) option.darkContentColor else option.lightContentColor
    SettingsListItem(
        title = option.title,
        showChevron = !option.selected,
        enabled = true,
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
                            ?: MaterialTheme.colorScheme.surfaceContainerHighest
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
                            brandContent != null -> brandContent
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(24.dp),
                    )
                } else if (iconVector != null) {
                    Icon(
                        imageVector = iconVector,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
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

/** 分组卡片形式的选项列表：组内 divider 分隔。 */
@Composable
internal fun SelectionGroupCard(
    options: List<SelectionOption>,
    isDarkTheme: Boolean,
    modifier: Modifier = Modifier,
) {
    SettingsGroupCard(modifier = modifier) {
        options.forEachIndexed { index, option ->
            SelectionOptionRow(option, isDarkTheme)
            if (index != options.lastIndex) {
                SettingsItemDivider()
            }
        }
    }
}

@Composable
fun SelectionPageContent(
    options: List<SelectionOption>,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = liquidScreenTopPadding())
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            text = stringResource(R.string.ui_onboard_provider_pick_description),
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = 18.sp,
                lineHeight = 28.sp,
            ),
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SelectionGroupCard(options = options, isDarkTheme = LocalAppDarkTheme.current)
    }
}
