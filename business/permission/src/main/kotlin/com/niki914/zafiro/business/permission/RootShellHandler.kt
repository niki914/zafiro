package com.niki914.zafiro.business.permission

import android.content.ComponentName
import android.content.Context
import android.os.Build
import com.niki914.logging.Logger
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ROOT_SHELL 通道（独立实现，与 libterm 共存；战略迁移完成前不合流）。
 * - status：Shell.isAppGrantedRoot()（未建 shell 时返回 null → UNKNOWN，静默契约）
 * - request：Shell.getShell() 阻塞拉起 su 授权，完成后 shell.isRoot 判定
 *
 * 支持 ROOT / OVERLAY / ACCESSIBILITY / NOTIFICATION / EXTERNAL_STORAGE，其余 permission 返回 UNAVAILABLE。
 */
internal class RootShellHandler(
    private val context: Context,
    private val accessibilityService: ComponentName? = null,
) : ChannelHandler {

    private val packageName: String = context.packageName

    override val channel = Channel.ROOT_SHELL
    override val minSdk = MinSdk(26)

    override fun status(permission: Permission): PermissionState {
        if (!permission.isSupported) return PermissionState.UNAVAILABLE
        return when (permission) {
            Permission.ROOT -> when (Shell.isAppGrantedRoot()) {
                true -> PermissionState.GRANTED
                false -> PermissionState.DENIED_BY_USER
                null -> PermissionState.UNKNOWN
            }
            // 应用权限的真值由 TargetStatus 统一给出，别在这里重抄一份分派
            else -> TargetStatus.query(context, permission, accessibilityService)
        }
    }

    override suspend fun request(permission: Permission): PermissionState {
        if (!permission.isSupported) return PermissionState.UNAVAILABLE

        // 拉起 su 授权（若已授权则立即返回），阻塞在用户点击期间
        val shell = withContext(Dispatchers.IO) {
            runCatching { Shell.getShell() }
        }.getOrElse { e ->
            if (e is CancellationException) throw e
            Logger.d(TAG, "request($permission): getShell threw ${e.javaClass.simpleName} -> UNAVAILABLE")
            return PermissionState.UNAVAILABLE
        }
        if (!shell.isRoot) {
            Logger.d(TAG, "request($permission): shell not root -> DENIED_BY_USER")
            return PermissionState.DENIED_BY_USER
        }

        // ponytail: 授权命令文本与 merge 逻辑收敛到 ShellGrants，两个 shell 通道不再各抄一份
        return when (permission) {
            Permission.ROOT -> PermissionState.GRANTED
            Permission.OVERLAY ->
                ShellGrants.grantOverlay({ cmd -> run(cmd) }, packageName)
            Permission.ACCESSIBILITY ->
                ShellGrants.grantAccessibility({ cmd -> run(cmd) }, accessibilityService)
            Permission.NOTIFICATION ->
                if (Build.VERSION.SDK_INT < NOTIFICATION_API) {
                    // <33 无 POST_NOTIFICATIONS 权限，通知恒可用（同 TargetStatus.notification）
                    PermissionState.GRANTED
                } else {
                    ShellGrants.grantNotification(
                        run = { cmd -> run(cmd) },
                        packageName = packageName,
                        verify = { TargetStatus.notification(context) },
                    )
                }
            Permission.EXTERNAL_STORAGE ->
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    // <30 无全局文件访问（D22 里 UI 也不放出入口），不把「不存在」报成 FAILED
                    PermissionState.UNAVAILABLE
                } else {
                    ShellGrants.grantExternalStorage(
                        run = { cmd -> run(cmd) },
                        packageName = packageName,
                        verify = { TargetStatus.externalStorage() },
                    )
                }
            else -> PermissionState.UNAVAILABLE
        }
    }

    private suspend fun run(command: String): ShellOutcome = withContext(Dispatchers.IO) {
        try {
            val result = Shell.cmd(command).exec()
            if (result.err.isNotEmpty()) {
                Logger.d(TAG, "exec stderr [$command]: ${result.err.joinToString(" | ")}")
            }
            ShellOutcome(
                exitCode = result.code,
                stdout = result.out,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Logger.d(TAG, "root exec failed [$command]: ${e.javaClass.simpleName}: ${e.message}")
            ShellOutcome(exitCode = null, stdout = emptyList())
        }
    }

    private val Permission.isSupported: Boolean
        get() = this == Permission.ROOT || isAppLevelGrantable(accessibilityService != null)

    private companion object {
        const val TAG = "niki914_zafiro_RootShellHandler"
    }
}
