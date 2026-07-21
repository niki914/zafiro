package com.niki914.nexus.agentic.app.ui.infra.preview

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.niki914.nexus.agentic.app.ui.infra.ConfirmationLiquidDialog
import com.niki914.nexus.agentic.app.ui.infra.LiquidScreen
import com.niki914.nexus.agentic.app.ui.infra.rememberLiquidScreenState
import com.niki914.nexus.base.BaseTheme

@Composable
private fun ConfirmationLiquidDialogPreviewContent() {
    val screenState = rememberLiquidScreenState(
        title = "",
        showLeftButton = false,
        showRightButton = false,
        showBlurLayer = false,
    )

    LiquidScreen(state = screenState) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            ConfirmationLiquidDialog(
                visible = true,
                onDismissRequest = {},
                title = "Hi,\niOS",
                text = "This is,\nthe\ncontent",
                negativeButtonText = "Cancel",
                positiveButtonText = "Bypass",
                onNegativeClick = {},
                onPositiveClick = {},
            )
        }
    }
}

@Preview(
    name = "Confirmation Liquid Dialog Light",
    showBackground = true,
    widthDp = 420,
    heightDp = 900
)
@Composable
private fun ConfirmationLiquidDialogLightPreview() {
    BaseTheme(darkTheme = false, dynamicColor = false) {
        Surface {
            ConfirmationLiquidDialogPreviewContent()
        }
    }
}

@Preview(
    name = "Confirmation Liquid Dialog Dark",
    showBackground = true,
    widthDp = 420,
    heightDp = 900,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ConfirmationLiquidDialogDarkPreview() {
    BaseTheme(darkTheme = true, dynamicColor = false) {
        Surface {
            ConfirmationLiquidDialogPreviewContent()
        }
    }
}
