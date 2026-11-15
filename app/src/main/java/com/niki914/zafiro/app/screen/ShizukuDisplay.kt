package com.niki914.zafiro.app.screen

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.Application
import android.app.Instrumentation
import android.content.AttributionSource
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.view.Display
import android.view.Surface
import com.niki914.logging.Logger
import com.niki914.zafiro.api.util.runCatchingCancellable
import com.niki914.zafiro.business.permission.Permission
import com.niki914.zafiro.business.permission.PermissionManager
import com.niki914.zafiro.business.permission.PermissionState
import com.niki914.zafiro.service.requireService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * 临时的 Shizuku 虚拟屏入口。绑定、授权和特权进程都在这一个文件里，方便以后整文件搬走。
 * 授权只走 [PermissionManager]，不自己弹 Shizuku 框。
 */
internal object ShizukuDisplay {
    private const val TAG = "niki914_zafiro_ShizukuDisplay"
    private const val BIND_TIMEOUT_MS = 5_000L
    private const val PROCESS_SUFFIX = "vd_service"

    private val connecting = AtomicBoolean(false)
    private var service: IVirtualDisplayService? = null
    private var pending: CompletableDeferred<IVirtualDisplayService?>? = null
    private var appContext: Context? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val remote = IVirtualDisplayService.Stub.asInterface(binder)
            service = remote
            pending?.complete(remote)
            connecting.set(false)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            connecting.set(false)
        }
    }

    suspend fun prepare(context: Context): PrepareResult {
        val manager = requireService<PermissionManager>()
        if (manager.status(Permission.SHIZUKU) != PermissionState.GRANTED) {
            val result = manager.request(Permission.SHIZUKU)
            if (result.finalState != PermissionState.GRANTED) return PrepareResult.Denied
        }
        if (!Shizuku.pingBinder()) return PrepareResult.Unavailable
        appContext = context.applicationContext
        return if (bind(context)) PrepareResult.Ready else PrepareResult.BindFailed
    }

    suspend fun create(surface: Surface, width: Int, height: Int, dpi: Int): Int =
        withContext(Dispatchers.IO) {
            val remote = service ?: return@withContext -1
            runCatchingCancellable {
                remote.createVirtualDisplay(surface, width, height, dpi)
            }.getOrDefault(-1)
        }

    suspend fun release(displayId: Int) {
        withContext(Dispatchers.IO) {
            runCatchingCancellable { service?.releaseDisplay(displayId) }
        }
    }

    suspend fun startApp(displayId: Int, packageName: String): Int =
        withContext(Dispatchers.IO) {
            val remote = service ?: return@withContext ShizukuDisplayService.START_ERROR
            runCatchingCancellable { remote.startApp(displayId, packageName) }
                .getOrDefault(ShizukuDisplayService.START_ERROR)
        }

    fun close() {
        val context = appContext
        runCatchingCancellable { service?.destroy() }
        service = null
        if (context != null) {
            val args = userServiceArgs(context)
            runCatchingCancellable { Shizuku.unbindUserService(args, connection, true) }
        }
        connecting.set(false)
        appContext = null
    }

    private suspend fun bind(context: Context): Boolean {
        service?.let { if (it.asBinder().isBinderAlive) return true }
        if (!connecting.compareAndSet(false, true)) {
            return withTimeoutOrNull(BIND_TIMEOUT_MS) { pending?.await() } != null
        }
        val deferred = CompletableDeferred<IVirtualDisplayService?>()
        pending = deferred
        val bound = runCatchingCancellable {
            Shizuku.bindUserService(userServiceArgs(context), connection)
            true
        }.getOrElse { error ->
            Logger.e(TAG, "bindUserService failed", error)
            connecting.set(false)
            false
        }
        if (!bound) return false
        val remote = withTimeoutOrNull(BIND_TIMEOUT_MS) { deferred.await() }
        if (remote == null) {
            Logger.w(TAG, "bindUserService timed out")
            connecting.set(false)
        }
        return remote != null
    }

    private fun userServiceArgs(context: Context): Shizuku.UserServiceArgs {
        return Shizuku.UserServiceArgs(
            ComponentName(context.packageName, ShizukuDisplayService::class.java.name),
        ).apply {
            daemon(false)
            debuggable(false)
            processNameSuffix(PROCESS_SUFFIX)
        }
    }
}

internal enum class PrepareResult { Ready, Denied, Unavailable, BindFailed }

internal fun computeDisplayFlags(sdk: Int, trusted: Boolean): Int {
    val flagPublic = 1 shl 0
    val flagOwnContentOnly = 1 shl 3
    val flagSupportsTouch = 1 shl 6
    val flagRotatesWithContent = 1 shl 7
    val flagDestroyContentOnRemoval = 1 shl 8
    val flagTrusted = 1 shl 10
    val flagOwnDisplayGroup = 1 shl 11
    val flagAlwaysUnlocked = 1 shl 12
    val flagTouchFeedbackDisabled = 1 shl 13
    val flagOwnFocus = 1 shl 14
    val flagStealTopFocusDisabled = 1 shl 16

    var flags = flagPublic or flagOwnContentOnly or flagSupportsTouch or
        flagRotatesWithContent or flagDestroyContentOnRemoval
    if (sdk >= Build.VERSION_CODES.TIRAMISU) {
        flags = flags or flagTouchFeedbackDisabled
        if (trusted) {
            flags = flags or flagTrusted or flagOwnDisplayGroup or flagAlwaysUnlocked
        }
    }
    if (sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && trusted) {
        flags = flags or flagOwnFocus or flagStealTopFocusDisabled
    }
    return flags
}

