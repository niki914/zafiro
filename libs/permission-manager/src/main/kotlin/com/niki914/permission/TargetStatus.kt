package com.niki914.permission

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * 目标权限的真实状态静默查询（PRD：status() 报真实权限，Context 注入）。
 * 各通道 handler 共用；查询只读系统状态，不拉任何授权。
 *
 * public：XIpcBridge.postLocalNotification 这类“只看状态、不跑链”的调用方
 * 也经此出口，业务方禁止直连原生权限 API（单测 PermissionEntryGuardTest 兜底）。
 */
object TargetStatus {

    fun overlay(context: Context): PermissionState =
        if (Settings.canDrawOverlays(context)) {
            PermissionState.GRANTED
        } else {
            PermissionState.DENIED_BY_USER
        }

    fun notification(context: Context): PermissionState {
        if (Build.VERSION.SDK_INT < 33) return PermissionState.GRANTED
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
