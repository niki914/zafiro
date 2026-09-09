package com.niki914.zafiro.app

import android.content.ComponentName
import com.niki914.logging.Logger
import com.niki914.permission.PermissionManager
import com.niki914.permission.UiGate

/**
 * PermissionManager 单例：UiGate + 门面持有者。
 * MainActivity 负责：预注册 launcher → install → onResume 转发 → onDestroy unbind。
 * 无障碍服务 ComponentName：包名 + .mod.feat.ZafiroAccessibilityService。
 *
 * 仅主进程可用：全部业务经 Binder 收敛于主进程，宿主进程禁止直连。
 */
object PermissionHolder {
    val ui = UiGate()

    @Volatile
    private var manager: PermissionManager? = null

    fun get(context: android.content.Context): PermissionManager {
        val app = context.applicationContext
        // 宿主进程的 packageName 是宿主包（Breeno/XiaoAi），与主包不一致时直接拒绝，
        // 避免错包单例污染 ComponentName 与 shell 命令对象。
        if (app.packageName != BuildConfig.APPLICATION_ID) {
            Logger.w(TAG, "reject non-host package=${app.packageName}")
            throw IllegalStateException(
                "PermissionManager is main-process only (caller=${app.packageName})"
            )
        }
        return manager ?: synchronized(this) {
            manager ?: PermissionManager.create(
                context,
                ui,
                ComponentName(
                    context.packageName,
                    "${context.packageName}.mod.feat.ZafiroAccessibilityService",
                ),
            ).also { manager = it }
        }
    }

    private const val TAG = "PermissionHolder"
}
