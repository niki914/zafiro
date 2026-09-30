package com.niki914.zafiro.business.permission

import android.content.ComponentName
import com.niki914.logging.Logger

/**
 * shell 通道共用的授权命令（RootShell / Shizuku 共用，命令文本与分叉逻辑只此一份）。
 * 按 [GrantMechanism] 选命令；命令退出码不等于系统里的权限状态，因此每条命令后都用
 * [grant] 的 `verify` 复查真实状态收尾。
 *
 * [run] 返回 null = 进程创建/执行失败（Shizuku 侧）；RootShell 侧永不 null，传 `{ cmd -> run(cmd) }` 即可。
 */
internal object ShellGrants {

    /**
     * 代授权一条权限。收尾状态：
     * - GRANTED：复查确认真值已是 GRANTED
     * - DENIED_BY_USER：命令被系统接受，但复查仍未授权（链靠它继续降级）
     * - FAILED：一条命令都没跑起来（进程创建失败）
     */
    suspend fun grant(
        mechanism: GrantMechanism,
        run: suspend (String) -> ShellOutcome?,
        packageName: String,
        accessibilityService: ComponentName?,
        verify: () -> PermissionState,
    ): PermissionState = when (mechanism) {
        GrantMechanism.None -> PermissionState.GRANTED
        is GrantMechanism.Runtime -> grantRuntime(mechanism, run, packageName, verify)
        is GrantMechanism.AppOp -> grantAppOp(mechanism.op, run, packageName, verify)
        GrantMechanism.Accessibility -> grantAccessibility(run, accessibilityService)
    }

    /**
     * 运行时权限：`pm grant` 走运行时权限表，`appops set` 走开关，不同 ROM 对二者的接受程度不一致，
     * 两条路都试。返回 FAILED = 一条命令都没跑起来；DENIED_BY_USER = 命令被接受但系统仍未授权。
     */
    private suspend fun grantRuntime(
        mechanism: GrantMechanism.Runtime,
        run: suspend (String) -> ShellOutcome?,
        packageName: String,
        verify: () -> PermissionState,
    ): PermissionState {
        val commands = mechanism.names.map { "pm grant $packageName $it" } +
            mechanism.appOps.map { "appops set $packageName $it allow" }
        var anyAccepted = false
        for (command in commands) {
            val outcome = run(command) ?: continue
            anyAccepted = anyAccepted || outcome.isSuccess
            Logger.d(TAG, "exec [$command] exit=${outcome.exitCode} stdoutLines=${outcome.stdout.size}")
            if (verify() == PermissionState.GRANTED) return PermissionState.GRANTED
        }
        return if (anyAccepted) PermissionState.DENIED_BY_USER else PermissionState.FAILED
    }

    /** 特殊权限：只有 `appops set <op> allow`，`pm grant` 不适用（它不是运行时权限）。 */
    private suspend fun grantAppOp(
        op: String,
        run: suspend (String) -> ShellOutcome?,
        packageName: String,
        verify: () -> PermissionState,
    ): PermissionState {
        val command = "appops set $packageName $op allow"
        val outcome = run(command)
        Logger.d(TAG, "exec [$command] exit=${outcome?.exitCode} stdoutLines=${outcome?.stdout?.size}")
        if (verify() == PermissionState.GRANTED) return PermissionState.GRANTED
        return if (outcome?.isSuccess == true) PermissionState.DENIED_BY_USER else PermissionState.FAILED
    }

    private suspend fun grantAccessibility(
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

    private const val TAG = "niki914_zafiro_ShellGrants"
}
