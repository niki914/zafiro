package com.niki914.uikit.infra.preview

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.niki914.uikit.infra.LiquidDialog
import com.niki914.uikit.infra.LiquidScreen
import com.niki914.uikit.infra.component.MaterialTintLiquidButton
import com.niki914.uikit.infra.rememberLiquidScreenState
import com.niki914.uikit.base.BaseTheme
import kotlinx.coroutines.delay

@Composable
private fun LiquidDialogPreviewContent() {
    var visible by remember { mutableStateOf(false) }
    val screenState = _root_ide_package_.com.niki914.uikit.infra.rememberLiquidScreenState(
        title = "",
        showLeftButton = false,
        showRightButton = false,
        showBlurLayer = false,
    )

    LaunchedEffect(Unit) {
        while (true) {
            visible = false
            delay(2_000)
            visible = true
            delay(2_000)
        }
    }

    _root_ide_package_.com.niki914.uikit.infra.LiquidScreen(state = screenState) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            _root_ide_package_.com.niki914.uikit.infra.LiquidDialog(
                visible = visible,
                onDismissRequest = {},
                title = {
                    Text(
                        text = "Bypass compatibility check",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                },
                text = {
                    Text(
                        text = "This action may cause unexpected behavior and should only be used when you fully understand the risk.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                actions = {
                    _root_ide_package_.com.niki914.uikit.infra.component.MaterialTintLiquidButton(
                        text = "Cancel",
                        onClick = {},
                        modifier = Modifier.weight(1f),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    )
                    _root_ide_package_.com.niki914.uikit.infra.component.MaterialTintLiquidButton(
                        text = "Bypass",
                        onClick = {},
                        modifier = Modifier.weight(1f),
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    )
                },
            )
        }
    }
}

@Preview(name = "Liquid Dialog Light", showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun LiquidDialogLightPreview() {
    BaseTheme(darkTheme = false, dynamicColor = false) {
        Surface {
            LiquidDialogPreviewContent()
        }
    }
}

@Preview(
    name = "Liquid Dialog Dark",
    showBackground = true,
    widthDp = 420,
    heightDp = 900,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun LiquidDialogDarkPreview() {
    BaseTheme(darkTheme = true, dynamicColor = false) {
        Surface {
            LiquidDialogPreviewContent()
        }
    }
}
