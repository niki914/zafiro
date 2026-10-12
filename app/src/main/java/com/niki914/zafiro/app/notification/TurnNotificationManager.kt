package com.niki914.zafiro.app.notification

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.niki914.logging.Logger
import com.niki914.zafiro.api.model.AgentState
import com.niki914.zafiro.app.MainActivity
import com.niki914.zafiro.app.conversation.ConversationRepo
import com.niki914.zafiro.business.application.ApplicationService
import com.niki914.zafiro.business.notification.AppNotificationChannel
import com.niki914.zafiro.business.notification.NotificationChannelManager
import com.niki914.zafiro.repo.XRepo
import com.niki914.zafiro.service.findService
import com.niki914.zafiro.service.requireService

/**
 * 回合事件即时提醒管理器。
 *
 * 核心职责：
 * 1. 响应式事件分派：监听会话进入等待审批或终态完成事件，向 [NotificationChannelManager] 发送高时效横幅通知；
 * 2. 智能防打扰：当且仅当应用在前台且眼前正展示该会话时静默跳过，避免近距离弹窗骚扰；
 * 3. 宿主隔离：由语音助手发起的回合静默跳过，不向用户弹横幅；
 * 4. 单一口径复用：通知文案与多语言严格复用 [ResidentNotificationBuilder]；
 * 5. 多会话并存与独立更新：通知 ID 派生自 conversationId.hashCode()，同会话自动刷新覆盖，不同会话独立并存；
 * 6. 点击仅切回前台：对齐常驻通知，不干预导航栈，保持用户原有上下文。
 */
object TurnNotificationManager {

    private const val TAG = "niki914_zafiro_TurnNotificationManager"

    @Volatile
    var visibleConversationId: String? = null

    @Volatile
    private var hostActiveConversationId: String? = null

    private var app: Application? = null

    fun init(application: Application) {
        app = application
    }

    fun markHostTurnStarted(conversationId: String) {
        hostActiveConversationId = conversationId
    }

    fun markHostTurnEnded(conversationId: String) {
        if (hostActiveConversationId == conversationId) {
            hostActiveConversationId = null
        }
    }

    fun isConversationVisible(conversationId: String): Boolean {
        val appService = findService<ApplicationService>() ?: return false
        val isForeground = appService.getActivity() != null
        return isForeground && visibleConversationId == conversationId
    }

    suspend fun onTurnEvent(conversationId: String, state: AgentState) {
        if (!XRepo.turnNotificationEnabled()) {
            return
        }
        if (conversationId == "host" || conversationId == hostActiveConversationId) {
            Logger.d(
                TAG,
                "skipping notification: conversation $conversationId is triggered by host"
            )
            return
        }
        if (isConversationVisible(conversationId)) {
            Logger.d(
                TAG,
                "skipping notification: conversation $conversationId is currently visible in foreground"
            )
            return
        }
        if (state is AgentState.Idle && state.lastOutcome == null) {
            return
        }

        val context =
            app ?: runCatching { requireService<ApplicationService>().getApplication() }.getOrNull()
            ?: return
        val titleRes = ResidentNotificationBuilder.resolveTitleResId(state)
        val title = context.getString(titleRes)
        val body = ResidentNotificationBuilder.resolveBody(state, context) ?: title

        val conversationRecord =
            runCatching { ConversationRepo.getConversation(conversationId) }.getOrNull()
        val subText = conversationRecord?.summary?.title?.takeIf { it.isNotBlank() }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(ApplicationService.EXTRA_TARGET_CONVERSATION_ID, conversationId)
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val channelManager = findService<NotificationChannelManager>() ?: return
        channelManager.post(AppNotificationChannel.Alerts, conversationId.hashCode()) {
            val icon =
                context.applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info
            setSmallIcon(icon)
            setContentTitle(title)
            subText?.let { setSubText(it) }
            setContentText(body)
            setStyle(NotificationCompat.BigTextStyle().bigText(body))
            setContentIntent(contentIntent)
            setAutoCancel(true)
        }
    }

    fun cancelNotification(conversationId: String) {
        findService<NotificationChannelManager>()?.cancel(conversationId.hashCode())
    }

    internal fun resetForTest() {
        visibleConversationId = null
        hostActiveConversationId = null
    }
}
