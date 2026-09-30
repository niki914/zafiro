package com.niki914.zafiro.app.ui.model.home

import com.niki914.zafiro.api.model.ApprovalDecision
import com.niki914.zafiro.api.model.ApprovalRequest
import com.niki914.zafiro.app.conversation.ConversationRecord
import com.niki914.zafiro.app.conversation.ForkKind
import com.niki914.zafiro.app.conversation.ForkResult
import com.niki914.zafiro.chat.LlmErrorCode

internal interface HomeConversationStore {
    suspend fun lastOpenedConversationId(): String
    suspend fun setLastOpenedConversationId(value: String)
    suspend fun loadLastConversationOnStartup(): Boolean
    suspend fun getConversation(id: String): ConversationRecord?
    suspend fun updateDraft(conversationId: String, draftText: String)
    suspend fun deleteConversation(id: String)

    // TODO(收进 Agent)：历史派生操作（reGenerate / fork / rewind）本轮留在业务侧自己组合
    //  fork（仓储）→ load（Agent）→ stream（Agent）。契约暂无 fork / delete 命令，为此不改。
    //  截断点与回填内容由仓储按回合计算，UI 侧不见底层条目与 okia 消息。
    suspend fun forkAtTurn(sourceId: String, turnIndex: Int, kind: ForkKind): ForkResult?
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
    data class Thinking(val id: Int, val text: String, val isComplete: Boolean = false) : HomeChatBlock
    data class Tool(val status: HomeToolStatus) : HomeChatBlock
    data class Error(
        val message: String?,
        val code: LlmErrorCode? = null,
        /** RetryExhausted 专属：已耗尽的重试次数。 */
        val attempts: Int? = null,
    ) : HomeChatBlock

    /**
     * 瞬时重试提示：传输层自动重试进行中。不进持久化状态（落盘无意义），
     * 下一个流事件到达即清除（由 `ConversationReducer` 处理）。
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
    val files: List<HomeChatFile> = emptyList(),
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
)

/**
 * 用户消息附带的文件 / 文件夹引用（**真实路径**，不是沙箱路径 —— 文件不拷贝）。
 * 待发送与已发送共用：send 时 pendingFiles 移入 turn.files，字段语义不变。
 *
 * 显示名是路径的 basename（「诚实的文件命名」）：不另存名字字段，
 * 免得名字和硬盘上的东西脱钩。目录靠末尾 `/` 识别，[name] 会把它去掉。
 */
data class HomeChatFile(
    val id: String,
    val path: String,
) {
    val name: String get() = path.trimEnd('/').substringAfterLast('/')
}

data class HomeChatUiState(
    val input: String = "",
    /** 待发送图片（composer 上方图片条）。send 时移入新 turn.images 并清空。 */
    val pendingImages: List<HomeChatImage> = emptyList(),
    /** 待发送文件 / 文件夹（composer 上方文件条）。send 时移入新 turn.files 并清空。 */
    val pendingFiles: List<HomeChatFile> = emptyList(),
    val turns: List<HomeChatTurn> = emptyList(),
    val isGenerating: Boolean = false,
    val isLoadingConversation: Boolean = false,
    /** 内容发射版本，用于 Compose 贴底触发；不携带事件类型。 */
    val conversationVersion: Int = 0,
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
    /**
     * 前台待裁决的审批请求；非空即渲染对应对话框。裁决用
     * [HomeChatIntent.ResolveApproval]，结算后归 null。
     */
    val pendingApproval: ApprovalRequest? = null,
)

/**
 * 会话切换时的统一瞬态清理：三组展开态 + 操作行 + active thinking 指针 + 自动展开记录全清，
 * 新会话不复用任何展开状态。所有会话切换路径（restore/load/new/delete）统一调用。
 */
fun HomeChatUiState.withClearedTransient() = copy(
    pendingImages = emptyList(),
    pendingFiles = emptyList(),
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

    /** 相册选图完成：追加一个待落盘项，实现侧完成后从 draft 读回。失败时该项消失。 */
    data class ImageAttached(val uri: String) : HomeChatIntent
    data class ImageRemoved(val id: String) : HomeChatIntent

    /**
     * 文件 / 文件夹选择完成（SAF 返回的 `content://`）。
     *
     * 解析路径、要全局文件访问权都在 VM 侧跑完（可能要跳设置页等用户回来）：
     * 拿到才把卡片加进草稿，拿不到经 [HomeChatEffect.FileAttachFailed] 回吐 toast。
     */
    data class FileAttached(val uri: String) : HomeChatIntent
    data class FileRemoved(val id: String) : HomeChatIntent

    /**
     * 相机拍完（uri 是给相机写的 FileProvider 临时文件，path 是它在 cache 里的位置）。
     * 与 [ImageAttached] 的差别：原图在**我们自己的 cache** 里，ingest 完成后要把它删掉。
     */
    data class CameraCaptured(val uri: String, val path: String) : HomeChatIntent
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

    /** 前台对话框的裁决回灌；由 [com.niki914.zafiro.api.Approver] 实现侧等待。 */
    data class ResolveApproval(val decision: ApprovalDecision) : HomeChatIntent
}

/**
 * 一次性效果。**持久**的状态（对话框、展开态）继续走 [HomeChatUiState]；
 * 用过即弃的提示走这里，不污染状态。
 */
sealed interface HomeChatEffect {
    data class FileAttachFailed(val reason: FileAttachReason) : HomeChatEffect
}

/**
 * 附件失败的原因，与 `business:files` 的 `FileAttachResult` 一一对应。
 * 定义在这里而不是直接用那个类型：content 包不依赖 `business:api` / `business:files`，
 * 契约类型到 UI 模型的翻译只发生在 mapper 与 ViewModel 里。
 */
enum class FileAttachReason {
    /** 解析不出真实路径（云盘 / SD 卡 / 其它 provider）。 */
    Unresolvable,

    /** 没拿到全局文件访问权。 */
    NoPermission,

    /** 权限拿到了但路径读不到。 */
    Unreadable,
}
