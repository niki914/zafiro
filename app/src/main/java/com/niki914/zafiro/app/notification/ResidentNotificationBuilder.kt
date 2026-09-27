package com.niki914.zafiro.app.notification

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import com.niki914.zafiro.api.model.AgentState
import com.niki914.zafiro.api.model.ApprovalRequest
import com.niki914.zafiro.api.model.TurnOutcome
import com.niki914.zafiro.app.MainActivity
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.model.ToolPresentation
import com.niki914.zafiro.business.notification.AppNotificationChannel
import com.niki914.zafiro.business.notification.NotificationChannelManager
import com.niki914.zafiro.runtime.service.AgentRuntimeService

/**
 * 常驻通知构建器。
 *
 * 核心设计：
 * - 严格采用系统原生 [NotificationCompat.BigTextStyle] 模版，杜绝 RemoteViews 跨厂商渲染变形与暗黑模式适配问题；
 * - 依托原生 [NotificationCompat.Action] 提供标准化按钮交互（Stop / Approve / Decline）；
 * - 结合 [AgentState] 的类型化阶段与内生数据动态构建。
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

    /** state 存 500，通知取一行 120（单行化后裁剪，不切开代理对；超长补显式 …）。 */
    private const val BODY_MAX_CHARS = 120

    private val WHITESPACE = Regex("\\s+")

    /**
     * 解析当前 [AgentState] 对应的本地化标题资源 ID。
     * [AgentState.Idle] 按结局拆：新鲜态 / Completed 共用 StandBy，
     * Failed / Interrupted 各有标题（文案归属业务方，契约只给数据）。
     */
    @StringRes
    fun resolveTitleResId(state: AgentState): Int = when (state) {
        is AgentState.Idle -> when (state.lastOutcome) {
            null, TurnOutcome.Completed -> R.string.agent_resident_title_idle
            TurnOutcome.Failed -> R.string.agent_resident_title_failed
            TurnOutcome.Interrupted -> R.string.agent_resident_title_interrupted
        }
        is AgentState.Thinking -> R.string.agent_resident_title_thinking
        is AgentState.Generating -> R.string.agent_resident_title_generating
        is AgentState.ToolRunning -> R.string.agent_resident_title_tool_running
        is AgentState.WaitingApproval -> R.string.agent_resident_title_waiting_approval
        AgentState.Stopping -> R.string.agent_resident_title_stopping
    }

    /**
     * 解析常驻通知的大文本正文：
     * 进行中取当段文本，工具执行中取工具运行文案（与悬浮球同口径），
     * 审批取 `command ?: toolName`（知情同意取标题），Idle 取末轮尾巴
     * `lastText`，Stopping 无正文。若无内容则返回 null
     * （通知只展示标题，不填充无意义兜底文本）。
     */
    fun resolveBody(state: AgentState, context: Context): String? {
        val raw = when (state) {
            is AgentState.Generating -> state.text
            is AgentState.Thinking -> state.text
            is AgentState.ToolRunning ->
                ToolPresentation.runningText(context, state.toolName, state.label)

            is AgentState.WaitingApproval -> when (val req = state.request) {
                is ApprovalRequest.ToolExecution ->
                    req.command.takeIf { it.isNotBlank() } ?: req.toolName
                is ApprovalRequest.ScreenControlConsent ->
                    context.getString(R.string.screen_control_consent_title)
            }
            is AgentState.Idle -> state.lastText
            AgentState.Stopping -> null
        }
        val singleLine = raw?.replace(WHITESPACE, " ")?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (singleLine.length <= BODY_MAX_CHARS) return singleLine
        val end = BODY_MAX_CHARS - 1
        val cut = if (Character.isHighSurrogate(singleLine[end - 1])) end - 1 else end
        return singleLine.substring(0, cut) + "…"
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
        status: AgentState,
        contentIntent: PendingIntent? = createContentIntent(context),
        stopIntent: PendingIntent? = createStopIntent(context),
        approveIntent: PendingIntent? = createApproveIntent(context),
        declineIntent: PendingIntent? = createDeclineIntent(context),
    ): Notification {
        return channelManager.buildNotification(AppNotificationChannel.Resident) {
            val icon = context.applicationInfo.icon.takeIf { it != 0 }
                ?: android.R.drawable.ic_dialog_info
            setSmallIcon(icon)

            val title = context.getString(resolveTitleResId(status))
            setContentTitle(title)

            val body = resolveBody(status, context)
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

            when (status) {
                is AgentState.Generating,
                is AgentState.Thinking,
                is AgentState.ToolRunning -> {
                    if (stopIntent != null) {
                        addAction(
                            0,
                            context.getString(R.string.agent_resident_action_stop),
                            stopIntent,
                        )
                    }
                }

                is AgentState.WaitingApproval -> {
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

                is AgentState.Idle,
                AgentState.Stopping -> {
                    // 无操作按钮
                }
            }
        }
    }
}
