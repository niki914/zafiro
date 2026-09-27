package com.niki914.zafiro.business.notification

import android.app.Notification
import androidx.core.app.NotificationCompat

/**
 * 应用级通知通道与安全发送门面。
 *
 * 核心设计：
 * 1. 通道集中管理：所有通知必须指定 [AppNotificationChannel] 发送，通道自动按需初始化与对齐；
 * 2. 权限无感守卫：内部在发送时自动校验通知权限（经由 PermissionManager），无权限时安全跳过；
 * 3. 样式完全自由：回调中直接暴露 [NotificationCompat.Builder]，UI 怎么排版完全交给业务方自定义。
 */
interface NotificationChannelManager {

    /**
     * 检查应用当前是否具备通知权限。
     */
    fun isNotificationPermissionGranted(): Boolean

    /**
     * 在指定的通道上安全发送一条通知。
     *
     * 1. 自动确保通道已创建；
     * 2. 自动检查通知权限（通过 PermissionManager），未授权则返回 false；
     * 3. 构造绑定了该通道的 [NotificationCompat.Builder] 回调给 [block] 进行自定义；
     * 4. 提交通知。
     *
     * @param channel 发送所使用的系统通道
     * @param notificationId 通知的唯一 ID
     * @param block 用于配置 NotificationCompat.Builder 的 DSL 块
     * @return 成功发送返回 true；若被权限拦截返回 false
     */
    suspend fun post(
        channel: AppNotificationChannel,
        notificationId: Int,
        block: NotificationCompat.Builder.() -> Unit,
    ): Boolean

    /**
     * 仅构建并配置指定通道的 [Notification] 实例（不直接提交 notify）。
     *
     * 专供前台服务 [android.app.Service.startForeground] 或需要自行持有 Notification 的场景。
     *
     * @param channel 目标通道
     * @param block 用于配置 NotificationCompat.Builder 的 DSL 块
     * @return 构建好的系统 Notification 实例
     */
    fun buildNotification(
        channel: AppNotificationChannel,
        block: NotificationCompat.Builder.() -> Unit,
    ): Notification

    /**
     * 取消/撤销指定的通知。
     */
    fun cancel(notificationId: Int)
}
