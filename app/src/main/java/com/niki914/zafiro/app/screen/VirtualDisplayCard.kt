package com.niki914.zafiro.app.screen

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.niki914.logging.Logger
import com.niki914.uikit.base.BaseTheme
import com.niki914.uikit.infra.shape.G2CardShape
import com.niki914.zafiro.accessibility.driver.SystemAccessibilityDriver
import com.niki914.zafiro.api.util.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlin.math.hypot

/**
 * 只能拖的虚拟屏预览。触摸留在卡片上，不进虚拟屏。
 * 窗口挂在无障碍服务上。加不上去时返回 false，调用方释放刚建的屏。
 */
internal object VirtualDisplayCard {
    private const val TAG = "niki914_zafiro_VirtualDisplayCard"

    private var windowManager: WindowManager? = null
    private var touchLayout: DragLayout? = null
    private var lifecycleOwner: OverlayLifecycle? = null

    @Volatile
    var isShowing: Boolean = false
        private set

    suspend fun show(frames: StateFlow<Bitmap?>): Boolean = withContext(Dispatchers.Main.immediate) {
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
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth - widthPx - (16 * density).toInt()
            y = (200 * density).toInt()
        }
        val owner = OverlayLifecycle().apply {
            performRestore(null)
            handle(Lifecycle.Event.ON_CREATE)
            handle(Lifecycle.Event.ON_START)
            handle(Lifecycle.Event.ON_RESUME)
        }
        val root = DragLayout(context, manager, params).apply {
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
                val frame by frames.collectAsState()
                BaseTheme { PreviewCard(frame) }
            }
        }
        root.addView(composeView)
        val added = runCatchingCancellable {
            manager.addView(root, params)
            true
        }.getOrElse { error ->
            Logger.e(TAG, "overlay add failed", error)
            owner.handle(Lifecycle.Event.ON_DESTROY)
            owner.clear()
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
        owner?.handle(Lifecycle.Event.ON_PAUSE)
        owner?.handle(Lifecycle.Event.ON_STOP)
        owner?.handle(Lifecycle.Event.ON_DESTROY)
        owner?.clear()
        if (layout != null && manager != null) {
            runCatchingCancellable {
                manager.removeView(layout)
            }.onFailure { error ->
                Logger.w(TAG, "overlay already gone", error)
            }
        }
    }
}

@Composable
private fun PreviewCard(frame: Bitmap?) {
    val context = LocalContext.current
    val aspect = remember(context) {
        val metrics = context.resources.displayMetrics
        if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
            metrics.heightPixels.toFloat() / metrics.widthPixels.toFloat()
        } else {
            2.2f
        }
    }
    val width = 100.dp
    val shape = G2CardShape(6.dp)
    Box(
        modifier = Modifier
            .size(width, width * aspect)
            .clip(shape)
            .background(Color(0xF0121216))
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), shape),
        contentAlignment = Alignment.Center,
    ) {
        if (frame != null && !frame.isRecycled) {
            val bitmap = remember(frame) { frame.asImageBitmap() }
            Image(
                bitmap = bitmap,
                contentDescription = "Screen preview",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 2.dp,
            )
        }
    }
}

@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
private class DragLayout(
    context: Context,
    private val manager: WindowManager,
    private val params: WindowManager.LayoutParams,
) : FrameLayout(context) {
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var dragging = false
    private var currentX = params.x
    private var currentY = params.y
    private var animator: ValueAnimator? = null

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animator?.cancel()
                currentX = params.x
                currentY = params.y
                downRawX = event.rawX
                downRawY = event.rawY
                lastRawX = event.rawX
                lastRawY = event.rawY
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val distance = hypot(
                    (event.rawX - downRawX).toDouble(),
                    (event.rawY - downRawY).toDouble(),
                ).toFloat()
                if (distance > touchSlop) {
                    dragging = true
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - lastRawX
                val dy = event.rawY - lastRawY
                lastRawX = event.rawX
                lastRawY = event.rawY
                val density = context.resources.displayMetrics.density
                val screenHeight = context.resources.displayMetrics.heightPixels
                val minY = (32 * density).toInt()
                val maxY = (screenHeight - height - minY).coerceAtLeast(minY)
                currentX += dx.toInt()
                currentY = (currentY + dy.toInt()).coerceIn(minY, maxY)
                params.x = currentX
                params.y = currentY
                if (isAttachedToWindow) manager.updateViewLayout(this, params)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    snapToEdge()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun snapToEdge() {
        val density = context.resources.displayMetrics.density
        val screenWidth = context.resources.displayMetrics.widthPixels
        val margin = (16 * density).toInt()
        val targetX = if (currentX + width / 2 < screenWidth / 2) {
            margin
        } else {
            (screenWidth - width - margin).coerceAtLeast(margin)
        }
        animator?.cancel()
        animator = ValueAnimator.ofInt(currentX, targetX).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                currentX = it.animatedValue as Int
                params.x = currentX
                if (isAttachedToWindow) manager.updateViewLayout(this@DragLayout, params)
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }
}

private class OverlayLifecycle :
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    fun handle(event: Lifecycle.Event) = registry.handleLifecycleEvent(event)
    fun performRestore(state: Bundle?) = savedState.performRestore(state)
    fun clear() = store.clear()
}
