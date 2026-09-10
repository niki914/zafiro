package com.niki914.permission

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.os.Build
import androidx.activity.result.ActivityResultLauncher

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

    /**
     * 只读查询出口：AgentRuntimeService 发通知前只看 NOTIFICATION 状态（静默，不跑链）。
     * 由 TargetStatus 直出 + 引擎聚合，不拉授权、不弹窗、不跳页。
     */
    fun targetStatus(permission: Permission): PermissionState =
        status(permission)

    /**
     * 默认链快捷入口：scope() 空参的等价写法。
     * 业务能确定用哪条链时直接调这个；要自定义通道才用 scope(vararg channels)。
     */
    suspend fun request(permission: Permission): PermissionResult =
        scope().request(permission)

    fun scope(vararg channels: Channel): ScopeBuilder =
        ScopeBuilder(engine, channels.toList())

    companion object {
        /** PRD 默认链 */
        fun defaultChain(permission: Permission): List<Channel> = when (permission) {
            Permission.OVERLAY, Permission.ACCESSIBILITY ->
                listOf(Channel.ROOT_SHELL, Channel.SHIZUKU, Channel.JUMP_SETTINGS)
            Permission.NOTIFICATION ->
                listOf(Channel.ROOT_SHELL, Channel.SHIZUKU, Channel.SYSTEM_DIALOG, Channel.JUMP_SETTINGS)
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
            // ponytail: UI 通道的复查走同一份 TargetStatus，避免 handler 与外部查询口径分叉
            val statusQuery: (Permission) -> PermissionState = { p ->
                queryTarget(app, p, accessibilityService)
            }
            val dialog = SystemDialogHandler(ui)
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

    }
}

class ScopeBuilder internal constructor(
    private val engine: PermissionEngine,
    private val channels: List<Channel>,
) {
    /**
     * 空 scope = 用该 permission 的默认链；传了 channels 就按传入顺序跑。
     * 挂起式：不占线程等回调；阻塞式会卡 Binder 线程，不提供。
     */
    suspend fun request(permission: Permission): PermissionResult {
        val chain = channels.ifEmpty { PermissionManager.defaultChain(permission) }
        return engine.request(permission, chain)
    }
}
