package com.niki914.uikit.infra.nav

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
import com.niki914.uikit.infra.TitleDirection

/**
 * 顶栏折叠信号：内容滚离顶部的累积量 + 折叠布尔。
 *
 * 默认由 `LiquidScreen` 的 nestedScroll 自动写入，页面不参与；归属导航条目
 * → 切页天然隔离（新条目从 0 起算）、条目存活期保留，返回本页时首帧即恢复。
 *
 * 累积量只能反映**手势**产生的位移。页面若含程序化定位（自动贴底、恢复位置）
 * 或页内嵌套滚动子树，可用 `ReportTitleBarCollapsed` 改用自身滚动状态接管。
 */
@Stable
class TitleBarScrollState {
    /**
     * 累积滚离量（px）。
     *
     * 故意用普通字段：滚动时每帧都在变，若做成 Compose 状态会让 `LiquidScreen`
     * 连同内容层逐帧重组。对外只暴露 [collapsed] 布尔，写入相同值时
     * `mutableStateOf` 的结构相等策略会跳过通知，因此不会产生无效重组。
     */
    private var offsetPx: Float = 0f

    /** 页面是否已用精确信号接管（见 `ReportTitleBarCollapsed`）。 */
    private var isOwnedByPage = false

    /** 内容是否已滚离顶部（超过传入的阈值）。 */
    var collapsed: Boolean by mutableStateOf(false)
        private set

    /**
     * 累加内容滚离量并重算折叠态。页面接管后本路径失效。
     *
     * @param scrolledDownPx 向下滚离的像素量，正数表示滚离顶部。
     * @param thresholdPx 折叠阈值，达此值即折叠；等于阈值时不算折叠。
     */
    internal fun addScroll(scrolledDownPx: Float, thresholdPx: Float) {
        if (isOwnedByPage) return
        offsetPx = (offsetPx + scrolledDownPx).coerceAtLeast(0f)
        collapsed = offsetPx > thresholdPx
    }

    /** 页面用自身滚动状态提供的精确折叠信号，接管后累积量不再参与。 */
    internal fun setFromPage(isCollapsed: Boolean) {
        isOwnedByPage = true
        collapsed = isCollapsed
    }
}

data class NavigationEntry<P : Page>(
    val id: String,
    val page: P,
    override val viewModelStore: ViewModelStore = ViewModelStore(),
) : ViewModelStoreOwner {
    /** 本页顶栏折叠信号（驱动 action bar 背景板渐显与小标题浮现）。 */
    val titleBarScroll = TitleBarScrollState()
}

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
