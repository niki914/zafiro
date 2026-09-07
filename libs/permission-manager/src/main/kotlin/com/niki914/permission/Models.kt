package com.niki914.permission

/** 要什么 */
enum class Permission {
    ROOT,
    SHIZUKU,
    NOTIFICATION,
    OVERLAY,
    ACCESSIBILITY,
}

/** 怎么拿。scope = 通道优先级链 */
enum class Channel {
    ROOT_SHELL,
    SHIZUKU,
    SYSTEM_DIALOG,
    JUMP_SETTINGS,
}

enum class PermissionState {
    GRANTED,
    DENIED_BY_USER,
    UNAVAILABLE,
    FAILED,
}

/** 版本门槛，一等公民。引擎读取，不直接碰 Build.VERSION */
@JvmInline
value class MinSdk(val api: Int)

/** 单环尝试记录 */
data class Attempt(
    val permission: Permission,
    val channel: Channel,
    val state: PermissionState,
    val detail: String? = null,
)

/** 最终结果：先看 finalState，排障看 attempts */
data class PermissionResult(
    val permission: Permission,
    val finalState: PermissionState,
    val attempts: List<Attempt>,
)
