// githooks:ignore-file file-token-limit
package com.niki914.zafiro.app.screen

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.niki914.logging.Logger
import com.niki914.uikit.base.BaseTheme
import com.niki914.uikit.control.ZafiroLoadingIndicator
import com.niki914.uikit.infra.shape.G2CardShape
import com.niki914.zafiro.api.util.runCatchingCancellable
import com.niki914.zafiro.app.overlay.OverlayLifecycleOwner
import com.niki914.zafiro.app.ui.model.ThemeController
import com.niki914.zafiro.remoteview.floatingball.DockSide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 虚拟屏小窗与大窗展开层（双 Window / 零系统级 Resize 架构）。
 *
 * 核心特征：
 * - **两级窗口分离架构**：
 *   - [Mini Window]：终生固定尺寸 (100dp x 220dp)，专职小窗常态、拖动吸边；
 *   - [Expanded Window]：展开时按需挂载透明窗口，形变在 Compose 内部完成；
 * - **唯一阶段来源 ([VirtualDisplayStage])**：
 *   小窗、大窗与主对话页卡片共享同一阶段流，避免选择器与运行态撕裂；
 * - **对称模糊形变**：
 *   运行态采用静图 + 实时画面模糊切换；选择器态与小窗待命图标之间采用对称模糊 + 交叉淡入淡出衔接。
 */
internal data class OverlayBinding(
    val screenId: String = "",
    val frames: StateFlow<Bitmap?>,
    val stage: StateFlow<VirtualDisplayStage>,
    val vdWidth: Int = 1080,
    val vdHeight: Int = 2400,
    val onClose: () -> Unit = {},
    val onNavKey: (Int) -> Unit = {},
    val onEnterAppPicker: () -> Unit = {},
    val onReturnFromAppPicker: () -> Boolean = { false },
    val onQualityChanged: (Boolean) -> Unit = {},
    val onTouch: (MotionEvent) -> Unit = {},
    val onLaunchApp: (String) -> Unit = {},
)

@SuppressLint("StaticFieldLeak")
internal object VirtualDisplayCard {
    private const val TAG = "niki914_zafiro_VirtualDisplayCard"

    private var windowManager: WindowManager? = null
    private var miniTouchLayout: VirtualDisplayTouchLayout? = null
    private var expandedView: View? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null
    private var onRequestExpandMini: (() -> Unit)? = null
    private val activeBinding = MutableStateFlow<OverlayBinding?>(null)

    @Volatile
    var isShowing: Boolean = false
        private set

    fun expandIfShowing() {
        if (!isShowing || expandedView != null) return
        onRequestExpandMini?.invoke()
    }

    suspend fun bind(binding: OverlayBinding?): Boolean = withContext(Dispatchers.Main.immediate) {
        if (binding == null) {
            hideOnMain()
            true
        } else {
            activeBinding.value = binding
            isShowing || show(binding)
        }
    }

    suspend fun show(
        binding: OverlayBinding,
    ): Boolean = withContext(Dispatchers.Main.immediate) {
        if (isShowing) hideOnMain()
        activeBinding.value = binding
        val context = applicationContext()
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return@withContext false
        val density = context.resources.displayMetrics.density
        val screenWidth = context.resources.displayMetrics.widthPixels
        val widthPx = (100 * density).toInt()
        val heightPx = (220 * density).toInt()

        val miniLp = WindowManager.LayoutParams(
            widthPx,
            heightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenWidth - widthPx - (16 * density).toInt()).coerceAtLeast(0)
            y = (240 * density).toInt()
            windowAnimations = 0
        }

        val owner = OverlayLifecycleOwner().apply {
            onCreate()
            onStart()
            onResume()
        }

        var isMiniFocused by mutableStateOf(false)
        var lastTouchEpoch by mutableStateOf(0L)
        val requestExpand: () -> Unit = {
            isMiniFocused = false
            showExpandedWindow(
                context = context,
                manager = manager,
                owner = owner,
                bindingFlow = activeBinding,
            )
        }

