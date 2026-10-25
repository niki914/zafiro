// githooks:ignore-file file-token-limit
package com.niki914.zafiro.app.ui

import android.app.Activity
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import com.niki914.uikit.base.BaseTheme
import com.niki914.uikit.base.ThemeStyle
import com.niki914.uikit.menu.ZafiroDropdownMenu
import com.niki914.uikit.menu.ZafiroMenuItem
import com.niki914.uikit.menu.ZafiroMenuAlignment
import com.niki914.uikit.screen.ZafiroScreen
import com.niki914.uikit.screen.ZafiroScreenSwipeContent
import com.niki914.uikit.screen.ZafiroScreenTitle
import com.niki914.uikit.screen.ZafiroScreenTitleContent
import com.niki914.uikit.screen.ZafiroScreenTitleText
import com.niki914.uikit.screen.rememberZafiroScreenState
import com.niki914.uikit.infra.TitleDirection
import com.niki914.uikit.infra.nav.LocalNavigationController
import com.niki914.uikit.infra.nav.LocalNavigationEntry
import com.niki914.uikit.infra.nav.LocalPageTitle
import com.niki914.uikit.infra.nav.rememberNavigationController
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.crash.CrashRecorder
import com.niki914.zafiro.app.crash.CrashReport
import com.niki914.zafiro.app.crash.CrashReportDialog
import com.niki914.zafiro.app.ui.chat.composable.text.LocalMarkdownScale
import com.niki914.zafiro.app.ui.helper.ZafiroToast
import com.niki914.zafiro.app.ui.model.AppLaunchDecision
import com.niki914.zafiro.app.ui.model.StartupAssistantUi
import com.niki914.zafiro.app.ui.model.ThemeController
import com.niki914.zafiro.app.ui.model.home.HomeChatViewModel
import com.niki914.zafiro.app.ui.nav.HomePage
import com.niki914.zafiro.app.ui.nav.NoTitle
import com.niki914.zafiro.app.ui.nav.PageTitleSpec
import com.niki914.zafiro.app.ui.nav.ResTitle
import com.niki914.zafiro.app.ui.nav.TextTitle
import com.niki914.zafiro.app.ui.nav.TitleBarMode
import com.niki914.zafiro.app.ui.nav.TopBarActionSpec
import com.niki914.zafiro.app.ui.nav.ZafiroPage
import com.niki914.zafiro.business.application.ApplicationService
import com.niki914.zafiro.repo.XRepo
import com.niki914.zafiro.service.findService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ZafiroApp(
    startupAssistantUi: StartupAssistantUi,
    launchDecision: AppLaunchDecision,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val activity = context as? Activity
    val systemDarkTheme = isSystemInDarkTheme()
    val themePrefs = ThemeController.prefs
    val markdownScale by XRepo.markdownScaleSetting.collectAsState()
    val isDarkTheme = themePrefs.resolveDarkTheme(systemDarkTheme)
    val actionIconTint = if (isDarkTheme) Color.White else Color.Black
    val rootBackToHomeWindowMillis = 2_000L
    val pageChromeHost = rememberPageChromeHost()
    var chromeMenuExpanded by remember { mutableStateOf(false) }
    var rightButtonBounds by remember { mutableStateOf<Rect?>(null) }
    var lastRootBackPressedAt by remember { mutableStateOf(0L) }
    var isPageTransitioning by remember { mutableStateOf(false) }
    var pendingCrashReport by remember { mutableStateOf<CrashReport?>(null) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            CrashRecorder.peekPendingCrash(context)
        }?.let { report ->
            pendingCrashReport = report
        }
    }
    var selectedConversationId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        findService<ApplicationService>()?.conversationSwitchRequests?.collect { convId ->
            selectedConversationId = convId
        }
    }
    var activeConversationId by remember { mutableStateOf<String?>(null) }
    val initialPage = launchDecision.initialPage
    val controller = rememberNavigationController<ZafiroPage>(initialPage = initialPage)
    val navigator = controller.navigator
    val saveableStateHolder = rememberSaveableStateHolder()

    LaunchedEffect(controller.stack) {
        val activeIds = controller.stack.map { it.id }.toSet()
        pageChromeHost.syncWithStack(activeIds)
    }

    val currentEntry = controller.currentEntry
    val currentPage = currentEntry.page
    val currentChrome = pageChromeHost.stateFor(currentEntry.id)
    val isChromeMenuOpen = chromeMenuExpanded && currentChrome.menuItems.isNotEmpty()
    val shouldFadeRightButton = themePrefs.themeStyle == ThemeStyle.LiquidGlass && isChromeMenuOpen
    val rightButtonAlpha by animateFloatAsState(
        targetValue = if (shouldFadeRightButton) 0f else 1f,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "rightButtonAlpha",
    )
    val currentLeftAction = currentChrome.leftAction ?: currentPage.leftAction
    val latestCurrentChrome by rememberUpdatedState(currentChrome)
    fun closeChromeMenu() {
        chromeMenuExpanded = false
    }

    fun openChromeMenu() {
        if (currentChrome.menuItems.isNotEmpty()) {
            focusManager.clearFocus(force = true)
            chromeMenuExpanded = true
        }
    }

    val currentRightAction = resolveRightAction(
        baseAction = currentPage.rightAction,
        chrome = currentChrome,
        onOpenChromeMenu = ::openChromeMenu,
    )
    val showLeftButton = currentChrome.actionsVisible &&
            currentLeftAction != null && (currentLeftAction.onClick != null || controller.canGoBack)
    val showRightButton = currentChrome.actionsVisible && currentRightAction != null
    val currentTitle = resolveScreenTitle(currentChrome, currentPage, currentEntry.id)
    val isTitleCollapsible = currentPage.titleMode == TitleBarMode.Collapsible
    val screenState = rememberZafiroScreenState(
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
            ZafiroToast.show(R.string.ui_root_back_to_home_hint)
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
        }
    }

    BackHandler(enabled = true) {
        requestBack()
    }

    val onLeftClick = bindAction(currentLeftAction, fallback = ::requestBack)
    val onRightClick = bindAction(currentRightAction)

    // 上次已做过标题动画的条目：标题滑动只表达「换了页面」这一空间语义。
    // 同一条目内的标题刷新（会话标题出现、选中数量变化、切换配置）是活内容变了，
    // 不是位移，走 update 瞬切；否则它们会沿用上一次的导航方向反复左右滑。
    var lastAnimatedEntryId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(
        currentEntry.id,
        controller.lastDirection,
        currentTitle,
        currentLeftAction,
        currentRightAction,
        currentChrome.menuItems,
        currentChrome.backHandler,
        currentChrome.actionsVisible,
    ) {
        if (currentChrome.menuItems.isEmpty()) {
            closeChromeMenu()
        }

        val isPageChange = lastAnimatedEntryId != currentEntry.id
        lastAnimatedEntryId = currentEntry.id
        val titleDirection =
            if (isPageChange) controller.lastDirection else TitleDirection.None

        when (titleDirection) {
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
            themeStyle = themePrefs.themeStyle,
        ) {
            CompositionLocalProvider(
                LocalMarkdownScale provides markdownScale,
            ) {
                ZafiroScreen(
                    state = screenState,
                    // 折叠信号载体归属当前导航条目，由 ZafiroScreen 经 nestedScroll 自动写入；
                    // 条目存活期保留，导航切页同帧隔离，返回时首帧恢复离开前状态。
                    titleBarScroll = currentEntry.titleBarScroll,
                    actionsEnabled = !isPageTransitioning,
                    isOccluded = isChromeMenuOpen || currentChrome.isTitleOccluded,
                    rightButtonAlpha = rightButtonAlpha,
                    showLeftButton = showLeftButton,
                    showRightButton = showRightButton,
                    onLeftClick = onLeftClick,
                    onRightClick = onRightClick,
                    onRightButtonBoundsChanged = { rightButtonBounds = it },
                    leftButton = currentLeftAction?.let { action ->
                        {
                            TopBarActionIcon(
                                action = action,
                                themeStyle = themePrefs.themeStyle,
                                tint = actionIconTint,
                                label = "leftActionIcon",
                            )
                        }
                    },
                    rightButton = currentRightAction?.let { action ->
                        {
                            TopBarActionIcon(
                                action = action,
                                themeStyle = themePrefs.themeStyle,
                                tint = actionIconTint,
                                label = "rightActionIcon",
                            )
                        }
                    },
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        ZafiroScreenSwipeContent(
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
                                LocalNavigationController provides controller,
                                LocalNavigationEntry provides entry,
                                LocalPageChrome provides pageChromeRegistrar,
                                LocalPageTitle provides resolveTitle(entry.page.titleSpec),
                            ) {
                                saveableStateHolder.SaveableStateProvider(entry.id) {
                                    ZafiroPageContent(
                                        entry = entry,
                                        startupAssistantUi = startupAssistantUi,
                                        isTopPage = (currentEntry.id == entry.id),
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
                                        onActiveConversationChanged = { id ->
                                            activeConversationId = id
                                        },
                                        onCurrentConversationDeleted = { id ->
                                            deleteActiveConversation(id)
                                        },
                                    )
                                }
                            }
                        }

                        ZafiroDropdownMenu(
                            expanded = isChromeMenuOpen,
                            onDismissRequest = ::closeChromeMenu,
                            anchorBounds = rightButtonBounds,
                            alignment = ZafiroMenuAlignment.BottomEnd,
                        ) {
                            currentChrome.menuItems.forEach { item ->
                                ZafiroMenuItem(
                                    title = item.title,
                                    onClick = {
                                        closeChromeMenu()
                                        item.onClick()
                                    },
                                )
                            }
                        }

                        pendingCrashReport?.let { report ->
                            CrashReportDialog(
                                report = report,
                                onDismiss = { pendingCrashReport = null },
                            )
                        }
                    }
                }
                currentChrome.overlay?.invoke()
            }
        }
    }
}

