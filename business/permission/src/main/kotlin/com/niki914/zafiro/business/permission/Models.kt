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
     * TODO(改名 STORAGE + 补齐 <30)：这个枚举项表达的是**语义**而不是某个 manifest 权限，
     *  所以更贴切的名字是 `STORAGE`（`EXTERNAL_STORAGE` 命名的是一个具体机制）。改名要连
     *  30 以下的实现一起做：那一步没有 all-files，对应语义是 `READ_EXTERNAL_STORAGE`
     *  运行时权限，需要在 SYSTEM_DIALOG 通道里新开一条「运行时权限」申请路径
     *  （可复用 `MainActivity` 的 RequestPermission → ApplicationService 结果路由）。
     *  在那之前 <30 一律报 UNAVAILABLE（见 TargetStatus.externalStorage）。
     */
    EXTERNAL_STORAGE,
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
