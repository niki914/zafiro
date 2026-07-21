package com.niki914.nexus.agentic.app.ui.infra.nav

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.niki914.nexus.agentic.app.ui.infra.TitleDirection

data class NavigationEntry<P : Page>(
    val id: String,
    val page: P,
    override val viewModelStore: ViewModelStore = ViewModelStore(),
) : ViewModelStoreOwner

@Stable
class NavigationController<P : Page>(
    initialPage: P,
    private val debounceMillis: Long = 300L,
) {
    private var nextEntryIndex by mutableIntStateOf(0)
    private val entryStack = mutableStateListOf(createEntry(initialPage))
    private var lastNavigationAtMillis: Long? = null

    var lastDirection by mutableStateOf(TitleDirection.None)
        private set

    val stack: List<NavigationEntry<P>>
        get() = entryStack

    val currentEntry: NavigationEntry<P>
        get() = entryStack.last()

    val canGoBack: Boolean
        get() = entryStack.size > 1

    val navigator: Navigator<P> = Navigator(this)

    fun push(
        page: P,
        direction: TitleDirection = TitleDirection.Forward,
    ) {
        if (!tryConsumeNavigationDebounce()) return
        entryStack += createEntry(page)
        lastDirection = direction
    }

    fun pop(
        direction: TitleDirection = TitleDirection.Back,
    ): Boolean {
        if (!canGoBack) return false
        if (!tryConsumeNavigationDebounce()) return false
        val removedEntry = entryStack.removeAt(entryStack.lastIndex)
        removedEntry.viewModelStore.clear()
        lastDirection = direction
        return true
    }

    fun popMultiple(
        count: Int,
        direction: TitleDirection = TitleDirection.Back,
    ): Int {
        if (!tryConsumeNavigationDebounce()) return 0
        var popped = 0
        repeat(count) {
            if (!canGoBack) return@repeat
            val removedEntry = entryStack.removeAt(entryStack.lastIndex)
            removedEntry.viewModelStore.clear()
            popped++
        }
        if (popped > 0) {
            lastDirection = direction
        }
        return popped
    }

    fun resetTo(page: P) {
        if (!tryConsumeNavigationDebounce()) return
        entryStack.forEach { entry -> entry.viewModelStore.clear() }
        entryStack.clear()
        entryStack += createEntry(page)
        lastDirection = TitleDirection.Forward
    }

    fun clear() {
        entryStack.forEach { entry -> entry.viewModelStore.clear() }
        entryStack.clear()
        lastDirection = TitleDirection.None
    }

    private fun tryConsumeNavigationDebounce(): Boolean {
        if (debounceMillis <= 0L) return true

        val now = SystemClock.elapsedRealtime()
        val previousNavigationAtMillis = lastNavigationAtMillis
        if (previousNavigationAtMillis != null && now - previousNavigationAtMillis < debounceMillis) {
            return false
        }
        lastNavigationAtMillis = now
        return true
    }

    private fun createEntry(page: P): NavigationEntry<P> {
        nextEntryIndex += 1
        return NavigationEntry(
            id = "${page.routeKey}#$nextEntryIndex",
            page = page,
        )
    }
}

class NavigationControllerHolderViewModel : ViewModel() {
    var controller: NavigationController<*>? = null

    override fun onCleared() {
        controller?.clear()
        controller = null
    }
}

@Composable
fun <P : Page> rememberNavigationController(
    initialPage: P,
): NavigationController<P> {
    val holder = viewModel<NavigationControllerHolderViewModel>()
    return remember(holder, initialPage.routeKey) {
        @Suppress("UNCHECKED_CAST")
        (holder.controller as? NavigationController<P>)
            ?: NavigationController(initialPage = initialPage).also {
                holder.controller = it
            }
    }
}
