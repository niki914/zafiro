package com.niki914.shizuku.display

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.IBinder
import android.view.Display
import android.view.InputEvent
import android.view.MotionEvent
import android.view.Surface
import com.niki914.logging.Logger
import com.niki914.shizuku.internal.runCatchingCancellable
import com.niki914.shizuku.privileged.HostDeathWatcher
import com.niki914.shizuku.privileged.ShellContext
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * 运行在 Shizuku 特权子进程中 (:vd_service) 的虚拟屏与应用调度服务。
 */
@SuppressLint("PrivateApi")
class ShizukuVirtualDisplayService : IShizukuVirtualDisplayService.Stub() {
    private val context: Context by lazy { ShellContext.create() }
    private val displays = ConcurrentHashMap<Int, VirtualDisplay>()
    private val launchLock = Any()

    private val deathWatcher = HostDeathWatcher {
        Logger.w(TAG, "Host process died, destroying virtual displays and terminating")
        destroy()
    }

    private val displayManager: DisplayManager by lazy {
        DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }
            .newInstance(context)
    }

    private val taskManager: Any by lazy {
        Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)!!
    }

    override fun registerClient(token: IBinder?) {
        deathWatcher.linkTo(token)
    }

    override fun createVirtualDisplay(surface: Surface, width: Int, height: Int, dpi: Int): Int {
        try {
            return createDisplay(surface, width, height, dpi, trusted = true)
        } catch (error: SecurityException) {
            Logger.w(TAG, "Trusted virtual display rejected", error)
        } catch (error: Exception) { // githooks:ignore bare-catch 保护跨进程服务不因未知图形异常崩溃
            Logger.e(TAG, "Trusted virtual display unexpected failure", error)
        }
        return try {
            createDisplay(surface, width, height, dpi, trusted = false)
        } catch (error: Exception) { // githooks:ignore bare-catch 保护跨进程服务不因未知图形异常崩溃
            Logger.e(TAG, "Virtual display fallback rejected", error)
            -1
        }
    }

    override fun releaseDisplay(displayId: Int) {
        displays.remove(displayId)?.let { display ->
            runCatchingCancellable { display.release() }
        }
    }

    override fun startApp(displayId: Int, packageName: String): Int {
        synchronized(launchLock) { // githooks:ignore monitor-lock 前台检查和启动必须一次做完，避免把用户正在看的应用拉走
            if (!displays.containsKey(displayId)) return START_ERROR
            if (isForegroundOnMain(packageName)) return START_IN_USE_ON_MAIN
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
                ?: return START_NOT_FOUND
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
            val started = runCatchingCancellable {
                val startMethod = taskManager.javaClass.methods.first {
                    it.name == "startActivityAsUser" && it.parameterTypes.size == 12
                }
                val result = startMethod.invoke(
                    taskManager,
                    null,
                    ShellContext.SHELL_PACKAGE,
                    null,
                    launchIntent,
                    null,
                    null,
                    null,
                    0,
                    0,
                    null,
                    options,
                    USER_CURRENT,
                ) as Int
                result >= 0
            }.getOrElse { error ->
                Logger.w(TAG, "startActivityAsUser failed", error)
                val component = launchIntent.component?.flattenToShortString() ?: return START_NOT_FOUND
                executeShellCommand("am", "start", "--display", "$displayId", "-n", component)
            }
            return if (started) START_OK else START_ERROR
        }
    }

    override fun destroy() {
        deathWatcher.unlink()
        displays.values.forEach { display ->
            runCatchingCancellable { display.release() }
        }
        displays.clear()
        exitProcess(0)
    }

    override fun injectMotionEvent(displayId: Int, event: MotionEvent?) {
        if (event == null) return
        try {
            InputInjector.inject(displayId, event)
        } finally {
            runCatchingCancellable { event.recycle() }
        }
    }

    private fun createDisplay(
        surface: Surface,
        width: Int,
        height: Int,
        dpi: Int,
        trusted: Boolean,
    ): Int {
        val flags = DisplayFlags.compute(Build.VERSION.SDK_INT, trusted)
        val display = displayManager.createVirtualDisplay(
            DISPLAY_NAME,
            width,
            height,
            dpi,
            surface,
            flags,
        ) ?: return -1
        val id = display.display.displayId
        displays[id] = display
        setImePolicy(id)
        return id
    }

    private fun setImePolicy(displayId: Int) {
        runCatchingCancellable {
            val windowGlobal = Class.forName("android.view.WindowManagerGlobal")
            val window = windowGlobal.getMethod("getWindowManagerService").invoke(null)!!
            window.javaClass.getMethod("setDisplayImePolicy", Int::class.java, Int::class.java)
                .invoke(window, displayId, IME_POLICY_LOCAL)
        }.onFailure { error ->
            Logger.w(TAG, "setDisplayImePolicy failed for $displayId", error)
        }
    }

    private fun isForegroundOnMain(packageName: String): Boolean {
        return runCatchingCancellable {
            val method = taskManager.javaClass.getMethod("getAllRootTaskInfosOnDisplay", Int::class.java)
            val tasks = method.invoke(taskManager, Display.DEFAULT_DISPLAY) as? List<*> ?: return false
            val top = tasks.firstOrNull() ?: return false
            val component = top.javaClass.getField("topActivity").get(top) as? ComponentName
            component?.packageName == packageName
        }.getOrDefault(false)
    }

    private fun executeShellCommand(vararg command: String): Boolean {
        return runCatchingCancellable {
            val process = Runtime.getRuntime().exec(command)
            val finished = process.waitFor(5, TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                return false
            }
            process.exitValue() == 0
        }.getOrDefault(false)
    }

    companion object {
        private const val TAG = "niki914_zafiro_ShizukuVirtualDisplayService"
        private const val DISPLAY_NAME = "zafiro-agent-vd"
        private const val USER_CURRENT = -2
        private const val IME_POLICY_LOCAL = 0

        const val START_OK = 0
        const val START_IN_USE_ON_MAIN = 1
        const val START_NOT_FOUND = 2
        const val START_ERROR = 3
    }
}

