package com.niki914.zafiro.app.ui.nexus

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.niki914.zafiro.app.ui.nexus.nav.PageTitleSpec
import com.niki914.zafiro.app.ui.nexus.nav.TopBarActionSpec

data class PageChromeContribution(
    val titleSpec: PageTitleSpec? = null,
    val leftAction: TopBarActionSpec? = null,
    val rightAction: TopBarActionSpec? = null,
    val menuItems: List<PageChromeMenuItem> = emptyList(),
    val backHandler: PageBackHandler? = null,
    val overlay: (@Composable () -> Unit)? = null,
) {
    companion object {
        val Empty = PageChromeContribution()
    }
}

data class PageBackHandler(
    val shouldConsumeBack: () -> Boolean,
    val onConsumeBack: () -> Unit,
)

data class PageChromeMenuItem(
    val key: String,
    val title: String,
    val onClick: () -> Unit,
)

class PageChromeHost {
    private val contributions = mutableStateMapOf<String, PageChromeContribution>()

    fun stateFor(entryId: String): PageChromeContribution {
        return contributions[entryId] ?: PageChromeContribution.Empty
    }

    fun registrarFor(entryId: String): PageChromeRegistrar {
        return PageChromeRegistrar(entryId = entryId, host = this)
    }

    internal fun setContribution(entryId: String, contribution: PageChromeContribution) {
        contributions[entryId] = contribution
    }

    internal fun clearContribution(entryId: String) {
        contributions.remove(entryId)
    }
}

class PageChromeRegistrar internal constructor(
    private val entryId: String,
    private val host: PageChromeHost,
) {
    fun setContribution(contribution: PageChromeContribution) {
        host.setContribution(entryId, contribution)
    }

    fun clearContribution() {
        host.clearContribution(entryId)
    }
}

val LocalPageChrome = staticCompositionLocalOf<PageChromeRegistrar> {
    error("No PageChromeRegistrar provided")
}

@Composable
fun rememberPageChromeHost(): PageChromeHost {
    return remember { PageChromeHost() }
}

@Composable
fun RegisterPageChrome(contribution: PageChromeContribution) {
    val registrar = LocalPageChrome.current

    DisposableEffect(registrar, contribution) {
        registrar.setContribution(contribution)
        onDispose {
            registrar.clearContribution()
        }
    }
}
