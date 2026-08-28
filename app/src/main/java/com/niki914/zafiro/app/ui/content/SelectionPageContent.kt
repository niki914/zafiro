package com.niki914.zafiro.app.ui.content

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.niki914.zafiro.app.R
import com.niki914.uikit.infra.component.SettingsGroupCard
import com.niki914.uikit.infra.component.SettingsItemDivider
import com.niki914.uikit.infra.component.SettingsListItem
import com.niki914.uikit.infra.liquidScreenTopPadding

data class SelectionOption(
    val id: String,
    val title: String,
    @DrawableRes val leadingIconRes: Int,
    val tintLeadingIcon: Boolean = true,
    val darkContainerColor: Color? = null,
    val lightContainerColor: Color? = null,
    val darkContentColor: Color? = null,
    val lightContentColor: Color? = null,
    val onClick: () -> Unit,
)

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

        SettingsGroupCard {
            val isDarkTheme = isSystemInDarkTheme()
            options.forEachIndexed { index, option ->
                val brandContainer = if (isDarkTheme) option.darkContainerColor else option.lightContainerColor
                val brandContent = if (isDarkTheme) option.darkContentColor else option.lightContentColor
                SettingsListItem(
                    title = option.title,
                    showChevron = true,
                    enabled = true,
                    leadingContent = {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(
                                    brandContainer
                                        ?: MaterialTheme.colorScheme.surfaceContainerHighest
                                ),
                        ) {
                            Icon(
                                painter = painterResource(option.leadingIconRes),
                                contentDescription = null,
                                tint = when {
                                    !option.tintLeadingIcon -> Color.Unspecified
                                    brandContent != null -> brandContent
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    },
                    onClick = option.onClick,
                )
                if (index != options.lastIndex) {
                    SettingsItemDivider()
                }
            }
        }
    }
}
