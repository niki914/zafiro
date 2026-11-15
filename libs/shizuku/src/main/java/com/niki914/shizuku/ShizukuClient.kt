package com.niki914.shizuku

import android.content.Context
import com.niki914.shizuku.internal.RealShizukuClient

/**
 * Shizuku 核心客户端门面：负责状态探测、权限申请与特权单次命令执行。
 */
interface ShizukuClient {

    /** Shizuku 客户端应用是否已安装 */
    val isInstalled: Boolean

    /** Shizuku 服务端 Binder 是否可用 */
    val isBinderAlive: Boolean

    /** 当前应用是否已获取 Shizuku 权限 */
    val isAuthorized: Boolean

    /** 挂起等待 Binder 就绪（带超时） */
    suspend fun awaitBinder(timeoutMillis: Long = DEFAULT_BINDER_TIMEOUT_MS): Boolean

    /** 挂起发起授权申请并等待结果 */
    suspend fun requestPermission(timeoutMillis: Long = DEFAULT_AUTH_TIMEOUT_MS): Boolean

    /** 在 Shizuku 特权 shell 中执行单次命令 */
    suspend fun exec(command: String, timeoutMillis: Long = DEFAULT_EXEC_TIMEOUT_MS): ShizukuExecResult

    companion object {
        const val DEFAULT_BINDER_TIMEOUT_MS = 5_000L
        const val DEFAULT_AUTH_TIMEOUT_MS = 30_000L
        const val DEFAULT_EXEC_TIMEOUT_MS = 10_000L

        fun create(context: Context? = null): ShizukuClient {
            return RealShizukuClient(context?.applicationContext)
        }
    }
}
