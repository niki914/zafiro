package com.niki914.zafiro.business.permission

import android.Manifest
import android.os.Build

/**
 * 应用级权限在某个 API 上的授予机制。纯事实：说清「叫什么、怎么开」，不含任何执行。
 */
internal sealed interface GrantMechanism {

    /** 该 API 上这个概念还不存在，恒可用（如 <33 的通知）。 */
    data object None : GrantMechanism

    /**
     * 运行时权限：`pm grant <name>` 与系统运行时权限框认同一组名字。
     * [appOps] 是部分 ROM 的兜底（只认 appops 不认 pm grant），op 名与权限名未必相同。
     */
    data class Runtime(
        val names: List<String>,
        val appOps: List<String> = names,
    ) : GrantMechanism

    /** 特殊权限：只有 `appops set <op> allow` 能代授权。 */
    data class AppOp(val op: String) : GrantMechanism

    /** 无障碍：settings secure 写值，唯一不走前两者的机制。 */
    data object Accessibility : GrantMechanism
}

/**
 * 「权限 × API → 机制」的唯一版本分叉点。全仓的 SDK_INT 比较只许住在这里。
 *
 * 能力型权限（ROOT / SHIZUKU）与版本无关，返回 null，由调用方收尾成 UNAVAILABLE。
 */
internal object PermissionSpec {

    /** POST_NOTIFICATIONS 引入的 API 级别；低于此值时通知恒可用，不能也不需要申请。 */
    const val NOTIFICATION_API = 33

    /** op 名与权限名不同：`POST_NOTIFICATIONS` 权限对应 `POST_NOTIFICATION` op。 */
    const val OP_POST_NOTIFICATION = "POST_NOTIFICATION"
    const val OP_MANAGE_EXTERNAL_STORAGE = "MANAGE_EXTERNAL_STORAGE"
    const val OP_SYSTEM_ALERT_WINDOW = "SYSTEM_ALERT_WINDOW"

    fun appLevelMechanism(permission: Permission, api: Int): GrantMechanism? = when (permission) {
        Permission.NOTIFICATION ->
            if (api < NOTIFICATION_API) {
                GrantMechanism.None
            } else {
                GrantMechanism.Runtime(
                    names = listOf(Manifest.permission.POST_NOTIFICATIONS),
                    appOps = listOf(OP_POST_NOTIFICATION),
                )
            }

        // <30 没有 all-files，语义回到运行时权限（读写两条；26-28 上 WRITE 兼给 READ）
        Permission.STORAGE ->
            if (api >= Build.VERSION_CODES.R) {
                GrantMechanism.AppOp(OP_MANAGE_EXTERNAL_STORAGE)
            } else {
                GrantMechanism.Runtime(
                    names = listOf(
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    ),
                )
            }

        Permission.OVERLAY -> GrantMechanism.AppOp(OP_SYSTEM_ALERT_WINDOW)
        Permission.ACCESSIBILITY -> GrantMechanism.Accessibility
        Permission.ROOT, Permission.SHIZUKU -> null
    }
}
