package com.niki914.zafiro.business.permission

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.niki914.logging.Logger
import com.niki914.zafiro.business.application.ApplicationService
import com.niki914.zafiro.business.application.NotificationDialogResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * SYSTEM_DIALOG 通道（minSdk 33；<33 的 NOTIFICATION 恒 GRANTED，request 走不到这）。
 * - 只支持 NOTIFICATION：经 ApplicationService.requestPostNotifications 弹窗，结果回调确定状态。
 * - 其余 permission：UNAVAILABLE（弹窗能力只覆盖通知）。
 */
internal class SystemDialogHandler(
    private val appService: ApplicationService,
) : ChannelHandler {

    override val channel = Channel.SYSTEM_DIALOG
    override val minSdk = MinSdk(33)

    /** 申请状态无法静默得知（弹窗还没弹），固定 UNKNOWN；由调用方先 status() 查 TargetStatus。 */
    override fun status(permission: Permission): PermissionState =
        PermissionState.UNKNOWN

    override suspend fun request(permission: Permission): PermissionState {
        if (permission != Permission.NOTIFICATION) return PermissionState.UNAVAILABLE
        if (Build.VERSION.SDK_INT < NOTIFICATION_API) return PermissionState.GRANTED
        if (appService.getActivity() == null) {
            Logger.d(TAG, "request(NOTIFICATION): no foreground -> UNAVAILABLE")
            return PermissionState.UNAVAILABLE
        }
        // 通知结果槽是单槽的，并发弹窗用 Mutex 串行
        return mutex.withLock {
            try {
                val result = appService.requestPostNotifications()
                Logger.d(TAG, "request(NOTIFICATION): $result")
                when (result) {
                    NotificationDialogResult.GRANTED -> PermissionState.GRANTED
                    NotificationDialogResult.DENIED -> PermissionState.DENIED_BY_USER
                    // 框没弹成 = 本次无结果，继续降级
                    NotificationDialogResult.NOT_SHOWN -> PermissionState.UNKNOWN
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Logger.d(TAG, "request(NOTIFICATION): threw ${e.javaClass.simpleName} -> UNKNOWN")
                PermissionState.UNKNOWN
            }
        }
    }

    private companion object {
        const val TAG = "niki914_zafiro_SystemDialogHandler"
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
        when (permission) {
            Permission.OVERLAY -> Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            )
            Permission.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            Permission.NOTIFICATION ->
                if (Build.VERSION.SDK_INT >= 26) {
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                } else {
                    // minSdk=26，此分支到不了；留着防未来降 minSdk
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:$packageName"))
                }
            Permission.ROOT, Permission.SHIZUKU -> Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName"),
            )
        }
}
