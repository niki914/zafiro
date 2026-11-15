package com.niki914.zafiro.app.screen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.niki914.logging.Logger
import com.niki914.uikit.base.BaseTheme
import com.niki914.uikit.base.LocalThemeStyle
import com.niki914.uikit.base.ThemeStyle
import com.niki914.uikit.infra.shape.G2CardShape
import com.niki914.zafiro.accessibility.driver.SystemAccessibilityDriver
import com.niki914.zafiro.api.util.runCatchingCancellable
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.overlay.OverlayLifecycleOwner
import com.niki914.zafiro.app.ui.model.ThemeController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * 虚拟屏小窗悬浮预览（Mini PIP 模式）。
 *
 * 核心特征：
 * - 纯净三态渲染：待命状态（居中单色 Logo）、启动过渡态（风格化加载指示器）、推流态（实时 Bitmap）；
 * - 播放器级交互黑幕：有焦点时浮现渐变黑幕与右上角垂直双圆钮（全屏、关闭），无焦点时零遮挡；
 * - 物理贴边吸附：集成悬浮球抛掷预测与高阻尼物理模型。
 */
internal object VirtualDisplayCard {
    private const val TAG = "niki914_zafiro_VirtualDisplayCard"

    private var windowManager: WindowManager? = null
    private var touchLayout: VirtualDisplayTouchLayout? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null

    @Volatile
    var isShowing: Boolean = false
        private set

    suspend fun show(
        frames: StateFlow<Bitmap?>,
        status: StateFlow<VirtualDisplayStatus>,
        onClose: () -> Unit = {},
        onExpand: () -> Unit = {},
    ): Boolean = withContext(Dispatchers.Main.immediate) {
        if (isShowing) hideOnMain()
        val context = SystemAccessibilityDriver.serviceContext ?: return@withContext false
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return@withContext false
        val density = context.resources.displayMetrics.density
        val screenWidth = context.resources.displayMetrics.widthPixels
        val widthPx = (100 * density).toInt()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth - widthPx - (16 * density).toInt()
            y = (200 * density).toInt()
        }
        val owner = OverlayLifecycleOwner().apply {
            onCreate()
            onStart()
            onResume()
        }

        val isFocusedState = mutableStateOf(false)
        val touchEpochState = mutableLongStateOf(0L)

        val root = VirtualDisplayTouchLayout(
            context = context,
            manager = manager,
            params = params,
            onOutsideTouch = {
                isFocusedState.value = false
            },
        ).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
        }

        val composeView = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
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
                ) {
                    val frame by frames.collectAsState()
                    val currentStatus by status.collectAsState()
                    var isFocused by isFocusedState
                    var touchEpoch by touchEpochState

                    PreviewCard(
                        frame = frame,
                        status = currentStatus,
                        isFocused = isFocused,
                        touchEpoch = touchEpoch,
                        onToggleFocus = {
                            isFocused = !isFocused
                            touchEpoch = SystemClock.uptimeMillis()
                        },
                        onFocusTimeout = {
                            isFocused = false
                        },
                        onExpand = onExpand,
                        onClose = onClose,
                    )
                }
            }
        }
        root.addView(composeView)
        val added = runCatchingCancellable {
            manager.addView(root, params)
            true
        }.getOrElse { error ->
            Logger.e(TAG, "overlay add failed", error)
            owner.onDestroy()
            false
        }
        if (!added) return@withContext false
        windowManager = manager
        touchLayout = root
        lifecycleOwner = owner
        isShowing = true
        true
    }

    suspend fun hide() = withContext(Dispatchers.Main.immediate) { hideOnMain() }

    fun hideOnMain() {
        val layout = touchLayout
        val manager = windowManager
        val owner = lifecycleOwner
        touchLayout = null
        windowManager = null
        lifecycleOwner = null
        isShowing = false
        owner?.onPause()
        owner?.onStop()
        owner?.onDestroy()
        if (layout != null && manager != null) {
            runCatchingCancellable {
                manager.removeView(layout)
            }.onFailure { error ->
                Logger.w(TAG, "overlay already gone", error)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PreviewCard(
    frame: Bitmap?,
    status: VirtualDisplayStatus,
    isFocused: Boolean,
    touchEpoch: Long,
    onToggleFocus: () -> Unit,
    onFocusTimeout: () -> Unit,
    onExpand: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val aspect = remember(context, frame) {
        if (frame != null && frame.width > 0 && frame.height > 0) {
            frame.height.toFloat() / frame.width.toFloat()
        } else {
            val (width, height, _) = virtualDisplaySize(context)
            if (width > 0 && height > 0) {
                height.toFloat() / width.toFloat()
            } else {
                2.2f
            }
        }
    }

    // 焦点 3.5 秒无交互自动超时淡出
    LaunchedEffect(isFocused, touchEpoch) {
        if (isFocused) {
            delay(3500L)
            onFocusTimeout()
        }
    }

    val width = 100.dp
    val shape = G2CardShape(8.dp)
    val cardBackground = MaterialTheme.colorScheme.surfaceContainer
    val borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)

    Box(
        modifier = Modifier
            .size(width, width * aspect)
            .clip(shape)
            .background(cardBackground)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggleFocus,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // --- 核心内容三态渲染 ---
        if (frame != null && !frame.isRecycled) {
            val bitmap = remember(frame) { frame.asImageBitmap() }
            Image(
                bitmap = bitmap,
                contentDescription = "Screen preview",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
            )
        } else if (status == VirtualDisplayStatus.Launching) {
            val themeStyle = LocalThemeStyle.current
            if (themeStyle == ThemeStyle.Material3Expressive) {
                LoadingIndicator(
                    modifier = Modifier.size(32.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(26.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.5.dp,
                )
            }
        } else {
            // 待命状态（Idle）：纯 Logo，无额外文本，汲取主页配色
            Icon(
                painter = painterResource(R.drawable.ic_launcher_monochrome),
                contentDescription = "Ready standby",
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                modifier = Modifier.size(44.dp),
            )
        }

        // --- 播放器级黑幕与水平居中、竖向均匀分布双按钮 ---
        AnimatedVisibility(
            visible = isFocused,
            enter = fadeIn(animationSpec = tween(220)),
            exit = fadeOut(animationSpec = tween(220)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.48f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 24.dp),
                    verticalArrangement = Arrangement.SpaceEvenly,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircleActionButton(
                        icon = Icons.Default.Fullscreen,
                        contentDescription = "Fullscreen",
                        onClick = onExpand,
                    )
                    CircleActionButton(
                        icon = Icons.Default.Close,
                        contentDescription = "Close",
                        onClick = onClose,
                    )
                }
            }
        }

        // --- 最外层边框：严格贴合包裹虚拟屏，确保零侧漏 ---
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(1.dp, borderColor, shape),
        )
    }
}

@Composable
private fun CircleActionButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.50f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true, radius = 20.dp),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(22.dp),
            tint = Color.White.copy(alpha = 0.95f),
        )
    }
}
