package com.niki914.zafiro.app.ui

import android.app.Activity
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import com.niki914.uikit.base.BaseTheme
import com.niki914.uikit.infra.LiquidScreen
import com.niki914.uikit.infra.LiquidScreenSwipeContent
import com.niki914.uikit.infra.TitleDirection
import com.niki914.uikit.infra.nav.LocalNavigationEntry
import com.niki914.uikit.infra.nav.LocalPageTitle
import com.niki914.uikit.infra.nav.rememberNavigationController
import com.niki914.uikit.infra.rememberLiquidScreenState
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.model.AppLaunchDecision
import com.niki914.zafiro.app.ui.model.HomeChatViewModel
import com.niki914.zafiro.app.ui.model.StartupAssistantUi
import com.niki914.zafiro.app.ui.model.ThemeController
import com.niki914.zafiro.app.ui.nav.HomePage
import com.niki914.zafiro.app.ui.nav.NoTitle
import com.niki914.zafiro.app.ui.nav.PageTitleSpec
import com.niki914.zafiro.app.ui.nav.ResTitle
import com.niki914.zafiro.app.ui.nav.TextTitle
import com.niki914.zafiro.app.ui.nav.TitleBarMode
import com.niki914.zafiro.app.ui.nav.TopBarActionSpec
import com.niki914.zafiro.app.ui.nav.ZafiroPage

