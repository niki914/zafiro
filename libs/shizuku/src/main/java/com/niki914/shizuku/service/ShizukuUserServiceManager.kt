package com.niki914.shizuku.service

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.os.IInterface
import com.niki914.logging.Logger
import com.niki914.shizuku.internal.runCatchingCancellable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 封装 Shizuku UserService 的绑定、超时与解绑生命周期。
 */
object ShizukuUserServiceManager {
    private const val TAG = "niki914_zafiro_ShizukuUserServiceManager"

    suspend fun <T : IInterface> bind(
        context: Context,
        serviceClass: Class<*>,
        processSuffix: String,
        daemon: Boolean = false,
        timeoutMillis: Long = 5_000L,
        asInterface: (IBinder) -> T,
    ): ShizukuUserServiceConnection<T>? {
        val args = Shizuku.UserServiceArgs(
            ComponentName(context.packageName, serviceClass.name),
        ).apply {
            daemon(daemon)
            debuggable(false)
            processNameSuffix(processSuffix)
        }

        val deferred = CompletableDeferred<T?>()
        val isBound = AtomicBoolean(false)

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder != null && binder.isBinderAlive) {
                    val serviceInstance = asInterface(binder)
                    deferred.complete(serviceInstance)
                } else {
                    deferred.complete(null)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                Logger.w(TAG, "UserService disconnected: $name")
                deferred.complete(null)
            }

            override fun onBindingDied(name: ComponentName?) {
                Logger.w(TAG, "UserService binding died: $name")
                deferred.complete(null)
            }

            override fun onNullBinding(name: ComponentName?) {
                Logger.w(TAG, "UserService null binding: $name")
                deferred.complete(null)
            }
        }

        val success = withContext(Dispatchers.Main.immediate) {
            runCatchingCancellable {
                Shizuku.bindUserService(args, connection)
                isBound.set(true)
                true
            }.getOrDefault(false)
        }
        if (!success) return null

        val remote = withTimeoutOrNull(timeoutMillis) { deferred.await() }
        if (remote == null) {
            Logger.w(TAG, "bindUserService timed out after ${timeoutMillis}ms for ${serviceClass.simpleName}")
            withContext(Dispatchers.Main.immediate) {
                runCatchingCancellable { Shizuku.unbindUserService(args, connection, true) }
            }
            return null
        }

        return object : ShizukuUserServiceConnection<T> {
            override val service: T = remote
            override val isAlive: Boolean
                get() = service.asBinder().isBinderAlive

            override fun close() {
                if (isBound.compareAndSet(true, false)) {
                    runCatchingCancellable {
                        Shizuku.unbindUserService(args, connection, true)
                    }
                }
            }
        }
    }
}
