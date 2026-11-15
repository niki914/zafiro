package com.niki914.libterm.backend.shizuku.internal

import android.content.pm.PackageManager
import com.niki914.shizuku.ShizukuClient
import rikka.shizuku.Shizuku

internal fun interface ShizukuPermissionResultListener {
    fun onRequestPermissionResult(requestCode: Int, granted: Boolean)
}

internal interface ShizukuPermissionRequester {
    fun isBinderAlive(): Boolean

    fun isPermissionGranted(): Boolean

    fun requestPermission(requestCode: Int)

    fun addRequestPermissionResultListener(listener: ShizukuPermissionResultListener)

    fun removeRequestPermissionResultListener(listener: ShizukuPermissionResultListener)
}

internal class RealShizukuPermissionRequester(
    private val client: ShizukuClient = ShizukuClient.create(),
) : ShizukuPermissionRequester {
    private val listeners =
        mutableMapOf<ShizukuPermissionResultListener, Shizuku.OnRequestPermissionResultListener>()

    override fun isBinderAlive(): Boolean = client.isBinderAlive

    override fun isPermissionGranted(): Boolean = client.isAuthorized

    override fun requestPermission(requestCode: Int) {
        Shizuku.requestPermission(requestCode)
    }

    override fun addRequestPermissionResultListener(listener: ShizukuPermissionResultListener) {
        val shizukuListener =
            Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
                listener.onRequestPermissionResult(
                    requestCode = requestCode,
                    granted = grantResult == PackageManager.PERMISSION_GRANTED,
                )
            }
        synchronized(listeners) { // githooks:ignore monitor-lock 监听器映射同步
            listeners[listener] = shizukuListener
        }
        Shizuku.addRequestPermissionResultListener(shizukuListener)
    }

    override fun removeRequestPermissionResultListener(listener: ShizukuPermissionResultListener) {
        val shizukuListener = synchronized(listeners) { // githooks:ignore monitor-lock 监听器映射同步
            listeners.remove(listener)
        } ?: return
        Shizuku.removeRequestPermissionResultListener(shizukuListener)
    }
}
