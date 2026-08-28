package com.niki914.zafiro.app.ui.content

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.niki914.zafiro.app.R
import com.niki914.uikit.infra.component.TintLiquidButton
import com.niki914.uikit.infra.liquidScreenTopPadding

data class SelectionOption(
    val id: String,
    val title: String,
    @DrawableRes val leadingIconRes: Int,
    val tintLeadingIcon: Boolean = true,
    val darkContainerColor: Color = Color.Unspecified,
    val lightContainerColor: Color = Color.Unspecified,
    val darkContentColor: Color = Color.Unspecified,
    val lightContentColor: Color = Color.Unspecified,
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
            .padding(horizontal = 20.dp, vertical = 24.dp),
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

        options.forEach { option ->
            TintLiquidButton(
                text = option.title,
                leadingIconRes = option.leadingIconRes,
                trailingIcon = Icons.AutoMirrored.Filled.ArrowForward,
                tintLeadingIcon = option.tintLeadingIcon,
                buttonHeight = 52.dp,
                darkContainerColor = option.darkContainerColor,
                lightContainerColor = option.lightContainerColor,
                darkContentColor = option.darkContentColor,
                lightContentColor = option.lightContentColor,
                onClick = option.onClick,
            )
        }
    }
}