        lateinit var root: VirtualDisplayTouchLayout

        val composeView = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setContent {
                val themePrefs = ThemeController.prefs
                val isSystemDark = isSystemInDarkTheme()
                val isDark = themePrefs.resolveDarkTheme(isSystemDark)
                val seed = themePrefs.seedColor?.let { Color(it) }

                BaseTheme(
                    darkTheme = isDark,
                    dynamicColor = themePrefs.seedColor == null,
                    seedColor = seed,
                    themeStyle = themePrefs.themeStyle,
                    uiStyleLookup = ThemeController.uiStyleLookup(),
                ) {
                    val currentBinding by activeBinding.collectAsState()
                    val current = currentBinding
                    if (current != null) {
                        MiniPreviewCard(
                            frames = current.frames,
                            stage = current.stage,
                            isFocused = isMiniFocused,
                            touchEpoch = lastTouchEpoch,
                            onToggleFocus = {
                                isMiniFocused = !isMiniFocused
                                lastTouchEpoch = SystemClock.uptimeMillis()
                            },
                            onFocusTimeout = { isMiniFocused = false },
                            onRequestExpand = requestExpand,
                            onClose = {
                                hideOnMain()
                                current.onClose()
                            },
                        )
                    }
                }
            }
        }

        root = VirtualDisplayTouchLayout(
            context = context,
            manager = manager,
            params = miniLp,
            onOutsideTouch = {
                if (isMiniFocused) {
                    isMiniFocused = false
                }
            },
        ).apply {
            addView(composeView)
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
        }

        val added = runCatchingCancellable {
            manager.addView(root, miniLp)
            true
        }.getOrElse { error ->
            Logger.e(TAG, "overlay add mini window failed", error)
            owner.onDestroy()
            false
        }
        if (!added) return@withContext false

        windowManager = manager
        miniTouchLayout = root
        lifecycleOwner = owner
        onRequestExpandMini = requestExpand
        isShowing = true
        true
    }

    private fun showExpandedWindow(
        context: Context,
        manager: WindowManager,
        owner: OverlayLifecycleOwner,
        bindingFlow: StateFlow<OverlayBinding?>,
    ) {
        if (expandedView != null) return
        val miniLayout = miniTouchLayout ?: return
        val initialBinding = bindingFlow.value ?: return

        initialBinding.onQualityChanged(true)

        val density = context.resources.displayMetrics.density
        val screenWidth = context.resources.displayMetrics.widthPixels
        val screenHeight = context.resources.displayMetrics.heightPixels

        val miniX = miniLayout.ballX
        val miniY = miniLayout.ballY
        val miniW = miniLayout.width
        val miniH = miniLayout.height
        val dockSide = miniLayout.getDockSide()

        val targetWidthDp = 290.dp
        val targetWidthPx = (targetWidthDp.value * density).toInt()

        val currentFrame = initialBinding.frames.value
        val aspect = if (currentFrame != null && currentFrame.width > 0 && currentFrame.height > 0) {
            currentFrame.height.toFloat() / currentFrame.width.toFloat()
        } else {
            screenHeight.toFloat() / screenWidth.toFloat()
        }

        val targetHeightDp = ((targetWidthDp * aspect) + 64.dp).coerceIn(360.dp, (screenHeight / density).dp - 64.dp)
        val targetHeightPx = (targetHeightDp.value * density).toInt()

        val initialTargetX = if (dockSide.isRight) {
            (miniX + miniW - targetWidthPx).coerceAtLeast(0)
        } else {
            miniX.coerceAtMost(screenWidth - targetWidthPx)
        }
        val initialTargetY = (miniY + (miniH - targetHeightPx) / 2).coerceIn(
            (32 * density).toInt(),
            (screenHeight - targetHeightPx - (32 * density).toInt()).coerceAtLeast((32 * density).toInt()),
        )

        val expandedLp = WindowManager.LayoutParams(
            targetWidthPx,
            targetHeightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialTargetX
            y = initialTargetY
            windowAnimations = 0
        }

        val composeView = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setContent {
                val themePrefs = ThemeController.prefs
                val isSystemDark = isSystemInDarkTheme()
                val isDark = themePrefs.resolveDarkTheme(isSystemDark)
                val seed = themePrefs.seedColor?.let { Color(it) }

                BaseTheme(
                    darkTheme = isDark,
                    dynamicColor = themePrefs.seedColor == null,
                    seedColor = seed,
                    themeStyle = themePrefs.themeStyle,
                    uiStyleLookup = ThemeController.uiStyleLookup(),
                ) {
                    val currentBinding by bindingFlow.collectAsState()
                    val b = currentBinding
                    if (b != null) {
                        DisposableEffect(b.screenId) {
                            b.onQualityChanged(true)
                            onDispose {
                                b.onQualityChanged(false)
                            }
                        }
                        ExpandedCardOverlay(
                            dockSide = dockSide,
                            targetWidth = targetWidthDp,
                            targetHeight = targetHeightDp,
                            miniWidth = (miniW / density).dp,
                            miniHeight = (miniH / density).dp,
                            frames = b.frames,
                            stage = b.stage,
                            vdWidth = b.vdWidth,
                            vdHeight = b.vdHeight,
                            onFirstFrameReady = {
                                miniTouchLayout?.visibility = View.INVISIBLE
                            },
                            onRequestCollapse = {
                                b.onQualityChanged(false)
                            },
                            onCollapseFinished = {
                                val finalMiniX = if (dockSide.isRight) {
                                    expandedLp.x + targetWidthPx - miniW
                                } else {
                                    expandedLp.x
                                }
                                val finalMiniY = expandedLp.y + (targetHeightPx - miniH) / 2
                                miniLayout.syncPosition(finalMiniX, finalMiniY)
                                miniLayout.makeVisibleAndNotifyDrawn {
                                    removeExpandedWindow()
                                }
                            },
                            onClose = {
                                hideOnMain()
                                b.onClose()
                            },
                            onNavKey = { keyCode -> b.onNavKey(keyCode) },
                            onEnterAppPicker = { b.onEnterAppPicker() },
                            onReturnFromAppPicker = { b.onReturnFromAppPicker() },
                            onDrag = { dxPx, dyPx ->
                                expandedLp.x = (expandedLp.x + dxPx.toInt()).coerceIn(0, screenWidth - targetWidthPx)
                                expandedLp.y = (expandedLp.y + dyPx.toInt()).coerceIn(
                                    (32 * density).toInt(),
                                    (screenHeight - targetHeightPx - (32 * density).toInt()).coerceAtLeast((32 * density).toInt()),
                                )
                                if (isAttachedToWindow) {
                                    manager.updateViewLayout(this, expandedLp)
                                }
                            },
                            onTouch = { event -> b.onTouch(event) },
                            onLaunchApp = { packageName -> b.onLaunchApp(packageName) },
                        )
                    }
                }
            }
        }

        expandedView = composeView
        runCatchingCancellable {
            manager.addView(composeView, expandedLp)
            Logger.i(TAG, "VirtualDisplay expanded window added on-demand")
        }.onFailure { error ->
            Logger.e(TAG, "Failed to add expanded window", error)
            removeExpandedWindow()
        }
    }

    private fun removeExpandedWindow() {
        val view = expandedView ?: return
        expandedView = null
        runCatchingCancellable {
            windowManager?.removeViewImmediate(view)
            Logger.i(TAG, "VirtualDisplay expanded window removed")
        }.onFailure { error ->
            Logger.w(TAG, "remove expanded window failed", error)
        }
    }

    suspend fun hide() = withContext(Dispatchers.Main.immediate) { hideOnMain() }

    fun hideOnMain() {
        removeExpandedWindow()
        val layout = miniTouchLayout
        val manager = windowManager
        val owner = lifecycleOwner
        miniTouchLayout = null
        windowManager = null
        lifecycleOwner = null
        onRequestExpandMini = null
        isShowing = false
        owner?.onPause()
        owner?.onStop()
        owner?.onDestroy()
        if (layout != null && manager != null) {
            runCatchingCancellable {
                manager.removeViewImmediate(layout)
            }.onFailure { error ->
                Logger.w(TAG, "overlay already gone", error)
            }
        }
    }
}

