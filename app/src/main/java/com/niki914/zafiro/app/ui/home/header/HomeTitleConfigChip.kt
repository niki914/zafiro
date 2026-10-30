package com.niki914.zafiro.app.ui.home.header

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import com.niki914.uikit.infra.shape.G2FieldShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.niki914.uikit.base.LocalAppDarkTheme
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.model.home.HomeChatUiState

@Composable
internal fun HomeTitleConfigChip(
    uiStateProvider: () -> HomeChatUiState,
    onChipClick: () -> Unit,
    onAnchorBoundsChanged: (Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState = uiStateProvider()
    val isDarkTheme = LocalAppDarkTheme.current
    val activeConfig = uiState.displayConfig
    val titleText = activeConfig?.name ?: stringResource(R.string.app_name)
    val hasConfigs = uiState.savedConfigs.isNotEmpty()

    val latestOnAnchorBoundsChanged by rememberUpdatedState(onAnchorBoundsChanged)
    val latestOnChipClick by rememberUpdatedState(onChipClick)
    var chipCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }

    Row(
        modifier = modifier
            .onGloballyPositioned { coordinates ->
                chipCoordinates = coordinates
                if (coordinates.isAttached) {
                    latestOnAnchorBoundsChanged(coordinates.boundsInRoot())
                }
            }
            .clip(G2FieldShape(16.dp))
            .clickable(
                enabled = hasConfigs,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    chipCoordinates?.takeIf { it.isAttached }?.boundsInRoot()?.let { bounds ->
                        latestOnAnchorBoundsChanged(bounds)
                    }
                    latestOnChipClick()
                },
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = titleText,
            style = TextStyle(
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isDarkTheme) Color.White else Color.Black,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (hasConfigs) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (isDarkTheme) Color.White.copy(alpha = 0.7f) else Color.Black.copy(alpha = 0.7f),
            )
        }
    }
}
