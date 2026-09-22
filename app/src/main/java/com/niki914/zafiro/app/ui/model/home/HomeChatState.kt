package com.niki914.zafiro.app.ui.model.home

import com.niki914.okia.conversation.SessionSnapshot
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.zafiro.app.conversation.ConversationRecord
import com.niki914.zafiro.app.conversation.ForkKind
import com.niki914.zafiro.chat.LlmErrorCode
import com.niki914.zafiro.chat.LlmStreamEvent
import kotlinx.coroutines.flow.Flow

internal interface HomeConversationStore {
    suspend fun lastOpenedConversationId(): String
    suspend fun setLastOpenedConversationId(value: String)
    suspend fun loadLastConversationOnStartup(): Boolean
    suspend fun createConversation(id: String, firstUserInput: String)
    suspend fun getConversation(id: String): ConversationRecord?
    suspend fun updateDraft(conversationId: String, draftText: String)
    suspend fun deleteConversation(id: String)

    // TODO(收进 Agent)：历史派生操作（reGenerate / fork / rewind）本轮留在业务侧自己组合
    //  fork（仓储）→ load（Agent）→ stream（Agent）。契约暂无 fork / delete 命令，为此不改。
    suspend fun forkConversation(sourceId: String, keepEntryCount: Int, kind: ForkKind): String
}


enum class ActionSource { User, Agent }

/**
 * 消息操作行显示模式：OnTap 点击消息弹出、再点收起；
 * Always 常显、永不收回，按钮去背景只留图标（降噪音）。
 */
enum class MessageActionsDisplay { OnTap, Always }

enum class HomeToolState {
    Running,
    Succeeded,
    Failed,
}

data class HomeToolStatus(
    val callId: String? = null,
    val name: String,
    val state: HomeToolState,
    val resultText: String? = null,
    val failedReason: String? = null,
    /** 本地化显示名 res id；null → 回退 [name]（Custom Tool / MCP）。 */
    val displayNameRes: Int? = null,
    /** 工具参数原文（复制用）；显示预览由 UI 从原文裁剪。null → 只显示标题无预览、无复制。 */
    val inputText: String? = null,
    /** 工具返回的图片引用（view_image / screenshot 等），path 指向 image_cache 落盘文件。 */
    val images: List<HomeChatImage> = emptyList(),
)

sealed interface HomeChatBlock {
    data class Text(val text: String) : HomeChatBlock
    data class Thinking(val id: Int, val text: String) : HomeChatBlock
    data class Tool(val status: HomeToolStatus) : HomeChatBlock
    data class Error(
        val message: String?,
        val code: LlmErrorCode? = null,
        /** RetryExhausted 专属：已耗尽的重试次数。 */
        val attempts: Int? = null,
    ) : HomeChatBlock

    /**
     * 瞬时重试提示：传输层自动重试进行中。不进持久化状态（落盘无意义），
     * 下一个流事件到达即清除（见 [HomeChatViewModel.applyEvent]）。
     */
    data class Retrying(
        val attempt: Int,
        val maxAttempts: Int,
        val delayMs: Long,
        val reason: String,
    ) : HomeChatBlock
}

data class HomeChatTurn(
    val id: Long,
    val userText: String,
    val images: List<HomeChatImage> = emptyList(),
    val blocks: List<HomeChatBlock> = emptyList(),
)

/**
 * 用户消息附带的图片（落盘路径引用）。
 * 待发送与已发送共用：send 时 pendingImages 移入 turn.images，字段语义不变。
 * path：落盘路径（发送链路与 UI 渲染共用；图片字节在 app 沙箱，重启不丢）。
 */
data class HomeChatImage(
    val id: String,
    val path: String,
) {
    companion object {
        /** ContentBlock.Image（工具结果/历史恢复）→ UI 图片卡模型，id 取 path hash 保持跨会话稳定。 */
        fun of(block: ContentBlock.Image): HomeChatImage =
            HomeChatImage(id = block.path.hashCode().toString(), path = block.path)
    }
}

