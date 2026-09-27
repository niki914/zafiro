package com.niki914.zafiro.business.permission

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * POST_NOTIFICATIONS 引入的 API 级别；低于此值时通知恒可用，不能也不需要申请。
 */
internal const val NOTIFICATION_API = 33

/**
 * 目标权限的真实状态静默查询（PRD：status() 报真实权限，Context 注入）。
 * 各通道 handler 共用；查询只读系统状态，不拉任何授权。
 *
 * 模块私有：业务方一律经 PermissionManager.status()，禁止直连此处与原生权限 API。
 */
internal object TargetStatus {

    /**
     * 「要什么」→ 真实状态的唯一分派点。RootShell/Shizuku handler 的 status 与
     * JUMP_SETTINGS 的复查都走这里，保证三处口径不分叉。
     *
     * 能力型目标（ROOT/SHIZUKU）静默嗅探会拉起授权，不查，返回 UNKNOWN，
     * 由各 shell 通道自己判定。
     */
    fun query(
        context: Context,
        permission: Permission,
        accessibilityService: ComponentName?,
    ): PermissionState = when (permission) {
        Permission.OVERLAY -> overlay(context)
        Permission.NOTIFICATION -> notification(context)
        Permission.ACCESSIBILITY -> accessibility(context, accessibilityService)
        Permission.ROOT, Permission.SHIZUKU -> PermissionState.UNKNOWN
    }

    fun overlay(context: Context): PermissionState =
        if (Settings.canDrawOverlays(context)) {
            PermissionState.GRANTED
        } else {
            PermissionState.DENIED_BY_USER
        }

    fun notification(context: Context): PermissionState {
        if (Build.VERSION.SDK_INT < NOTIFICATION_API) return PermissionState.GRANTED
        val granted = context.checkSelfPermission("android.permission.POST_NOTIFICATIONS") ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        return if (granted) PermissionState.GRANTED else PermissionState.DENIED_BY_USER
    }

    /**
     * [service] 传 ComponentName（调用方用 ComponentName(pkg, cls) 构造即可，
     * 无需手拼字符串）。比较时归一化两种写法：短名 "pkg/.Cls" 与全限定名
     * "pkg/pkg.Cls" 都认，系统存哪种都能命中。
     */
    fun accessibility(context: Context, service: ComponentName?): PermissionState {
        if (service == null) return PermissionState.UNAVAILABLE
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return PermissionState.DENIED_BY_USER
        val hit = enabled.split(":").any { matches(it, context, service) }
        return if (hit) PermissionState.GRANTED else PermissionState.DENIED_BY_USER
    }

    private fun matches(stored: String, context: Context, service: ComponentName): Boolean {
        if (stored == service.flattenToShortString()) return true
        val pkg = stored.substringBefore("/", missingDelimiterValue = "")
        val cls = stored.substringAfter("/", missingDelimiterValue = "")
        if (pkg.isEmpty() || cls.isEmpty()) return false
        val fullCls = if (cls.startsWith(".")) pkg + cls else cls
        return pkg == service.packageName && fullCls == service.className
    }
}
