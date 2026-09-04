package com.niki914.zafiro.app.ui.route

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.niki914.uikit.base.BaseTheme
import com.niki914.uikit.infra.ProvideLiquidScreenContentForPreview
import com.niki914.zafiro.app.ui.content.StartupPageContent
import com.niki914.zafiro.app.ui.model.StartupAssistantUi
import com.niki914.zafiro.app.ui.nav.ProviderPickPage
import com.niki914.zafiro.app.ui.nav.ZafiroPage

@Composable
internal fun StartupPageRoute(
    startupAssistantUi: StartupAssistantUi,
    onPush: (ZafiroPage) -> Unit,
) {
    var isEntering by rememberSaveable { mutableStateOf(false) }

    fun enterNextPage() {
        if (isEntering) return
        isEntering = true
        onPush(ProviderPickPage)
    }

    StartupPageContent(
        onDemoComplete = { enterNextPage() },
    )
}

@Preview(name = "Startup Demo", showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun StartupPageRouteNormalPreview() {
    BaseTheme(darkTheme = false, dynamicColor = false) {
        Surface {
            ProvideLiquidScreenContentForPreview(topPadding = 0.dp) {
                StartupPageContent(
                    onDemoComplete = {},
                )
            }
        }
    }
}