@Composable
fun ZafiroApp(
    startupAssistantUi: StartupAssistantUi,
    launchDecision: AppLaunchDecision,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val systemDarkTheme = isSystemInDarkTheme()
    val themePrefs = ThemeController.prefs
    val isDarkTheme = themePrefs.resolveDarkTheme(systemDarkTheme)
    val actionIconTint = if (isDarkTheme) Color.White else Color.Black
    val rootBackToHomeWindowMillis = 2_000L
    val rootBackToHomeHint = stringResource(R.string.ui_root_back_to_home_hint)
    val pageChromeHost = rememberPageChromeHost()
    var chromeMenuExpanded by remember { mutableStateOf(false) }
    var lastRootBackPressedAt by remember { mutableStateOf(0L) }
    var isPageTransitioning by remember { mutableStateOf(false) }
    var selectedConversationId by remember { mutableStateOf<String?>(null) }
    var activeConversationId by remember { mutableStateOf<String?>(null) }
    var activeConversationTitle by remember { mutableStateOf<String?>(null) }
    val initialPage = launchDecision.initialPage
    val controller = rememberNavigationController<ZafiroPage>(initialPage = initialPage)
    val navigator = controller.navigator
    val saveableStateHolder = rememberSaveableStateHolder()
    val currentEntry = controller.currentEntry
    val currentPage = currentEntry.page
    val currentChrome = pageChromeHost.stateFor(currentEntry.id)
    val currentLeftAction = currentChrome.leftAction ?: currentPage.leftAction
    val latestCurrentChrome by rememberUpdatedState(currentChrome)
    fun closeChromeMenu() {
        chromeMenuExpanded = false
    }

    fun openChromeMenu() {
        if (currentChrome.menuItems.isNotEmpty()) {
            chromeMenuExpanded = true
        }
    }

    val currentRightAction = resolveRightAction(
        baseAction = currentPage.rightAction,
        chrome = currentChrome,
        onOpenChromeMenu = ::openChromeMenu,
    )
    val showLeftButton =
        currentLeftAction != null && (currentLeftAction.onClick != null || controller.canGoBack)
    val showRightButton = currentRightAction != null
    val currentTitle = resolveTitle(currentChrome.titleSpec ?: currentPage.titleSpec)
    val isTitleCollapsible = currentPage.titleMode == TitleBarMode.Collapsible
    val screenState = rememberLiquidScreenState(
        title = currentTitle,
        isTitleCollapsible = isTitleCollapsible,
        showLeftButton = showLeftButton,
        showRightButton = showRightButton,
        showBlurLayer = currentPage.showBlurLayer,
    )

    fun push(page: ZafiroPage) {
        closeChromeMenu()
        navigator.push(page)
    }

    fun pushFromLeft(page: ZafiroPage) {
        closeChromeMenu()
        navigator.push(page, direction = TitleDirection.Back)
    }

    fun resetTo(page: ZafiroPage) {
        closeChromeMenu()
        navigator.resetTo(page)
    }

    fun popToRight() {
        navigator.pop(direction = TitleDirection.Forward)
    }

    fun popOrMoveTaskToBack() {
        if (controller.canGoBack && currentPage.backEnabled) {
            navigator.pop()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastRootBackPressedAt <= rootBackToHomeWindowMillis) {
            activity?.moveTaskToBack(true)
        } else {
            lastRootBackPressedAt = now
            Toast.makeText(context.applicationContext, rootBackToHomeHint, Toast.LENGTH_SHORT)
                .show()
        }
    }

    fun requestBack() {
        if (chromeMenuExpanded) {
            closeChromeMenu()
            return
        }
        val backHandler = latestCurrentChrome.backHandler
        if (backHandler != null && backHandler.shouldConsumeBack()) {
            backHandler.onConsumeBack()
        } else {
            popOrMoveTaskToBack()
        }
    }

    suspend fun deleteActiveConversation(id: String) {
        val homeEntry = controller.stack.lastOrNull { entry -> entry.page == HomePage }
            ?: error("Home entry is required before deleting the active conversation.")
        val homeViewModel = ViewModelProvider(homeEntry)[
            HomeChatViewModel::class.java.name,
            HomeChatViewModel::class.java,
        ]
        homeViewModel.deleteConversationNow(id)
        if (activeConversationId == id) {
            activeConversationId = null
            activeConversationTitle = null
        }
    }

    BackHandler(enabled = true) {
        requestBack()
    }

    LaunchedEffect(
        currentEntry.id,
        controller.lastDirection,
        currentTitle,
        currentLeftAction,
        currentRightAction,
        currentChrome.menuItems,
        currentChrome.backHandler,
    ) {
        if (currentChrome.menuItems.isEmpty()) {
            closeChromeMenu()
        }
        val onLeftClick = bindAction(currentLeftAction, fallback = ::requestBack)
        val onRightClick = bindAction(currentRightAction)

        when (controller.lastDirection) {
            TitleDirection.Forward -> screenState.navigateForward(
                title = currentTitle,
                isTitleCollapsible = isTitleCollapsible,
                showLeftButton = showLeftButton,
                showRightButton = showRightButton,
                showBlurLayer = currentPage.showBlurLayer,
                onLeftClick = onLeftClick,
                onRightClick = onRightClick,
            )

            TitleDirection.Back -> screenState.navigateBack(
                title = currentTitle,
                isTitleCollapsible = isTitleCollapsible,
                showLeftButton = showLeftButton,
                showRightButton = showRightButton,
                showBlurLayer = currentPage.showBlurLayer,
                onLeftClick = onLeftClick,
                onRightClick = onRightClick,
            )

            TitleDirection.None -> screenState.update(
                title = currentTitle,
                isTitleCollapsible = isTitleCollapsible,
                showLeftButton = showLeftButton,
                showRightButton = showRightButton,
                showBlurLayer = currentPage.showBlurLayer,
                onLeftClick = onLeftClick,
                onRightClick = onRightClick,
            )
        }
    }

    val seedColor = themePrefs.seedColor?.let { Color(it) }

    Box(modifier = Modifier.fillMaxSize()) {
        BaseTheme(
            darkTheme = isDarkTheme,
            dynamicColor = themePrefs.seedColor == null,
            seedColor = seedColor,
        ) {
            LiquidScreen(
                state = screenState,
                // 折叠状态拉取自当前导航条目：页面经 ReportTitleBarCollapsed 写入，
                // 条目存活期保留，导航切页同帧生效，返回时首帧恢复离开前状态。
                collapsed = currentEntry.titleCollapsed,
                actionsEnabled = !isPageTransitioning,
                leftButton = currentLeftAction?.let { action ->
                    {
                        AnimatedContent(
                            targetState = action.icon,
                            transitionSpec = {
                                val iconAnimationSpec = tween<Float>(
                                    durationMillis = 280,
                                    easing = FastOutSlowInEasing,
                                )
                                (scaleIn(
                                    initialScale = 1.18f,
                                    animationSpec = iconAnimationSpec,
                                ) + fadeIn(animationSpec = iconAnimationSpec)).togetherWith(
                                    scaleOut(
                                        targetScale = 0.78f,
                                        animationSpec = iconAnimationSpec,
                                    ) + fadeOut(animationSpec = iconAnimationSpec)
                                )
                            },
                            label = "leftActionIcon",
                        ) { imageVector ->
                            ActionBarVectorIcon(
                                imageVector = imageVector,
                                tint = actionIconTint,
                            )
                        }
                    }
                },
                rightButton = currentRightAction?.let { action ->
                    {
                        AnimatedContent(
                            targetState = action.icon,
                            transitionSpec = {
                                val iconAnimationSpec = tween<Float>(
                                    durationMillis = 280,
                                    easing = FastOutSlowInEasing,
                                )
                                (scaleIn(
                                    initialScale = 1.18f,
                                    animationSpec = iconAnimationSpec,
                                ) + fadeIn(animationSpec = iconAnimationSpec)).togetherWith(
                                    scaleOut(
                                        targetScale = 0.78f,
                                        animationSpec = iconAnimationSpec,
                                    ) + fadeOut(animationSpec = iconAnimationSpec)
                                )
                            },
                            label = "rightActionIcon",
                        ) { imageVector ->
                            ActionBarVectorIcon(
                                imageVector = imageVector,
                                tint = actionIconTint,
                            )
                        }
                    }
                },
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    LiquidScreenSwipeContent(
                        targetState = currentEntry,
                        direction = controller.lastDirection,
                        modifier = Modifier.fillMaxSize(),
                        onTransitionActiveChanged = { active ->
                            isPageTransitioning = active
                        },
                    ) { entry ->
                        val pageChromeRegistrar = remember(entry.id, pageChromeHost) {
                            pageChromeHost.registrarFor(entry.id)
                        }
                        CompositionLocalProvider(
                            LocalNavigationEntry provides entry,
                            LocalPageChrome provides pageChromeRegistrar,
                            LocalPageTitle provides resolveTitle(entry.page.titleSpec),
                        ) {
                            saveableStateHolder.SaveableStateProvider(entry.id) {
                                ZafiroPageContent(
                                    entry = entry,
                                    startupAssistantUi = startupAssistantUi,
                                    onPush = ::push,
                                    onPushFromLeft = ::pushFromLeft,
                                    onPop = { navigator.pop() },
                                    onPopMultiple = { navigator.popMultiple(it) },
                                    onPopToRight = ::popToRight,
                                    onResetTo = ::resetTo,
                                    selectedConversationId = selectedConversationId,
                                    onConversationSelected = { id ->
                                        selectedConversationId = id
                                    },
                                    onConversationSelectionConsumed = { id ->
                                        if (selectedConversationId == id) {
                                            selectedConversationId = null
                                        }
                                    },
                                    activeConversationId = activeConversationId,
                                    activeConversationTitle = activeConversationTitle,
                                    onActiveConversationChanged = { id, title ->
                                        activeConversationId = id
                                        activeConversationTitle = title
                                    },
                                    onCurrentConversationDeleted = { id ->
                                        deleteActiveConversation(id)
                                    },
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = screenState.actionBarHeight.value, end = 12.dp),
                    ) {
                        DropdownMenu(
                            expanded = chromeMenuExpanded && currentChrome.menuItems.isNotEmpty(),
                            onDismissRequest = ::closeChromeMenu,
                        ) {
                            currentChrome.menuItems.forEach { item ->
                                DropdownMenuItem(
                                    text = { Text(item.title) },
                                    onClick = {
                                        closeChromeMenu()
                                        item.onClick()
                                    },
                                )
                            }
                        }
                    }
                }
            }
            currentChrome.overlay?.invoke()
        }
    }
}

@Composable
private fun resolveTitle(titleSpec: PageTitleSpec): String {
    return when (titleSpec) {
        NoTitle -> ""
        is ResTitle -> stringResource(titleSpec.resId)
        is TextTitle -> titleSpec.value
    }
}

private fun bindAction(
    action: TopBarActionSpec?,
    fallback: (() -> Unit)? = null,
): (() -> Unit)? {
    return action?.onClick ?: fallback
}

private fun resolveRightAction(
    baseAction: TopBarActionSpec?,
    chrome: PageChromeContribution,
    onOpenChromeMenu: () -> Unit,
): TopBarActionSpec? {
    return when {
        chrome.rightAction != null -> chrome.rightAction
        chrome.menuItems.isNotEmpty() -> baseAction?.copy(onClick = onOpenChromeMenu)
            ?: TopBarActionSpec(icon = Icons.Default.MoreHoriz, onClick = onOpenChromeMenu)

        else -> baseAction
    }
}

@Composable
private fun ActionBarVectorIcon(
    imageVector: ImageVector,
    tint: Color,
    size: Dp = 20.dp,
) {
    Image(
        painter = rememberVectorPainter(imageVector),
        contentDescription = null,
        modifier = Modifier.size(size),
        colorFilter = ColorFilter.tint(tint),
    )
}
