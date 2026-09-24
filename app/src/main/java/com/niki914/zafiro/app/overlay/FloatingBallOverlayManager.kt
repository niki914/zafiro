package com.niki914.zafiro.app.overlay

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
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
import com.niki914.zafiro.app.MainActivity
import com.niki914.zafiro.app.ui.model.ThemeController
import com.niki914.zafiro.remoteview.floatingball.FloatingBallCollapsedBall
import com.niki914.zafiro.remoteview.floatingball.FloatingBallExpandedCard

/**
 * 悬浮球 WindowManager 承载管理器。
 *
 * 在非 Activity 窗口中挂载 ComposeView，并提供独立的 Lifecycle、SavedStateRegistry
 * 与 ViewModelStore，使 BaseTheme 及 Material 组件安全渲染。
 */
object FloatingBallOverlayManager {

    private const val TAG = "FloatingBallOverlay"
    private const val INITIAL_X_DP = 24
    private const val INITIAL_Y_DP = 120

    private val mainHandler = Handler(Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var rootView: FrameLayout? = null // TODO 编译器有内存泄露警告, 这个做法其实确实不是特别好
    private var lifecycleOwner: OverlayLifecycleOwner? = null

    val isShowing: Boolean
        get() = rootView != null

    fun show(context: Context) {
        mainHandler.post {
            if (rootView != null) return@post

            val appContext = context.applicationContext
            val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager = wm

            val density = appContext.resources.displayMetrics.density
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (INITIAL_X_DP * density).toInt()
                y = (INITIAL_Y_DP * density).toInt()
            }

            val owner = OverlayLifecycleOwner().apply {
                onCreate()
                onStart()
                onResume()
            }
            lifecycleOwner = owner

            val frame = FrameLayout(appContext).apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                setViewTreeViewModelStoreOwner(owner)
            }

            val composeView = ComposeView(appContext).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent {
                    val themePrefs = ThemeController.prefs
                    val isSystemDark = isSystemInDarkTheme()
                    val isDark = themePrefs.resolveDarkTheme(isSystemDark)
                    val seed = themePrefs.seedColor?.let { Color(it) }

                    BaseTheme(
                        darkTheme = isDark,
                        dynamicColor = themePrefs.seedColor == null,
                        seedColor = seed,
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            horizontalAlignment = Alignment.Start,
                        ) {
                            FloatingBallExpandedCard(
                                preview = "Agent Msg Pre-\n-view..",
                                onJumpToApp = {
                                    val intent = Intent(appContext, MainActivity::class.java).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                                    }
                                    appContext.startActivity(intent)
                                },
                                onStop = {
                                    Logger.i(TAG, "FloatingBall: Stop clicked")
                                },
                                onMinimize = {
                                    Logger.i(TAG, "FloatingBall: Minimize clicked")
                                },
                            )

                            FloatingBallCollapsedBall(
                                onClick = {
                                    Logger.i(TAG, "FloatingBall: Collapsed ball clicked")
                                },
                            )
                        }
                    }
                }
            }

            frame.addView(composeView)
            rootView = frame

            try {
                wm.addView(frame, lp)
                Logger.i(TAG, "FloatingBall window added to WindowManager")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to add FloatingBall window", e)
                dismiss()
            }
        }
    }

    fun dismiss() {
        mainHandler.post {
            val view = rootView ?: return@post
            val wm = windowManager

            try {
                wm?.removeViewImmediate(view)
            } catch (e: Exception) {
                Logger.w(TAG, "Failed to remove FloatingBall window", e)
            }

            lifecycleOwner?.let {
                it.onPause()
                it.onStop()
                it.onDestroy()
            }

            rootView = null
            windowManager = null
            lifecycleOwner = null
            Logger.i(TAG, "FloatingBall window dismissed")
        }
    }

    private class OverlayLifecycleOwner :
        LifecycleOwner,
        SavedStateRegistryOwner,
        ViewModelStoreOwner {

        private val lifecycleRegistry = LifecycleRegistry(this)
        private val savedStateRegistryController = SavedStateRegistryController.create(this)
        private val store = ViewModelStore()

        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
        override val viewModelStore: ViewModelStore get() = store

        fun onCreate() {
            savedStateRegistryController.performRestore(null)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        }

        fun onStart() {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        }

        fun onResume() {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        fun onPause() {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        }

        fun onStop() {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }

        fun onDestroy() {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            store.clear()
        }
    }
}
