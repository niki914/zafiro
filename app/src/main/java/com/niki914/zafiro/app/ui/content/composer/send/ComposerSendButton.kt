package com.niki914.zafiro.app.ui.content.composer.send

import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.niki914.uikit.base.LocalThemeStyle
import com.niki914.uikit.base.ThemeStyle
import com.niki914.uikit.button.ZafiroActionButton
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.content.composer.drawContextRingOverlay

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ComposerSendButton(
    isGenerating: Boolean,
    contextProgress: Float?,
    onSendClick: () -> Unit,
    onStopClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val themeStyle = LocalThemeStyle.current
    val isExpressive = themeStyle == ThemeStyle.Material3Expressive
    val contentColor = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    val buttonDiameter = with(density) { 48.sp.toDp() }

    val ringModifier = Modifier.drawContextRingOverlay(
        progress = contextProgress,
        buttonDiameter = buttonDiameter,
    )

    CompositionLocalProvider(LocalContentColor provides contentColor) {
        ZafiroActionButton(
            onClick = if (isGenerating) onStopClick else onSendClick,
            modifier = modifier
                .offset(x = 6.dp)
                .then(ringModifier),
            enabled = true,
        ) {
            if (isGenerating) {
                if (isExpressive) {
                    LoadingIndicator(
                        modifier = Modifier
                            .size(28.dp)
                            .clearAndSetSemantics {
                                contentDescription = "Stop Button"
                            },
                        color = contentColor,
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(22.dp)
                            .clearAndSetSemantics {
                                contentDescription = "Stop Button"
                            },
                        color = contentColor,
                        strokeWidth = 2.dp,
                    )
                }
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_up),
                    contentDescription = "Send Button",
                )
            }
        }
    }
}