/**
 * 小窗选择器态的待命图标视图，同时供大窗展开/收起交接帧复用以保证零闪烁。
 */
@Composable
private fun AppPickerStandbyGlyph(
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(G2CardShape(14.dp))
            .background(colors.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Apps,
            contentDescription = "App picker standby",
            tint = colors.onSecondaryContainer,
            modifier = Modifier.size(26.dp),
        )
    }
}

/**
 * 小窗常态渲染（Mini PIP 卡片）。
 */
@Composable
private fun MiniPreviewCard(
    frames: StateFlow<Bitmap?>,
    stage: StateFlow<VirtualDisplayStage>,
    isFocused: Boolean,
    touchEpoch: Long,
    onToggleFocus: () -> Unit,
    onFocusTimeout: () -> Unit,
    onRequestExpand: () -> Unit,
    onClose: () -> Unit,
) {
    val frame by frames.collectAsState()
    val currentStage by stage.collectAsState()
    val colors = MaterialTheme.colorScheme
    val cardShape = G2CardShape(8.dp)

    LaunchedEffect(isFocused, touchEpoch) {
        if (isFocused) {
            delay(3500L)
            onFocusTimeout()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(cardShape)
            .background(colors.surfaceContainerHigh)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggleFocus,
            ),
        contentAlignment = Alignment.Center,
    ) {
        when (currentStage) {
            is VirtualDisplayStage.AppPicker -> {
                AppPickerStandbyGlyph()
            }

            is VirtualDisplayStage.Launching -> {
                ZafiroLoadingIndicator(
                    color = colors.primary,
                    size = 24.dp,
                    petalSize = 28.dp,
                    strokeWidth = 2.2.dp,
                )
            }

            is VirtualDisplayStage.Running -> {
                val liveFrame = frame?.takeIf { !it.isRecycled }
                if (liveFrame != null) {
                    val bitmap = remember(liveFrame) { liveFrame.asImageBitmap() }
                    Image(
                        bitmap = bitmap,
                        contentDescription = "Screen preview",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds,
                    )
                } else {
                    ZafiroLoadingIndicator(
                        color = colors.primary,
                        size = 24.dp,
                        petalSize = 28.dp,
                        strokeWidth = 2.2.dp,
                    )
                }
            }
        }

        if (isFocused) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 20.dp),
                    verticalArrangement = Arrangement.SpaceEvenly,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircleActionButton(
                        icon = Icons.Default.Fullscreen,
                        contentDescription = "Fullscreen",
                        size = 38.dp,
                        iconSize = 20.dp,
                        onClick = onRequestExpand,
                    )
                    CircleActionButton(
                        icon = Icons.Default.Close,
                        contentDescription = "Close",
                        size = 38.dp,
                        iconSize = 20.dp,
                        onClick = onClose,
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(1.dp, colors.outlineVariant.copy(alpha = 0.5f), cardShape),
        )
    }
}

