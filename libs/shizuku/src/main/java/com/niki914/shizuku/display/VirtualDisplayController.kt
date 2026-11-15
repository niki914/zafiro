package com.niki914.shizuku.display

import android.content.Context
import android.os.Binder
import android.view.Surface
import com.niki914.logging.Logger
import com.niki914.shizuku.ShizukuClient
import com.niki914.shizuku.internal.runCatchingCancellable
import com.niki914.shizuku.service.ShizukuUserServiceConnection
import com.niki914.shizuku.service.ShizukuUserServiceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface StartAppResult {
    data object Ok : StartAppResult
    data object InUseOnMain : StartAppResult
    data object NotFound : StartAppResult
    data object Failed : StartAppResult
}

sealed interface VirtualDisplayPrepareResult {
    data object Ready : VirtualDisplayPrepareResult
    data object Denied : VirtualDisplayPrepareResult
    data object Unavailable : VirtualDisplayPrepareResult
    data object BindFailed : VirtualDisplayPrepareResult
}

/**
 * 虚拟屏控制器门面：纯 Kotlin 接口，隐藏 IPC 与 AIDL 实现细节。
 */
interface VirtualDisplayController {
    suspend fun prepare(): VirtualDisplayPrepareResult
    suspend fun create(surface: Surface, width: Int, height: Int, dpi: Int): Int
    suspend fun release(displayId: Int)
    suspend fun startApp(displayId: Int, packageName: String): StartAppResult
    fun close()

    companion object {
        fun create(
            context: Context,
            client: ShizukuClient = ShizukuClient.create(context),
        ): VirtualDisplayController {
            return RealVirtualDisplayController(context.applicationContext, client)
        }
    }
}

internal class RealVirtualDisplayController(
    private val context: Context,
    private val client: ShizukuClient,
) : VirtualDisplayController {

    private val hostToken = Binder()
    private var connection: ShizukuUserServiceConnection<IShizukuVirtualDisplayService>? = null

    override suspend fun prepare(): VirtualDisplayPrepareResult {
        if (!client.isInstalled) return VirtualDisplayPrepareResult.Unavailable
        if (!client.isAuthorized) {
            val granted = client.requestPermission()
            if (!granted) return VirtualDisplayPrepareResult.Denied
        }
        if (!client.isBinderAlive) return VirtualDisplayPrepareResult.Unavailable

        val active = connection
        if (active != null && active.isAlive) {
            return VirtualDisplayPrepareResult.Ready
        }

        val newConnection = ShizukuUserServiceManager.bind(
            context = context,
            serviceClass = ShizukuVirtualDisplayService::class.java,
            processSuffix = PROCESS_SUFFIX,
            asInterface = IShizukuVirtualDisplayService.Stub::asInterface,
        ) ?: return VirtualDisplayPrepareResult.BindFailed

        runCatchingCancellable { newConnection.service.registerClient(hostToken) }
        connection = newConnection
        return VirtualDisplayPrepareResult.Ready
    }

    override suspend fun create(surface: Surface, width: Int, height: Int, dpi: Int): Int =
        withContext(Dispatchers.IO) {
            val remote = connection?.service ?: return@withContext -1
            runCatchingCancellable {
                remote.createVirtualDisplay(surface, width, height, dpi)
            }.getOrDefault(-1)
        }

    override suspend fun release(displayId: Int) {
        withContext(Dispatchers.IO) {
            runCatchingCancellable { connection?.service?.releaseDisplay(displayId) }
        }
    }

    override suspend fun startApp(displayId: Int, packageName: String): StartAppResult =
        withContext(Dispatchers.IO) {
            val remote = connection?.service ?: return@withContext StartAppResult.Failed
            val code = runCatchingCancellable {
                remote.startApp(displayId, packageName)
            }.getOrDefault(ShizukuVirtualDisplayService.START_ERROR)

            when (code) {
                ShizukuVirtualDisplayService.START_OK -> StartAppResult.Ok
                ShizukuVirtualDisplayService.START_IN_USE_ON_MAIN -> StartAppResult.InUseOnMain
                ShizukuVirtualDisplayService.START_NOT_FOUND -> StartAppResult.NotFound
                else -> StartAppResult.Failed
            }
        }

    override fun close() {
        val current = connection
        connection = null
        if (current != null) {
            runCatchingCancellable { current.service.destroy() }
            runCatchingCancellable { current.close() }
        }
    }

    companion object {
        private const val TAG = "niki914_zafiro_RealVirtualDisplayController"
        private const val PROCESS_SUFFIX = "vd_service"
    }
}
