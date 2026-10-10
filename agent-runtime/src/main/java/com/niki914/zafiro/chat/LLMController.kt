package com.niki914.zafiro.chat

import com.niki914.okia.Okia
import com.niki914.okia.conversation.Conversation
import com.niki914.okia.conversation.SessionSnapshot
import com.niki914.okia.mcp.McpServerDiscoverySnapshot
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.tooling.ToolRegistry
import com.niki914.zafiro.api.model.FileRef
import com.niki914.zafiro.chat.agentic.IngestedImage
import com.niki914.zafiro.settings.model.LlmProtocol
import com.niki914.zafiro.settings.model.RuntimeLlmConfig as LlmConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Zafiro 的遗留 LLM 回合调度门面。
 *
 * 实体能力已全部下沉至可多实例化的 [AgentSessionEngine]。
 * 本门面仅代理默认实例，随 M10 演进逐步淘汰。
 */
object LLMController {
    internal const val NO_IDLE_TIMEOUT_SECONDS = Long.MAX_VALUE / 1000

    val defaultEngine = AgentSessionEngine()

    internal val toolRegistry: ToolRegistry get() = defaultEngine.toolRegistry

    internal var okia: Okia?
        get() = defaultEngine.okia
        set(value) { defaultEngine.okia = value }

    internal var okiaFactory: OkiaFactory
        get() = defaultEngine.okiaFactory
        set(value) { defaultEngine.okiaFactory = value }

    val currentConversation: StateFlow<Conversation?> get() = defaultEngine.currentConversation
    val keepScreenOn: StateFlow<Boolean> get() = defaultEngine.keepScreenOn

    internal fun interface OkiaFactory {
        suspend fun create(
            protocol: LlmProtocol,
            restore: SessionSnapshot?,
            config: ResolvedLlmConfig,
        ): Okia
    }

    internal fun resetForTest() {
        defaultEngine.resetForTest()
    }

    suspend fun refresh(): LlmRuntimeSnapshot = defaultEngine.refresh()

    suspend fun refreshFromHookContext(): LlmRuntimeSnapshot = refresh()

    suspend fun snapshot(): LlmRuntimeSnapshot? = defaultEngine.snapshot()

    suspend fun ensureSession(): String = defaultEngine.ensureSession()

    suspend fun openSession(restore: SessionSnapshot) = defaultEngine.openSession(restore)

    fun stream(
        query: String,
        images: List<ContentBlock.Image> = emptyList(),
        files: List<FileRef> = emptyList(),
    ): Flow<LlmStreamEvent> = defaultEngine.stream(query, images, files)

    suspend fun resetConversation() = defaultEngine.resetConversation()

    suspend fun stopCurrentRound() = defaultEngine.stopCurrentRound()

    suspend fun ingestUserImage(uriString: String): IngestedImage? =
        defaultEngine.ingestUserImage(uriString)

    internal fun buildMcpFailureNotice(failed: List<McpServerDiscoverySnapshot>): String =
        TurnPrefixComposer().buildMcpFailureNotice(failed)

    internal fun validateLlmConfig(config: LlmConfig) =
        defaultEngine.sessionAssembler.validateLlmConfig(config)
}
