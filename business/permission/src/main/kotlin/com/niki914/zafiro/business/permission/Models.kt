package com.niki914.zafiro.business.permission

/** 要什么 */
enum class Permission {
    ROOT,
    SHIZUKU,
    NOTIFICATION,
    OVERLAY,
    ACCESSIBILITY,

    /**
     * 能按路径读写外部文件（“真的能读写用户文件”这件事的语义目标）。
     *
     * 命名是语义而不是机制：30+ 的机制是 `MANAGE_EXTERNAL_STORAGE`（all-files），
     * <30 是 `READ/WRITE_EXTERNAL_STORAGE` 运行时权限。机制与版本的分叉见 [PermissionSpec]。
     */
    STORAGE,
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

    /** 无法静默得知（如 root 嗅探会拉起授权）。非成功也非失败，链中视为未成功继续降级 */
    UNKNOWN,
}

/** 版本门槛，一等公民。引擎读取，不直接碰 Build.VERSION */
@JvmInline
internal value class MinSdk(val api: Int)

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
