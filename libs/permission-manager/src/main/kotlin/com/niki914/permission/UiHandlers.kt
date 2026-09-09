package com.niki914.permission

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.niki914.logging.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * SYSTEM_DIALOG 通道（minSdk 33；<33 的 NOTIFICATION 恒 GRANTED，request 走不到这）。
 * - 只支持 NOTIFICATION：调 MainActivity 预注册的 launcher 弹窗，结果回调确定状态。
 * - 其余 permission：UNAVAILABLE（弹窗能力只覆盖通知）。
 */
class SystemDialogHandler(
    private val ui: UiGate,
) : ChannelHandler {

    override val channel = Channel.SYSTEM_DIALOG
    override val minSdk = MinSdk(33)

    /** 申请状态无法静默得知（弹窗还没弹），固定 UNKNOWN；由调用方先 status() 查 TargetStatus。 */
    override fun status(permission: Permission): PermissionState =
        PermissionState.UNKNOWN

    override suspend fun request(permission: Permission): PermissionState {
        if (permission != Permission.NOTIFICATION) return PermissionState.UNAVAILABLE
        if (Build.VERSION.SDK_INT < 33) return PermissionState.GRANTED
        val launcher = ui.notificationLauncher
        if (launcher == null || ui.activity == null) {
            Logger.d(TAG, "request(NOTIFICATION): launcher=$launcher bound=${ui.activity != null} -> UNAVAILABLE")
            return PermissionState.UNAVAILABLE
        }
        // ponytail: UiGate 通知结果槽是单槽的，并发弹窗用 Mutex 串行
        return mutex.withLock {
            // ponytail: launcher.launch 在非 resumed 状态抛 IllegalStateException，
            // 抛则本次无结果（UNKNOWN 继续降级），调用方下次重试。
            runCatching { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }.onFailure {
                Logger.d(TAG, "request(NOTIFICATION): launch threw ${it.javaClass.simpleName} -> UNKNOWN")
                return@withLock PermissionState.UNKNOWN
            }
            val granted = ui.awaitNotificationResult()
            Logger.d(TAG, "request(NOTIFICATION): granted=$granted")
            if (granted) PermissionState.GRANTED else PermissionState.DENIED_BY_USER
        }
    }

    private companion object {
        const val TAG = "SystemDialogHandler"
        val mutex = Mutex()
    }
}

/**
 * JUMP_SETTINGS 通道：任何 permission 都能跳对应的设置页。
 * launch intent → 挂起等 UiGate.resume 代数推进 → 复查一次 status() 后确定结果。
 * 超时 60s 后也复查一次（用户可能在别处授权了）。
 * 未 bind 时 UNAVAILABLE（决策 2：严格按 PRD，不做 Application 直跳）。
 */
class JumpSettingsHandler(
    private val context: Context,
    private val ui: UiGate,
    private val packageName: String,
    private val statusQuery: (Permission) -> PermissionState,
) : ChannelHandler {

    override val channel = Channel.JUMP_SETTINGS
    override val minSdk = MinSdk(26)

    override fun status(permission: Permission): PermissionState =
        if (ui.activity != null) PermissionState.UNKNOWN else PermissionState.UNAVAILABLE

    override suspend fun request(permission: Permission): PermissionState {
        val activity = ui.activity ?: run {
            Logger.d(TAG, "request($permission): not bound -> UNAVAILABLE")
            return PermissionState.UNAVAILABLE
        }
        if (!ui.isResumed) {
            // 刚从系统授权框回来时 resume 回调可能还没到：等一次（2s），等不到才放弃。
            // 后台真实场景：2s 内无 resume → UNKNOWN 收尾，不跳页骚扰。
            val fgGen = ui.currentGeneration()
            withTimeoutOrNull(FOREGROUND_WAIT_MILLIS) {
                ui.awaitResumeAfter(fgGen)
            }
            if (!ui.isResumed) {
                Logger.d(TAG, "request($permission): app in background, skip jump -> UNKNOWN")
                return PermissionState.UNKNOWN
            }
        }
        val intent = defaultIntent(context, permission, packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val gen = ui.currentGeneration()
        runCatching { activity.startActivity(intent) }.onFailure {
            Logger.d(TAG, "request($permission): startActivity threw -> FAILED")
            return PermissionState.FAILED
        }
        // ponytail: 等用户从设置页回来（resume 代数推进）；60s 超时也复查一次，
        // 复查 status() 定结果，符合 request() 契约。
        try {
            kotlinx.coroutines.withTimeoutOrNull(SETTINGS_RETURN_TIMEOUT_MILLIS) {
                ui.awaitResumeAfter(gen)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        }
        return when (val s = statusQuery(permission)) {
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

    companion object {
        const val TAG = "JumpSettingsHandler"
        const val SETTINGS_RETURN_TIMEOUT_MILLIS = 60_000L
        const val FOREGROUND_WAIT_MILLIS = 2_000L

        fun defaultIntent(context: Context, permission: Permission, packageName: String): Intent =
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
}
