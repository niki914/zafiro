package com.niki914.zafiro.business.permission

import android.content.ComponentName
import android.content.Context
import android.os.Build
import com.niki914.logging.Logger
import com.niki914.shizuku.ShizukuClient

/**
 * SHIZUKU 权限通道：经 [ShizukuClient] 代理底层 Shizuku 授权与特权命令执行。
 *
 * 支持的权限集见 `isSupported`；每种权限怎么授、哪个 API 上是什么机制，全在
 * [PermissionSpec] 与 [ShellGrants]，本类只负责 Shizuku 授权与执行器。
 */
internal class ShizukuHandler(
    private val context: Context,
    private val accessibilityService: ComponentName? = null,
    private val client: ShizukuClient = ShizukuClient.create(context),
) : ChannelHandler {

    private val packageName: String = context.packageName

    override val channel = Channel.SHIZUKU
    override val minSdk = MinSdk(23) // Shizuku 自身要求 23+

    override fun status(permission: Permission): PermissionState {
        if (!permission.isSupported) return PermissionState.UNAVAILABLE
        return when (permission) {
            Permission.ROOT, Permission.SHIZUKU -> {
                if (!client.isBinderAlive) {
                    Logger.d(TAG, "status($permission): binder not alive -> UNAVAILABLE")
                    return PermissionState.UNAVAILABLE
                }
                when {
                    client.isAuthorized -> PermissionState.GRANTED
                    else -> PermissionState.DENIED_BY_USER
                }
            }
            // 应用权限的真值由 TargetStatus 统一给出，别在这里重抄一份分派
            else -> TargetStatus.query(context, permission, accessibilityService)
        }
    }

    override suspend fun request(permission: Permission): PermissionState {
        if (!permission.isSupported) return PermissionState.UNAVAILABLE
        if (!client.isBinderAlive) {
            // binder 不在时先问「装没装」：没装就不会有 binder 送来，等下去是白等。
            // 顺序不能反——Sui（Magisk 模块）能让没装 Shizuku app 的机器也拿到 binder，
            // 所以先放过已经在的 binder，再拿安装状态做快速失败。
            if (!client.isInstalled) {
                Logger.d(TAG, "request($permission): shizuku not installed -> UNAVAILABLE")
                return PermissionState.UNAVAILABLE
            }
            Logger.d(
                TAG,
                "request($permission): binder absent, waiting up to ${BINDER_TIMEOUT_MILLIS}ms",
            )
            if (!client.awaitBinder(BINDER_TIMEOUT_MILLIS)) {
                Logger.d(TAG, "request($permission): binder still absent -> UNAVAILABLE")
                return PermissionState.UNAVAILABLE
            }
        }

        if (!client.isAuthorized) {
            Logger.d(TAG, "request($permission): showing Shizuku dialog")
            val authorized = client.requestPermission(AUTH_TIMEOUT_MILLIS)
            Logger.d(TAG, "request($permission): authorized=$authorized")
            if (!authorized) return PermissionState.DENIED_BY_USER
        }

        // 能力型目标：Shizuku 自身已授权，即达成（机制表不管能力型权限）
        if (permission == Permission.ROOT || permission == Permission.SHIZUKU) {
            return PermissionState.GRANTED
        }
        val mechanism = PermissionSpec.appLevelMechanism(permission, Build.VERSION.SDK_INT)
            ?: return PermissionState.UNAVAILABLE
        return ShellGrants.grant(
            mechanism = mechanism,
            run = ::run,
            packageName = packageName,
            accessibilityService = accessibilityService,
            verify = { TargetStatus.query(context, permission, accessibilityService) },
        )
    }

    private suspend fun run(command: String): ShellOutcome? {
        val result = client.exec(command, EXEC_TIMEOUT_MILLIS)
        if (result.stderr.isNotEmpty()) {
            Logger.d(TAG, "exec stderr [$command]: ${result.stderr.joinToString(" | ")}")
        }
        Logger.d(TAG, "exec [$command] exit=${result.exitCode} stdoutLines=${result.stdout.size}")
        return if (result.exitCode == -1 && result.stdout.isEmpty() && result.stderr.isNotEmpty()) {
            null
        } else {
            ShellOutcome(exitCode = result.exitCode, stdout = result.stdout)
        }
    }

    private val Permission.isSupported: Boolean
        get() = this == Permission.ROOT ||
                this == Permission.SHIZUKU ||
                isAppLevelGrantable(accessibilityService != null)

    companion object {
        const val TAG = "niki914_zafiro_ShizukuHandler"
        const val AUTH_TIMEOUT_MILLIS = 30_000L
        const val BINDER_TIMEOUT_MILLIS = 8_000L
        const val EXEC_TIMEOUT_MILLIS = 10_000L
    }
}
