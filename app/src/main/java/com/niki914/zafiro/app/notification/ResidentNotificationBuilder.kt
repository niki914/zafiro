package com.niki914.zafiro.app.notification

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import com.niki914.zafiro.api.model.AgentPhase
import com.niki914.zafiro.api.model.AgentStatus
import com.niki914.zafiro.app.MainActivity
import com.niki914.zafiro.app.R
import com.niki914.zafiro.business.notification.AppNotificationChannel
import com.niki914.zafiro.business.notification.NotificationChannelManager
import com.niki914.zafiro.runtime.service.AgentRuntimeService

/**
 * 常驻通知构建器。
 *
 * 核心设计：
 * - 严格采用系统原生 [NotificationCompat.BigTextStyle] 模版，杜绝 RemoteViews 跨厂商渲染变形与暗黑模式适配问题；
 * - 依托原生 [NotificationCompat.Action] 提供标准化按钮交互（Stop / Approve / Decline）；
 * - 结合 [AgentStatus] 的生命周期状态（[AgentPhase]）与预览内容（preview）动态构建。
 */
object ResidentNotificationBuilder {

    const val NOTIFICATION_ID: Int = 1001

    const val ACTION_STOP = "com.niki914.zafiro.action.RESIDENT_STOP"
    const val ACTION_APPROVE = "com.niki914.zafiro.action.RESIDENT_APPROVE"
    const val ACTION_DECLINE = "com.niki914.zafiro.action.RESIDENT_DECLINE"

    private const val REQUEST_CODE_CONTENT = 101
    private const val REQUEST_CODE_STOP = 102
    private const val REQUEST_CODE_APPROVE = 103
    private const val REQUEST_CODE_DECLINE = 104

    /**
     * 解析当前 [AgentPhase] 对应的本地化标题资源 ID。
     */
    @StringRes
    fun resolveTitleResId(phase: AgentPhase): Int = when (phase) {
        AgentPhase.Idle -> R.string.agent_resident_title_idle
        AgentPhase.Thinking -> R.string.agent_resident_title_thinking
        AgentPhase.Generating -> R.string.agent_resident_title_generating
        AgentPhase.ToolRunning -> R.string.agent_resident_title_tool_running
        AgentPhase.WaitingApproval -> R.string.agent_resident_title_waiting_approval
        AgentPhase.Stopping -> R.string.agent_resident_title_stopping
    }

    /**
     * 解析常驻通知的大文本正文：
     * 若当前 status.preview 存在则返回；若无内容则返回 null（通知只展示标题，不填充无意义兜底文本）。
     */
    fun resolveBody(status: AgentStatus): String? {
        return status.preview?.takeIf { it.isNotBlank() }
    }

    /**
     * 生成点击常驻通知主体跳转主页的 [PendingIntent]。
     */
    fun createContentIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(context, REQUEST_CODE_CONTENT, intent, flags)
    }

    fun createStopIntent(context: Context): PendingIntent {
        val intent = Intent(context, AgentRuntimeService::class.java).apply {
            action = ACTION_STOP
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getService(context, REQUEST_CODE_STOP, intent, flags)
    }

    fun createApproveIntent(context: Context): PendingIntent {
        val intent = Intent(context, AgentRuntimeService::class.java).apply {
            action = ACTION_APPROVE
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getService(context, REQUEST_CODE_APPROVE, intent, flags)
    }

    fun createDeclineIntent(context: Context): PendingIntent {
        val intent = Intent(context, AgentRuntimeService::class.java).apply {
            action = ACTION_DECLINE
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getService(context, REQUEST_CODE_DECLINE, intent, flags)
    }

    /**
     * 构建常驻通知 [Notification] 实例。
     */
    fun build(
        context: Context,
        channelManager: NotificationChannelManager,
        status: AgentStatus,
        contentIntent: PendingIntent? = createContentIntent(context),
        stopIntent: PendingIntent? = createStopIntent(context),
        approveIntent: PendingIntent? = createApproveIntent(context),
        declineIntent: PendingIntent? = createDeclineIntent(context),
    ): Notification {
        return channelManager.buildNotification(AppNotificationChannel.Resident) {
            val icon = context.applicationInfo.icon.takeIf { it != 0 }
                ?: android.R.drawable.ic_dialog_info
            setSmallIcon(icon)

            val title = context.getString(resolveTitleResId(status.phase))
            setContentTitle(title)

            val body = resolveBody(status)
            if (body != null) {
                setContentText(body)
                setStyle(NotificationCompat.BigTextStyle().bigText(body))
            }

            if (contentIntent != null) {
                setContentIntent(contentIntent)
            }
            setOngoing(true)
            setOnlyAlertOnce(true)
            setCategory(NotificationCompat.CATEGORY_SERVICE)

            when (status.phase) {
                AgentPhase.Generating,
                AgentPhase.Thinking,
                AgentPhase.ToolRunning -> {
                    if (stopIntent != null) {
                        addAction(
                            0,
                            context.getString(R.string.agent_resident_action_stop),
                            stopIntent,
                        )
                    }
                }

                AgentPhase.WaitingApproval -> {
                    if (approveIntent != null) {
                        addAction(
                            0,
                            context.getString(R.string.agent_resident_action_approve),
                            approveIntent,
                        )
                    }
                    if (declineIntent != null) {
                        addAction(
                            0,
                            context.getString(R.string.agent_resident_action_decline),
                            declineIntent,
                        )
                    }
                }

                AgentPhase.Idle,
                AgentPhase.Stopping -> {
                    // 无操作按钮
                }
            }
        }
    }
}
