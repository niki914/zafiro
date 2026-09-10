package com.niki914.permission

import android.content.ComponentName
import android.content.Context
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
 * 支持 ROOT / OVERLAY / ACCESSIBILITY，其余 permission 返回 UNAVAILABLE。
 */
class RootShellHandler(
    private val context: Context,
    private val packageName: String,
    private val accessibilityService: ComponentName? = null,
) : ChannelHandler {

    override val channel = Channel.ROOT_SHELL
    override val minSdk = MinSdk(26)

    override fun status(permission: Permission): PermissionState {
        if (!permission.isSupported) return PermissionState.UNAVAILABLE
        return when (permission) {
            Permission.OVERLAY -> TargetStatus.overlay(context)
            Permission.ACCESSIBILITY ->
                TargetStatus.accessibility(context, accessibilityService)
            Permission.ROOT -> when (Shell.isAppGrantedRoot()) {
                true -> PermissionState.GRANTED
                false -> PermissionState.DENIED_BY_USER
                null -> PermissionState.UNKNOWN
            }
            else -> PermissionState.UNAVAILABLE
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

        return when (permission) {
            Permission.ROOT -> PermissionState.GRANTED
            Permission.OVERLAY -> exec("appops set $packageName SYSTEM_ALERT_WINDOW allow")
            Permission.ACCESSIBILITY -> grantAccessibility()
            else -> PermissionState.UNAVAILABLE
        }
    }

    private suspend fun grantAccessibility(): PermissionState {
        val service = requireNotNull(accessibilityService) {
            "accessibilityService is required for Permission.ACCESSIBILITY"
        }
        val get = run("settings get secure enabled_accessibility_services")
        if (!get.isSuccess) return PermissionState.FAILED
        val merged = get.stdout.joinToString("").trim()
            .takeUnless { it.isBlank() || it == "null" }
            ?.split(":")
            .orEmpty()
            .filter { it.isNotBlank() }
            .plus(service.flattenToShortString())
            .distinct()
            .joinToString(":")
        if (!run("settings put secure enabled_accessibility_services $merged").isSuccess) {
            return PermissionState.FAILED
        }
        return if (run("settings put secure accessibility_enabled 1").isSuccess) {
            PermissionState.GRANTED
        } else {
            PermissionState.FAILED
        }
    }

    private suspend fun exec(command: String): PermissionState {
        val outcome = run(command)
        Logger.d(TAG, "exec [$command] exit=${outcome.exitCode} stdoutLines=${outcome.stdout.size}")
        return if (outcome.isSuccess) PermissionState.GRANTED else PermissionState.FAILED
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
        get() = this == Permission.ROOT ||
            this == Permission.OVERLAY ||
            (this == Permission.ACCESSIBILITY && accessibilityService != null)

    private companion object {
        const val TAG = "RootShellHandler"
    }
}
