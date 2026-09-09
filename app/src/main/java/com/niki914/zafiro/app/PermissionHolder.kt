package com.niki914.zafiro.app

import android.content.ComponentName
import com.niki914.permission.PermissionManager
import com.niki914.permission.UiGate

/**
 * PermissionManager 单例：UiGate + 门面持有者。
 * MainActivity 负责：预注册 launcher → install → onResume 转发 → onDestroy unbind。
 * 无障碍服务 ComponentName：包名 + .mod.feat.ZafiroAccessibilityService。
 */
object PermissionHolder {
    val ui = UiGate()

    @Volatile
    private var manager: PermissionManager? = null

    fun get(context: android.content.Context): PermissionManager =
        manager ?: synchronized(this) {
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