/**
 * 大窗展开态核心视图（独立卡片 Window 内部流体连续膨胀，零外部遮罩，事件完全穿透）。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ExpandedCardOverlay(
    dockSide: DockSide,
    targetWidth: Dp,
    targetHeight: Dp,
    miniWidth: Dp,
    miniHeight: Dp,
    frames: StateFlow<Bitmap?>,
    stage: StateFlow<VirtualDisplayStage>,
    vdWidth: Int,
    vdHeight: Int,
    onFirstFrameReady: () -> Unit,
    onRequestCollapse: () -> Unit,
    onCollapseFinished: () -> Unit,
    onClose: () -> Unit,
    onNavKey: (Int) -> Unit,
    onEnterAppPicker: () -> Unit,
    onReturnFromAppPicker: () -> Boolean,
    onDrag: (Float, Float) -> Unit,
    onTouch: (MotionEvent) -> Unit,
    onLaunchApp: (String) -> Unit,
) {
    val frame by frames.collectAsState()
    val currentStage by stage.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val transformMatrix = remember { Matrix() }
    var viewWidthPx by remember { mutableIntStateOf(0) }
    var viewHeightPx by remember { mutableIntStateOf(0) }

    val initialStill = remember { copyStill(frames.value) }

    val animProgress = remember { Animatable(0f) }
    var isClosing by remember { mutableStateOf(false) }
    var frozenFrameForCollapse by remember { mutableStateOf<Bitmap?>(null) }
    val firstFrameReported = remember { booleanArrayOf(false) }

    LaunchedEffect(Unit) {
        animProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        )
    }

    fun startCollapse() {
        if (!isClosing) {
            isClosing = true
            frozenFrameForCollapse = copyStill(frames.value)
            coroutineScope.launch {
                val dropQuality = launch {
                    snapshotFlow { animProgress.value }.first { it <= COLLAPSE_LIVE_UNTIL }
                    onRequestCollapse()
                }
                animProgress.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
                )
                dropQuality.join()
                onCollapseFinished()
            }
        }
    }

    val p = animProgress.value
    val currentWidth = lerp(miniWidth, targetWidth, p)
    val currentHeight = lerp(miniHeight, targetHeight, p)
    val cardOffsetX = if (dockSide.isRight) targetWidth - currentWidth else 0.dp
    val cardOffsetY = (targetHeight - currentHeight) / 2
    val currentCornerRadius = lerp(8.dp, 16.dp, p)
    val liveFrame = frame?.takeIf { !it.isRecycled }
    val heldStill = (if (isClosing) frozenFrameForCollapse else initialStill)
        ?.takeIf { !it.isRecycled }
    val wantLive = morphShowsLive(p, isClosing)
    val showingLive = wantLive && liveFrame != null
    val morphBlurDp = morphBlurRadius(p, isClosing).dp
    val frameBlurRadiusDp = if (showingLive || !wantLive) morphBlurDp else MORPH_BLUR_HOLD.dp
    val morphSource = when {
        showingLive -> liveFrame
        heldStill != null -> heldStill
        else -> liveFrame
    }

    val topBarHeight = lerp(0.dp, 24.dp, p)
    val bottomBarHeight = lerp(0.dp, 40.dp, p)
    val barContentAlpha = ((p - 0.35f) / 0.65f).coerceIn(0f, 1f)

    LaunchedEffect(p) {
        if (!firstFrameReported[0]) {
            firstFrameReported[0] = true
            onFirstFrameReady()
        }
    }

    val colors = MaterialTheme.colorScheme
    val cardShape = G2CardShape(currentCornerRadius)

    Box(
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            modifier = Modifier
                .offset(x = cardOffsetX, y = cardOffsetY)
                .size(currentWidth, currentHeight)
                .clip(cardShape)
                .background(colors.surfaceContainerHigh),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(topBarHeight)
                        .clipToBounds()
                        .background(Color.Black.copy(alpha = 0.52f * p))
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                if (p >= 0.99f) {
                                    change.consume()
                                    onDrag(dragAmount.x, dragAmount.y)
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (barContentAlpha > 0f) {
                        Box(
                            modifier = Modifier
                                .size(width = 34.dp, height = 4.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.38f * barContentAlpha)),
                        )
                    }
                }

                val morphBitmap = remember(morphSource) { morphSource?.asImageBitmap() }
                val isRunningWithFrame = currentStage is VirtualDisplayStage.Running && morphBitmap != null
                val touchInjectModifier = if (isRunningWithFrame) {
                    Modifier.pointerInteropFilter { motionEvent ->
                        if (p >= 0.99f && !isClosing && viewWidthPx > 0 && viewHeightPx > 0) {
                            val clonedEvent = MotionEvent.obtain(motionEvent)
                            val scaleX = vdWidth.toFloat() / viewWidthPx.toFloat()
                            val scaleY = vdHeight.toFloat() / viewHeightPx.toFloat()
                            transformMatrix.setScale(scaleX, scaleY)
                            clonedEvent.transform(transformMatrix)
                            onTouch(clonedEvent)
                            clonedEvent.recycle()
                            true
                        } else {
                            false
                        }
                    }
                } else {
                    Modifier
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .onSizeChanged { size ->
                            viewWidthPx = size.width
                            viewHeightPx = size.height
                        }
                        .then(touchInjectModifier),
                    contentAlignment = Alignment.Center,
                ) {
                    when (currentStage) {
                        is VirtualDisplayStage.AppPicker -> {
                            var pickerApps by remember { mutableStateOf<List<VirtualDisplayAppItem>>(emptyList()) }
                            var isPickerLoading by remember { mutableStateOf(true) }
                            val recentPackages by RecentVirtualDisplayAppsStore.recentPackages.collectAsState()
                            LaunchedEffect(Unit) {
                                RecentVirtualDisplayAppsStore.ensureLoaded()
                                pickerApps = loadVirtualDisplayAppItems()
                                isPickerLoading = false
                            }
                            val recentApps = remember(recentPackages, pickerApps) {
                                resolveRecentAppItems(recentPackages, pickerApps)
                            }
                            val pickerAlpha = ((p - 0.50f) / 0.40f).coerceIn(0f, 1f)
                            val standbyAlpha = ((0.60f - p) / 0.40f).coerceIn(0f, 1f)
                            val blurModifier = if (morphBlurDp > 0.dp) Modifier.blur(morphBlurDp) else Modifier

                            if (pickerAlpha > 0f) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .alpha(pickerAlpha)
                                        .then(blurModifier),
                                ) {
                                    VirtualDisplayAppPicker(
                                        recentApps = recentApps,
                                        allApps = pickerApps,
                                        isLoading = isPickerLoading,
                                        compact = true,
                                        enabled = p >= 0.99f && !isClosing,
                                        onSelectApp = onLaunchApp,
                                    )
                                }
                            }
                            if (standbyAlpha > 0f) {
                                AppPickerStandbyGlyph(
                                    modifier = Modifier.alpha(standbyAlpha),
                                )
                            }
                        }

                        is VirtualDisplayStage.Launching -> {
                            ZafiroLoadingIndicator(
                                color = colors.primary,
                                size = 28.dp,
                                petalSize = 28.dp,
                                strokeWidth = 2.5.dp,
                            )
                        }

                        is VirtualDisplayStage.Running -> {
                            if (morphBitmap != null) {
                                Image(
                                    bitmap = morphBitmap,
                                    contentDescription = "Screen preview",
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .then(if (frameBlurRadiusDp > 0.dp) Modifier.blur(frameBlurRadiusDp) else Modifier),
                                    contentScale = ContentScale.FillBounds,
                                )
                            } else {
                                ZafiroLoadingIndicator(
                                    color = colors.primary,
                                    size = 28.dp,
                                    petalSize = 28.dp,
                                    strokeWidth = 2.5.dp,
                                )
                            }
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(bottomBarHeight)
                        .clipToBounds()
                        .background(Color.Black.copy(alpha = 0.52f * p)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (barContentAlpha > 0f) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp)
                                .alpha(barContentAlpha),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            NavKeyButton(
                                icon = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                onClick = {
                                    if (p >= 0.9f && !isClosing) {
                                        if (currentStage is VirtualDisplayStage.AppPicker) {
                                            if (!onReturnFromAppPicker()) {
                                                onNavKey(KeyEvent.KEYCODE_BACK)
                                            }
                                        } else {
                                            onNavKey(KeyEvent.KEYCODE_BACK)
                                        }
                                    }
                                },
                            )
                            NavKeyButton(
                                icon = IconNavHome,
                                contentDescription = "Home",
                                onClick = {
                                    if (p >= 0.9f && !isClosing) {
                                        onEnterAppPicker()
                                    }
                                },
                            )
                            NavKeyButton(
                                icon = IconNavMinimize,
                                contentDescription = "Collapse",
                                onClick = { if (p >= 0.9f && !isClosing) startCollapse() },
                            )
                            NavKeyButton(
                                icon = Icons.Default.Close,
                                contentDescription = "Close",
                                onClick = { if (p >= 0.9f && !isClosing) onClose() },
                            )
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(1.dp, colors.outlineVariant.copy(alpha = 0.5f), cardShape),
            )
        }
    }
}

private const val MORPH_BLUR_HOLD = 20f
private const val EXPAND_LIVE_FROM = 0.86f
private const val COLLAPSE_LIVE_UNTIL = 0.88f

/**
 * 形变期间盖住画面的模糊。
 * 展开：中段保持模糊，实时画面在模糊退尽前接上，最后一段才对焦。
 * 收起：先在实时画面上模糊，再换成静图缩小。
 */
