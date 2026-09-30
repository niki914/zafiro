package com.niki914.zafiro.business.permission

internal interface ChannelHandler {
    val channel: Channel

    /** 版本门槛。实现类标 @RequiresApi，lint NewApi(error) 编译期兜底 */
    val minSdk: MinSdk

    /** 永远静默：不弹窗、不跳页、不写 */
    fun status(permission: Permission): PermissionState

    /**
     * 契约：必须在状态确定后返回，不允许"发射后不管"。
     * - SYSTEM_DIALOG：弹窗回调返回时确定
     * - JUMP_SETTINGS：launch → 等 resume → 复查一次 status()
     * - shell：exit code 校验后确定
     * Activity 销毁致挂起取消 = 本次无结果，下次 status() 兜底。
     */
    suspend fun request(permission: Permission): PermissionState
}

/**
 * 应用级权限（overlay / notification / 无障碍）的「能给」判定：两个 shell 通道共用。
 * 无障碍额外要求组件名可用；能力型权限（root / shizuku）不归应用级通道管，由各通道自己声明。
 */
internal fun Permission.isAppLevelGrantable(accessibilityServiceAvailable: Boolean): Boolean =
    when (this) {
        Permission.OVERLAY, Permission.NOTIFICATION, Permission.EXTERNAL_STORAGE -> true
        Permission.ACCESSIBILITY -> accessibilityServiceAvailable
        Permission.ROOT, Permission.SHIZUKU -> false
    }
