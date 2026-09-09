package com.niki914.permission

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import kotlinx.coroutines.runBlocking

/**
 * PRD 门面：bind/unbind + scope() API。
 *
 * 组装（供 App 进程）：
 * ```
 * val ui = UiGate()
 * val pm = PermissionManager.create(context, ui, accessibilityService)
 * ```
 * MainActivity：onCreate 预注册 launcher → `ui.notificationLauncher = launcher`、
 * onResume 转发 `ui.onActivityResumed()`、onDestroy 调 `pm.unbind()`。
 *
 * 仅主进程：全部业务经 Binder 收敛于主进程，宿主进程禁止直连。
 */
class PermissionManager private constructor(
    private val engine: PermissionEngine,
    private val ui: UiGate?,
) {
    /** PRD 契约：onDestroy 必须 unbind，防泄漏 */
    fun bind(activity: Activity) {
        ui?.bind(activity)
    }

    fun unbind() {
        ui?.unbind()
    }

    fun status(permission: Permission): PermissionState =
        engine.status(permission)

    fun scope(vararg channels: Channel): ScopeBuilder =
        ScopeBuilder(engine, channels.toList())

    companion object {
        /** PRD 默认链 */
        fun defaultChain(permission: Permission): List<Channel> = when (permission) {
            Permission.OVERLAY, Permission.ACCESSIBILITY ->
                listOf(Channel.ROOT_SHELL, Channel.SHIZUKU, Channel.JUMP_SETTINGS)
            Permission.NOTIFICATION ->
                listOf(Channel.SYSTEM_DIALOG, Channel.JUMP_SETTINGS)
            Permission.ROOT -> listOf(Channel.ROOT_SHELL)
            Permission.SHIZUKU -> listOf(Channel.SHIZUKU)
        }

        fun create(
            context: Context,
            ui: UiGate,
            accessibilityService: ComponentName? = null,
        ): PermissionManager {
            val app = context.applicationContext
            val root = RootShellHandler(app, app.packageName, accessibilityService)
            val shizuku = ShizukuHandler(app, app.packageName, accessibilityService)
            val dialog = SystemDialogHandler(ui)
            // ponytail: UI 通道的复查走同一份 TargetStatus，避免 handler 与外部查询口径分叉
            val statusQuery: (Permission) -> PermissionState = { p ->
                queryTarget(app, p, accessibilityService)
            }
            val jump = JumpSettingsHandler(app, ui, app.packageName, statusQuery)
            val engine = PermissionEngine(
                currentApi = Build.VERSION.SDK_INT,
                handlers = mapOf(
                    Channel.ROOT_SHELL to root,
                    Channel.SHIZUKU to shizuku,
                    Channel.SYSTEM_DIALOG to dialog,
                    Channel.JUMP_SETTINGS to jump,
                ),
            )
            return PermissionManager(engine, ui)
        }

        private fun queryTarget(
            context: Context,
            permission: Permission,
            accessibilityService: ComponentName?,
        ): PermissionState = when (permission) {
            Permission.OVERLAY -> TargetStatus.overlay(context)
            Permission.NOTIFICATION -> TargetStatus.notification(context)
            Permission.ACCESSIBILITY -> TargetStatus.accessibility(context, accessibilityService)
            // 能力型目标静默嗅探会拉授权，不查，交给 shell 通道内部判定
            Permission.ROOT, Permission.SHIZUKU -> PermissionState.UNKNOWN
        }

        /** MainActivity 预注册的 launcher 注入点（决策 1：launcher 注入，不内部注册） */
        fun installNotificationLauncher(ui: UiGate, launcher: ActivityResultLauncher<String>) {
            ui.notificationLauncher = launcher
        }
    }
}

class ScopeBuilder internal constructor(
    private val engine: PermissionEngine,
    private val channels: List<Channel>,
) {
    /**
     * 挂起式：回调跑在调用方协程上下文。
     * 空 scope = 用该 permission 的默认链。
     */
    suspend fun withPermission(
        permission: Permission,
        onResult: (PermissionResult) -> Unit,
    ) {
        val chain = channels.ifEmpty { PermissionManager.defaultChain(permission) }
        onResult(engine.request(permission, chain))
    }

    /** 阻塞式：给 handleBackgroundConfirmation 这类同步回调桥用 */
    fun withPermissionBlocking(permission: Permission): PermissionResult =
        runBlocking {
            val chain = channels.ifEmpty { PermissionManager.defaultChain(permission) }
            engine.request(permission, chain)
        }
}