private fun morphBlurRadius(progress: Float, closing: Boolean): Float {
    val p = progress.coerceIn(0f, 1f)
    val radius = if (closing) {
        when {
            p > 0.90f -> MORPH_BLUR_HOLD * ((1f - p) / 0.10f)
            p > 0.22f -> MORPH_BLUR_HOLD
            else -> MORPH_BLUR_HOLD * (p / 0.22f)
        }
    } else {
        when {
            p < 0.16f -> MORPH_BLUR_HOLD * (p / 0.16f)
            p < 0.92f -> MORPH_BLUR_HOLD
            else -> MORPH_BLUR_HOLD * ((1f - p) / 0.08f)
        }
    }
    return radius.coerceIn(0f, MORPH_BLUR_HOLD)
}

private fun morphShowsLive(progress: Float, closing: Boolean): Boolean {
    return if (closing) progress > COLLAPSE_LIVE_UNTIL else progress >= EXPAND_LIVE_FROM
}

private fun copyStill(source: Bitmap?): Bitmap? {
    if (source == null || source.isRecycled) return null
    return runCatchingCancellable {
        source.copy(source.config ?: Bitmap.Config.ARGB_8888, false)
    }.getOrNull()
}

@Composable
private fun CircleActionButton(
    icon: ImageVector,
    contentDescription: String?,
    size: Dp = 40.dp,
    iconSize: Dp = 22.dp,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.50f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true, radius = size / 2),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(iconSize),
            tint = Color.White.copy(alpha = 0.95f),
        )
    }
}

@Composable
private fun NavKeyButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true, radius = 18.dp),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(20.dp),
            tint = Color.White.copy(alpha = 0.88f),
        )
    }
}

private val IconNavHome: ImageVector by lazy {
    ImageVector.Builder(
        name = "NavHome",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2.2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(12f, 4f)
            lineTo(20f, 10.5f)
            lineTo(20f, 20f)
            lineTo(4f, 20f)
            lineTo(4f, 10.5f)
            close()
        }
    }.build()
}

private val IconNavMinimize: ImageVector by lazy {
    ImageVector.Builder(
        name = "NavMinimize",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2.4f,
            strokeLineCap = StrokeCap.Round,
        ) {
            moveTo(6f, 12f)
            lineTo(18f, 12f)
        }
    }.build()
}
