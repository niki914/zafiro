package com.niki914.shizuku.service

import android.os.IInterface

/**
 * Shizuku UserService 连接句柄，支持 AutoCloseable 资源释放。
 */
interface ShizukuUserServiceConnection<T : IInterface> : AutoCloseable {
    val service: T
    val isAlive: Boolean
}
