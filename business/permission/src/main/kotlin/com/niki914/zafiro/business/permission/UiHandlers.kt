package com.niki914.zafiro.business.permission

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.niki914.logging.Logger
import com.niki914.zafiro.business.application.ApplicationService
import com.niki914.zafiro.business.application.RuntimeDialogResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * SYSTEM_DIALOG 通道（minSdk 26：运行时权限弹框从 23 起就有，通知只是其中一例）。
 * 机制是 [GrantMechanism.Runtime] 的权限经 ApplicationService 弹框，一次一个、串行；
 * 其余（特殊权限 / 无障碍）没有系统弹框，报 UNAVAILABLE 让链降级。
 */
internal class SystemDialogHandler(
    private val appService: ApplicationService,
) : ChannelHandler {

    private val app: Context = appService.getApplication()

    override val channel = Channel.SYSTEM_DIALOG
    override val minSdk = MinSdk(26)

    /** 申请状态无法静默得知（弹窗还没弹）；有弹框可弹的报 UNKNOWN，其余的明确 UNAVAILABLE。 */
    override fun status(permission: Permission): PermissionState =
        if (PermissionSpec.appLevelMechanism(permission, Build.VERSION.SDK_INT) is GrantMechanism.Runtime) {
            PermissionState.UNKNOWN
        } else {
            PermissionState.UNAVAILABLE
        }

    override suspend fun request(permission: Permission): PermissionState {
        val mechanism = PermissionSpec.appLevelMechanism(permission, Build.VERSION.SDK_INT)
            ?: return PermissionState.UNAVAILABLE
        return when (mechanism) {
            GrantMechanism.None -> PermissionState.GRANTED
            is GrantMechanism.Runtime -> requestRuntimePermissions(permission, mechanism)
            is GrantMechanism.AppOp, GrantMechanism.Accessibility -> PermissionState.UNAVAILABLE
        }
    }

    private suspend fun requestRuntimePermissions(
        permission: Permission,
        mechanism: GrantMechanism.Runtime,
    ): PermissionState {
        // 弹框要前台。刚被 su / Shizuku 自己的授权框顶下去过时，我们的 resume 回调要晚几十毫秒
        // 才到；那一刻判 null 会把这一环整环丢掉（链只给每环一次机会），所以先等一次前台。
        if (appService.awaitForeground(FOREGROUND_WAIT_MILLIS) == null) {
            Logger.d(TAG, "request($permission): no foreground -> UNAVAILABLE")
            return PermissionState.UNAVAILABLE
        }
        // 结果槽是单槽的，并发弹窗用 Mutex 串行；同一次申请里的多个权限也逐个弹，不追着用户重复要
        return mutex.withLock {
            try {
                var dialogShown = false
                for (name in mechanism.names) {
                    val result = requestRuntimePermissionWithRetry(name)
                    dialogShown = dialogShown || result != RuntimeDialogResult.NOT_SHOWN
                    if (result != RuntimeDialogResult.GRANTED) break
                }
                // 框的返回值只用来分辨「没弹成」；真值以系统里的权限状态收尾（与跳设置页复查同口径）
                val state = if (dialogShown) {
                    TargetStatus.query(app, permission, accessibilityService = null)
                } else {
                    PermissionState.UNKNOWN
                }
                Logger.d(TAG, "request($permission): dialogShown=$dialogShown -> $state")
                state
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Logger.d(TAG, "request($permission): threw ${e.javaClass.simpleName} -> UNKNOWN")
                PermissionState.UNKNOWN
            }
        }
    }

    /**
     * 弹一次系统框；[RuntimeDialogResult.NOT_SHOWN]（未 RESUMED 导致 launch 抛 / 框没弹成）
     * 再等一次前台并重试一次——竞态窗口只有几十毫秒，重试一次基本都能落到框上；
     * 再失败就交回上层降级（本环只给这一次重试）。
     */
    private suspend fun requestRuntimePermissionWithRetry(name: String): RuntimeDialogResult {
        val first = appService.requestRuntimePermission(name)
        if (first != RuntimeDialogResult.NOT_SHOWN) return first
        appService.awaitForeground(FOREGROUND_WAIT_MILLIS)
        return appService.requestRuntimePermission(name)
    }

    private companion object {
        const val TAG = "niki914_zafiro_SystemDialogHandler"

        /** 等前台的短超时：刚从 su / Shizuku 授权框回来时 resume 回调可能迟到。 */
        const val FOREGROUND_WAIT_MILLIS = 2_000L

        val mutex = Mutex()
    }
}