@SuppressLint("PrivateApi")
private object InputInjector {
    private const val TAG = "niki914_zafiro_InputInjector"
    private const val INJECT_INPUT_EVENT_MODE_ASYNC = 0

    private val inputManagerInstance: Any?
    private val injectMethod: Method?
    private val setDisplayIdMethod: Method?

    init {
        var im: Any? = null
        var inject: Method? = null
        var setDisplayId: Method? = null

        try {
            val inputManagerClass = runCatchingCancellable {
                Class.forName("android.hardware.input.InputManagerGlobal")
            }.getOrElse {
                Class.forName("android.hardware.input.InputManager")
            }
            im = inputManagerClass.getMethod("getInstance").invoke(null)
            inject = inputManagerClass.getMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }

            setDisplayId = runCatchingCancellable {
                InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
            }.getOrElse {
                MotionEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
            }.apply { isAccessible = true }
        } catch (e: Exception) { // githooks:ignore bare-catch 保护特权服务反射初始化失败不崩溃
            Logger.e(TAG, "Failed to initialize InputInjector reflection", e)
        }

        inputManagerInstance = im
        injectMethod = inject
        setDisplayIdMethod = setDisplayId
    }

    fun inject(displayId: Int, event: MotionEvent): Boolean {
        val im = inputManagerInstance ?: return false
        val inject = injectMethod ?: return false
        val setDisplay = setDisplayIdMethod ?: return false

        return try {
            setDisplay.invoke(event, displayId)
            inject.invoke(im, event, INJECT_INPUT_EVENT_MODE_ASYNC) as? Boolean ?: true
        } catch (e: Exception) { // githooks:ignore bare-catch 保护特权服务注入异常不崩溃
            Logger.e(TAG, "injectInputEvent failed for displayId=$displayId", e)
            false
        }
    }
}