@Composable
private fun resolveScreenTitle(
    chrome: PageChromeContribution,
    page: ZafiroPage,
    entryId: String,
): ZafiroScreenTitle {
    val key = chrome.titleKey ?: entryId
    chrome.titleContent?.let { return ZafiroScreenTitleContent(content = it, key = key) }
    return ZafiroScreenTitleText(
        value = resolveTitle(chrome.titleSpec ?: page.titleSpec),
        key = key
    )
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
            ?: TopBarActionSpec(
                icon = Icons.Default.MoreHoriz,
                onClick = onOpenChromeMenu,
                contentDescription = "More Button",
            )

        else -> baseAction
    }
}

@Composable
private fun TopBarActionIcon(
    action: TopBarActionSpec,
    themeStyle: ThemeStyle,
    tint: Color,
    label: String,
    modifier: Modifier = Modifier,
) {
    val isLiquid = themeStyle == ThemeStyle.LiquidGlass

    AnimatedContent(
        targetState = action.icon,
        transitionSpec = {
            if (isLiquid) {
                val iconAnimationSpec = tween<Float>(
                    durationMillis = 280,
                    easing = FastOutSlowInEasing,
                )
                (scaleIn(initialScale = 1.18f, animationSpec = iconAnimationSpec) + fadeIn(animationSpec = iconAnimationSpec)).togetherWith(
                    scaleOut(targetScale = 0.78f, animationSpec = iconAnimationSpec) + fadeOut(animationSpec = iconAnimationSpec)
                )
            } else {
                val enterSpec = tween<Float>(durationMillis = 220, easing = FastOutSlowInEasing)
                val exitSpec = tween<Float>(durationMillis = 180, easing = FastOutSlowInEasing)
                fadeIn(animationSpec = enterSpec).togetherWith(
                    fadeOut(animationSpec = exitSpec)
                )
            }
        },
        label = label,
        modifier = modifier,
    ) { imageVector ->
        val rotationModifier = if (!isLiquid) {
            val rotation by transition.animateFloat(
                transitionSpec = { tween(durationMillis = 220, easing = FastOutSlowInEasing) },
                label = "${label}Rotation",
            ) { state ->
                when (state) {
                    EnterExitState.PreEnter -> -90f
                    EnterExitState.Visible -> 0f
                    EnterExitState.PostExit -> 90f
                }
            }
            Modifier.graphicsLayer { rotationZ = rotation }
        } else {
            Modifier
        }

        ActionBarVectorIcon(
            imageVector = imageVector,
            contentDescription = action.contentDescription,
            tint = tint,
            modifier = rotationModifier,
        )
    }
}

@Composable
private fun ActionBarVectorIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    tint: Color,
    size: Dp = 20.dp,
    modifier: Modifier = Modifier,
) {
    Image(
        painter = rememberVectorPainter(imageVector),
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        colorFilter = ColorFilter.tint(tint),
    )
}