data class HomeChatUiState(
    val input: String = "",
    /** 待发送图片（composer 上方图片条）。send 时移入新 turn.images 并清空。 */
    val pendingImages: List<HomeChatImage> = emptyList(),
    val turns: List<HomeChatTurn> = emptyList(),
    val isGenerating: Boolean = false,
    val isLoadingConversation: Boolean = false,
    val lastEventName: String? = null,
    val streamEventCount: Int = 0,
    val currentConversationId: String? = null,
    val currentConversationTitle: String? = null,
    val expandedToolRuns: Set<String> = emptySet(),
    val expandedToolResults: Set<String> = emptySet(),
    val expandedThinking: Set<String> = emptySet(),
    val expandedActionTurnId: Long? = null,
    val expandedActionSource: ActionSource? = null,
    /** 当前正在流式产生的思考块 key；仅驱动 thinking 块内滚动跟随。 */
    val activeThinkingKey: String? = null,
    /**
     * 仍处于「自动展开、未被用户干预」的思考块 key（"${turnId}_${blockIndex}"）。
     * 新块首发时收起本集合中的旧块再展开新块；用户 toggle 过的块移出本集合，不再被自动收起。
     * （首发与续接回声的区分改用 turns 中是否已存在同 id Thinking 块判断，见 applyEvent。）
     */
    val autoExpandedThinking: Set<String> = emptySet(),
)

/**
 * 会话切换时的统一瞬态清理：三组展开态 + 操作行 + active thinking 指针 + 自动展开记录全清，
 * 新会话不复用任何展开状态。所有会话切换路径（restore/load/new/delete）统一调用。
 */
fun HomeChatUiState.withClearedTransient() = copy(
    pendingImages = emptyList(),
    expandedToolRuns = emptySet(),
    expandedToolResults = emptySet(),
    expandedThinking = emptySet(),
    expandedActionTurnId = null,
    expandedActionSource = null,
    activeThinkingKey = null,
    autoExpandedThinking = emptySet(),
)

sealed interface HomeChatIntent {
    data class InputChanged(val value: String) : HomeChatIntent
    data object Send : HomeChatIntent

    /** 相册选图完成：uri → ingest 落盘 → 加入 pendingImages。失败静默（记日志）。 */
    data class ImageAttached(val uri: String) : HomeChatIntent
    data class ImageRemoved(val id: String) : HomeChatIntent
    data object StopGenerating : HomeChatIntent
    data object NewConversation : HomeChatIntent
    data class LoadConversation(val id: String) : HomeChatIntent
    data class DeleteConversation(val id: String) : HomeChatIntent
    data class ToggleToolRun(val turnId: Long, val runStartIndex: Int) : HomeChatIntent
    data class ToggleToolResult(val turnId: Long, val runStartIndex: Int, val toolIndex: Int) :
        HomeChatIntent

    data class ToggleThinking(val turnId: Long, val blockIndex: Int) : HomeChatIntent
    data class ToggleActionRow(val turnId: Long, val source: ActionSource) : HomeChatIntent
    data class ReGenerateAt(val turnId: Long) : HomeChatIntent
    data class ForkAt(val turnId: Long) : HomeChatIntent
    data class RewindAt(val turnId: Long) : HomeChatIntent
}

internal interface HomeChatRuntime {
    fun stream(query: String, images: List<ContentBlock.Image>): Flow<LlmStreamEvent>
    suspend fun resetConversation()
    suspend fun stopCurrentRound()
    suspend fun ensureSession(): String
    suspend fun openSession(restore: SessionSnapshot)
    suspend fun historySnapshot(): List<Message>

    /** 相册 URI → ingest 落盘 → path。失败返回 null（静默丢弃）。 */
    suspend fun ingestImage(uri: String): HomeChatImage?
}
