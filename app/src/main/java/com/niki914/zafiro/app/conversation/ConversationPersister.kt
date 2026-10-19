package com.niki914.zafiro.app.conversation

import com.niki914.logging.Logger
import com.niki914.okia.conversation.Conversation
import com.niki914.okia.conversation.ConversationEntry
import com.niki914.zafiro.api.model.AgentState
import com.niki914.zafiro.app.notification.TurnNotificationManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 消息级增量持久化器（T3，D3-1/D3-2/D3-8）。
 *
 * 观察会话快照流（由各持久化 Agent 实例提供），对比已持久化条数，把新 commit 的消息增量插入 Room。
 * - 新增消息永远追加在 leaf 投影尾部（线性 append-only），drop(persistedCount)
 *   就是新消息；插入按 (conversation_id, id) 幂等（@Insert IGNORE），观察协程
 *   重启/切会话重复观察不重复写
 * - parentId 从投影前一条 id 推导（Zafiro 无 rewind，树恒线性，D3-3）
 * - 会话切换：快照 id 变化 → 新会话从 Room 现有条数开始对比（恢复场景
 *   Room 已有全量，不重复插）
 * - 崩溃窗口：消息已 commit 但观察者未落盘 → 丢这条（"半句话"扩展版，
 *   D3-1 日记比喻，可接受）
 * - 错误回合：OKIA Failed 时 commitPartial 已把半条 assistant 放回树，
 *   本器自然落盘（D3-4，bug 修复）
 */
object ConversationPersister {
    private const val LOG_TAG = "niki914_zafiro_ConversationPersister"

    /** 已持久化条数（按会话 id 隔离）。内存态，重启后按 Room 现有条数重建。 */
    private val persistedCountBySession = ConcurrentHashMap<String, Int>()

    fun start(
        scope: CoroutineScope,
        source: Flow<Conversation?>,
    ) {
        scope.launch {
            source.collect { snapshot ->
                snapshot?.let {
                    try {
                        persistNow(it)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // 单条失败隔离（问题 5 修复）：会话已删导致的外键违规等
                        // 不杀死唯一 collector——重置该会话计数（下次快照重新
                        // 对比），记日志后继续，后续会话持久化不受影响。
                        Logger.e(
                            LOG_TAG,
                            "persist failed sessionId=${it.id} " +
                                    "errorType=${e::class.simpleName} message=${e.message}"
                        )
                        persistedCountBySession.remove(it.id)
                    }
                }
            }
        }
        Logger.i(LOG_TAG, "persister started")
    }

    /** 观察指定 Agent 引擎的会话快照流并增量落盘（支持多 Agent 并发）。 */
    fun trackEngine(
        scope: CoroutineScope,
        source: Flow<Conversation?>,
    ) {
        start(scope, source)
    }

    internal fun resetForTest() {
        persistedCountBySession.clear()
    }

    /**
     * 生命周期边界刷新：进入等待审批、或回合结束落回 [AgentState.Idle] 时更新会话的
     * 最近活跃时间，把它顶到列表最前。
     *
     * - 生成中的增量落盘不碰时间戳（多会话并发时列表会上下跳）；
     * - 不带 [AgentState.Idle.lastOutcome] 的空闲态不算边界：启动、载入会话、
     *   重命名都会落回空闲，否则打开一个旧会话就会把它顶到最前。
     */
    fun trackActivity(
        scope: CoroutineScope,
        status: Flow<AgentState>,
        conversationId: () -> String?,
    ) {
        scope.launch {
            status.distinctUntilChanged().collect { state ->
                val boundary = when (state) {
                    is AgentState.WaitingApproval -> true
                    is AgentState.Idle -> state.lastOutcome != null
                    else -> false
                }
                if (boundary) {
                    conversationId()?.let { id ->
                        try {
                            ConversationRepo.touchConversation(id)
                            ConversationRepo.setHasUnread(id, true)
                            TurnNotificationManager.onTurnEvent(id, state)
                        } catch (t: Throwable) {
                            if (t is CancellationException) throw t
                            Logger.w(LOG_TAG, "trackActivity failed for id=$id: ${t.message}", t)
                        }
                    }
                }
            }
        }
    }

    /** 单条快照的增量落盘（internal 供测试直接调用，绕开流接线与 Room IO 时序）。 */
    internal suspend fun persistNow(conversation: Conversation) {
        val sessionId = conversation.id
        val persisted = persistedCountBySession.getOrPut(sessionId) {
            ConversationRepo.countEntries(sessionId)
        }
        val history = conversation.history
        if (history.size <= persisted) return

        val newEntries = history.drop(persisted).mapIndexed { index, entry ->
            val absoluteIndex = persisted + index
            ConversationEntry(
                id = entry.id,
                parentId = if (absoluteIndex == 0) null else history[absoluteIndex - 1].id,
                timestamp = entry.timestamp,
                message = entry.message,
            )
        }

        ConversationRepo.appendEntriesAtomically(
            conversationId = sessionId,
            entries = newEntries,
            leafId = conversation.leafId ?: newEntries.last().id,
            lastMessagePreview = ConversationFormatter.previewFromMessages(history),
            turnCount = history.size,
        )
        persistedCountBySession[sessionId] = history.size
        Logger.d(
            LOG_TAG,
            "persisted sessionId=$sessionId persisted=$persisted -> ${history.size} " +
                    "new=${newEntries.size}"
        )
    }
}