/**
 * JUMP_SETTINGS 通道：任何 permission 都能跳对应的设置页。
 * 前台 activity 经 ApplicationService 取 → startActivity 跳页 →
 * awaitNextResume 等用户回来 → 复查一次 status() 后确定结果。
 * 超时 60s 也复查一次（用户可能在别处授权了）。
 * 无前台时：短等 2s（刚从系统框回来 resume 回调可能迟到），等不到按 UNKNOWN 收尾，不跳页骚扰。
 */
internal class JumpSettingsHandler(
    private val context: Context,
    private val appService: ApplicationService,
    private val accessibilityService: ComponentName?,
) : ChannelHandler {

    private val packageName: String = context.packageName

    override val channel = Channel.JUMP_SETTINGS
    override val minSdk = MinSdk(26)

    override fun status(permission: Permission): PermissionState =
        if (appService.getActivity() != null) PermissionState.UNKNOWN else PermissionState.UNAVAILABLE

    override suspend fun request(permission: Permission): PermissionState {
        var activity = appService.getActivity()
        if (activity == null) {
            // 刚从系统授权框回来时 resume 回调可能还没到：等一次，等不到才放弃。
            activity = appService.awaitForeground(FOREGROUND_WAIT_MILLIS)
            if (activity == null) {
                Logger.d(TAG, "request($permission): app in background, skip jump -> UNKNOWN")
                return PermissionState.UNKNOWN
            }
        }
        val intent = defaultIntent(permission)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { activity.startActivity(intent) }.onFailure {
            Logger.d(TAG, "request($permission): startActivity threw -> FAILED")
            return PermissionState.FAILED
        }
        // 等用户从设置页回来；60s 超时也复查一次，复查 status() 定结果。
        appService.awaitNextResume(SETTINGS_RETURN_TIMEOUT_MILLIS)
        return when (val s = TargetStatus.query(context, permission, accessibilityService)) {
            PermissionState.GRANTED -> {
                Logger.d(TAG, "request($permission): recheck=GRANTED")
                PermissionState.GRANTED
            }
            PermissionState.UNKNOWN -> {
                Logger.d(TAG, "request($permission): recheck=UNKNOWN")
                PermissionState.UNKNOWN
            }
            else -> {
                Logger.d(TAG, "request($permission): recheck=$s -> DENIED_BY_USER")
                PermissionState.DENIED_BY_USER
            }
        }
    }

    private companion object {
        const val TAG = "niki914_zafiro_JumpSettingsHandler"
        const val SETTINGS_RETURN_TIMEOUT_MILLIS = 60_000L
        const val FOREGROUND_WAIT_MILLIS = 2_000L
    }

    private fun defaultIntent(permission: Permission): Intent =
        when (PermissionSpec.appLevelMechanism(permission, Build.VERSION.SDK_INT)) {
            // 运行时权限里只有通知有专属页；存储（<30）只能进应用详情
            is GrantMechanism.Runtime -> when (permission) {
                Permission.NOTIFICATION -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                else -> appDetails()
            }
            null -> appDetails()
            // 特殊权限各有专属页；AppOp 机制目前只有存储与悬浮窗
            is GrantMechanism.AppOp -> when (permission) {
                Permission.STORAGE -> Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName"),
                )
                Permission.OVERLAY -> Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                )
                else -> appDetails()
            }
            GrantMechanism.Accessibility -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            // <33 的通知恒可用，跳页无意义，给一个落脚点
            GrantMechanism.None -> appDetails()
        }

    private fun appDetails(): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:$packageName"),
    )
}
