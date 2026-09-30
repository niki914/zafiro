package com.niki914.zafiro.business.permission

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings

/**
 * 目标权限的真实状态静默查询（PRD：status() 报真实权限，Context 注入）。
 * 各通道 handler 共用；查询只读系统状态，不拉任何授权。
 *
 * 「哪个 API 上算什么机制」全在 [PermissionSpec]；这里只管机制对应的系统查询怎么写。
 *
 * 模块私有：业务方一律经 PermissionManager.status()，禁止直连此处与原生权限 API。
 */
internal object TargetStatus {

    /**
     * 「要什么」→ 真实状态的唯一分派点。RootShell/Shizuku handler 的 status 与
     * SYSTEM_DIALOG / JUMP_SETTINGS 的复查都走这里，保证各处口径不分叉。
     */
    fun query(
        context: Context,
        permission: Permission,
        accessibilityService: ComponentName?,
    ): PermissionState =
        when (val mechanism = PermissionSpec.appLevelMechanism(permission, Build.VERSION.SDK_INT)) {
            // 能力型目标（ROOT/SHIZUKU）静默嗅探会拉起授权，不查，由各 shell 通道自己判定
            null -> PermissionState.UNKNOWN
            GrantMechanism.None -> PermissionState.GRANTED
            is GrantMechanism.Runtime -> runtimePermission(context, mechanism)
            is GrantMechanism.AppOp -> when (permission) {
                Permission.STORAGE -> storage()
                Permission.OVERLAY -> overlay(context)
                else -> PermissionState.UNKNOWN
            }

            GrantMechanism.Accessibility -> accessibility(context, accessibilityService)
        }

    /** 一组运行时权限全部到手才算 GRANTED（同权限组的成员系统会一起给）。 */
    private fun runtimePermission(
        context: Context,
        mechanism: GrantMechanism.Runtime,
    ): PermissionState {
        val granted = mechanism.names.all {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
        return if (granted) PermissionState.GRANTED else PermissionState.DENIED_BY_USER
    }

    /**
     * 全局文件访问的真实状态。这是 30+ 的概念：版本门在 [PermissionSpec]（<30 报运行时权限），
     * 这里的 SDK 判断只是给 lint(NewApi=error) 与「spec 万一判错」兜底，不是第二个分叉点。
     */
    fun storage(): PermissionState =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            PermissionState.GRANTED
        } else {
            PermissionState.DENIED_BY_USER
        }

    fun overlay(context: Context): PermissionState =
        if (Settings.canDrawOverlays(context)) {
            PermissionState.GRANTED
        } else {
            PermissionState.DENIED_BY_USER
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
