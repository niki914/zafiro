package com.niki914.permission

import android.content.ComponentName
import com.niki914.logging.Logger

/**
 * shell 通道共用的授权命令（RootShell / Shizuku 共用，命令文本与 merge 逻辑只此一份）。
 * [run] 返回 null = 进程创建/执行失败（Shizuku 侧）；RootShell 侧永不 null，传 `{ cmd -> run(cmd) }` 即可。
 */
internal object ShellGrants {

    suspend fun grantOverlay(
        run: suspend (String) -> ShellOutcome?,
        packageName: String,
    ): PermissionState {
        val command = "appops set $packageName SYSTEM_ALERT_WINDOW allow"
        val outcome = run(command)
        Logger.d(TAG, "exec [$command] exit=${outcome?.exitCode} stdoutLines=${outcome?.stdout?.size}")
        return if (outcome?.isSuccess == true) PermissionState.GRANTED else PermissionState.FAILED
    }

    suspend fun grantAccessibility(
        run: suspend (String) -> ShellOutcome?,
        service: ComponentName?,
    ): PermissionState {
        val svc = requireNotNull(service) {
            "accessibilityService is required for Permission.ACCESSIBILITY"
        }
        val get = run("settings get secure enabled_accessibility_services")
            ?: return PermissionState.FAILED
        val merged = mergeServices(get.stdout, svc)
        val put1 = run("settings put secure enabled_accessibility_services $merged")
            ?: return PermissionState.FAILED
        if (!put1.isSuccess) return PermissionState.FAILED
        val put2 = run("settings put secure accessibility_enabled 1")
            ?: return PermissionState.FAILED
        return if (put2.isSuccess) PermissionState.GRANTED else PermissionState.FAILED
    }

    private fun mergeServices(stdout: List<String>, service: ComponentName): String =
        stdout.joinToString("").trim()
            .takeUnless { it.isBlank() || it == "null" }
            ?.split(":")
            .orEmpty()
            .filter { it.isNotBlank() }
            .plus(service.flattenToShortString())
            .distinct()
            .joinToString(":")

    private const val TAG = "ShellGrants"
}
