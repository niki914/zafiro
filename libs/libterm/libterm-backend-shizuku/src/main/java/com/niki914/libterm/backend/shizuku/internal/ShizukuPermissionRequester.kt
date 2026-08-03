package com.niki914.libterm.backend.shizuku.internal

import android.content.pm.PackageManager
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

internal class RealShizukuPermissionRequester : ShizukuPermissionRequester {
    private val listeners =
        mutableMapOf<ShizukuPermissionResultListener, Shizuku.OnRequestPermissionResultListener>()

    override fun isBinderAlive(): Boolean = Shizuku.pingBinder()

    override fun isPermissionGranted(): Boolean {
        return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }

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
        synchronized(listeners) {
            listeners[listener] = shizukuListener
        }
        Shizuku.addRequestPermissionResultListener(shizukuListener)
    }

    override fun removeRequestPermissionResultListener(listener: ShizukuPermissionResultListener) {
        val shizukuListener = synchronized(listeners) {
            listeners.remove(listener)
        } ?: return
        Shizuku.removeRequestPermissionResultListener(shizukuListener)
    }
}
