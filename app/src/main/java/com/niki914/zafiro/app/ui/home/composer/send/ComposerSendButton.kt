package com.niki914.zafiro.app.ui.home.composer.send

import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Icon
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
import com.niki914.uikit.button.ZafiroActionButton
import com.niki914.uikit.control.ZafiroLoadingIndicator
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.home.composer.drawContextRingOverlay

@Composable
internal fun ComposerSendButton(
    isGenerating: Boolean,
    contextProgress: Float?,
    onSendClick: () -> Unit,
    onStopClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
                ZafiroLoadingIndicator(
                    modifier = Modifier.clearAndSetSemantics {
                        contentDescription = "Stop Button"
                    },
                    color = contentColor,
                    size = 22.dp,
                    petalSize = 28.dp,
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_up),
                    contentDescription = "Send Button",
                )
            }
        }
    }
}