/** Shizuku 按这个类名启动。类必须留在本文件，并且保留无参构造。 */
@SuppressLint("PrivateApi")
class ShizukuDisplayService : IVirtualDisplayService.Stub() {
    private val context: Context by lazy { ShellContext.create() }
    private val displays = ConcurrentHashMap<Int, VirtualDisplay>()
    private val launchLock = Any()

    private val displayManager: DisplayManager by lazy {
        DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }
            .newInstance(context)
    }

    private val taskManager: Any by lazy {
        Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)!!
    }

    override fun createVirtualDisplay(surface: Surface, width: Int, height: Int, dpi: Int): Int {
        try {
            return createDisplay(surface, width, height, dpi, trusted = true)
        } catch (error: SecurityException) {
            Logger.w(TAG, "trusted virtual display rejected", error)
        }
        return try {
            createDisplay(surface, width, height, dpi, trusted = false)
        } catch (error: SecurityException) {
            Logger.e(TAG, "virtual display fallback rejected", error)
            -1
        }
    }

    override fun releaseDisplay(displayId: Int) {
        displays.remove(displayId)?.release()
    }

    override fun startApp(displayId: Int, packageName: String): Int {
        // githooks:ignore monitor-lock 前台检查和启动必须一次做完，避免把用户正在看的应用拉走
        synchronized(launchLock) {
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
        displays.values.forEach { display -> display.release() }
        displays.clear()
        exitProcess(0)
    }

    private fun createDisplay(
        surface: Surface,
        width: Int,
        height: Int,
        dpi: Int,
        trusted: Boolean,
    ): Int {
        val flags = computeDisplayFlags(Build.VERSION.SDK_INT, trusted)
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
        private const val TAG = "niki914_zafiro_ShizukuDisplayService"
        private const val DISPLAY_NAME = "zafiro-agent-vd"
        private const val USER_CURRENT = -2
        private const val IME_POLICY_LOCAL = 0

        const val START_OK = 0
        const val START_IN_USE_ON_MAIN = 1
        const val START_NOT_FOUND = 2
        const val START_ERROR = 3
    }
}

@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
private class ShellContext private constructor(base: Context) : ContextWrapper(base) {
    override fun getPackageName(): String = SHELL_PACKAGE
    override fun getOpPackageName(): String = SHELL_PACKAGE
    override fun getAttributionSource(): AttributionSource =
        AttributionSource.Builder(SHELL_UID).setPackageName(SHELL_PACKAGE).build()
    override fun getApplicationContext(): Context = this

    companion object {
        const val SHELL_PACKAGE = "com.android.shell"
        const val SHELL_UID = Process.SHELL_UID

        fun create(): Context {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val thread = activityThreadClass.getMethod("currentActivityThread").invoke(null)
                ?: createActivityThread(activityThreadClass)
            val system = activityThreadClass.getDeclaredMethod("getSystemContext").invoke(thread) as Context
            val context = ShellContext(system)
            val initialApp = activityThreadClass.getDeclaredField("mInitialApplication").apply {
                isAccessible = true
            }
            if (initialApp.get(thread) == null) {
                runCatchingCancellable {
                    val app = Instrumentation.newApplication(Application::class.java, context)
                    initialApp.set(thread, app)
                }
            }
            return context
        }

        private fun createActivityThread(activityThreadClass: Class<*>): Any {
            val thread = activityThreadClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            activityThreadClass.getDeclaredField("sCurrentActivityThread").apply {
                isAccessible = true
            }.set(null, thread)
            activityThreadClass.getDeclaredField("mSystemThread").apply {
                isAccessible = true
            }.setBoolean(thread, true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatchingCancellable {
                    val controllerClass = Class.forName("android.app.ConfigurationController")
                    val internalClass = Class.forName("android.app.ActivityThreadInternal")
                    val controller = controllerClass.getDeclaredConstructor(internalClass)
                        .apply { isAccessible = true }
                        .newInstance(thread)
                    activityThreadClass.getDeclaredField("mConfigurationController").apply {
                        isAccessible = true
                    }.set(thread, controller)
                }
            }
            runCatchingCancellable {
                val bindDataClass = Class.forName("android.app.ActivityThread\$AppBindData")
                val bindData = bindDataClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
                bindDataClass.getDeclaredField("appInfo").apply { isAccessible = true }
                    .set(bindData, ApplicationInfo().apply { packageName = SHELL_PACKAGE })
                activityThreadClass.getDeclaredField("mBoundApplication").apply { isAccessible = true }
                    .set(thread, bindData)
            }
            return thread
        }
    }
}
