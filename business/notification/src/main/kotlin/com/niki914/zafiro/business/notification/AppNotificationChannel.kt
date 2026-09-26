package com.niki914.zafiro.business.notification

import android.app.NotificationManager
import androidx.annotation.StringRes

/**
 * 集中定义的系统通知通道规范。
 *
 * 彻底杜绝通道乱建、属性写错（如重要度配置错误导致横幅失效）的问题。
 */
enum class AppNotificationChannel(
    val id: String,
    @param:StringRes val channelNameResId: Int,
    val importance: Int,
) {
    /**
     * 即时提醒通道：用于工具调用提醒、即时消息与重要告警。
     * 重要性为 [NotificationManager.IMPORTANCE_HIGH]，配合 PRIORITY_HIGH 可弹出浮动横幅 (Heads-up)。
     *
     * 注意：部分厂商定制系统（如 ColorOS/Oppo 等）有系统级拦截策略，默认可能关闭三方应用的横幅 (Banner)
     * 或静音，仅保留通知抽屉展示；原生 AOSP / Pixel 等可正常展示浮动横幅。
     */
    Alerts(
        id = "zafiro_alerts",
        channelNameResId = R.string.notification_channel_alerts,
        importance = NotificationManager.IMPORTANCE_HIGH,
    ),

    /**
     * 常驻状态通道：用于前台服务、Agent 后台/常驻状态。
     * 重要性为 [NotificationManager.IMPORTANCE_LOW]，静默展示在通知抽屉中，不弹横幅、不产生声音干扰。
     */
    Resident(
        id = "zafiro_resident",
        channelNameResId = R.string.notification_channel_resident,
        importance = NotificationManager.IMPORTANCE_LOW,
    );
}
