package com.niki914.zafiro.app.ui.model.home.tmp

import com.niki914.okia.conversation.SessionSnapshot
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.zafiro.api.Agent
import com.niki914.zafiro.api.model.Attachment
import com.niki914.zafiro.api.model.DraftImage
import com.niki914.zafiro.app.ui.model.home.HomeChatImage
import com.niki914.zafiro.app.ui.model.home.HomeChatRuntime
import com.niki914.zafiro.business.agent.AgentImpl
import com.niki914.zafiro.chat.LLMController
import com.niki914.zafiro.chat.LlmStreamEvent
import com.niki914.zafiro.service.requireService
import kotlinx.coroutines.flow.Flow
import java.util.UUID

// 存废：阶段 5 删除（业务侧接缝，被 Agent 取代；M3e 删）
object LlmHomeChatRuntime : HomeChatRuntime {
    private fun agent(): Agent = requireService()

    override fun stream(
        query: String,
        images: List<ContentBlock.Image>
    ): Flow<LlmStreamEvent> {
        // 命令已走契约；事件仍从实现侧的过渡通道收（`events_Tmp`，P2 删）
        agent().updateDraft { draft ->
            draft.copy(
                text = query,
                images = images.map {
                    DraftImage.Ready(
                        Attachment(
                            path = it.path,
                            mimeType = it.mimeType
                        )
                    )
                },
            )
        }
        agent().stream()
        return AgentImpl.events_Tmp
    }

    override suspend fun resetConversation() {
        agent().discard()
    }

    override suspend fun stopCurrentRound() {
        agent().stop()
    }

    override suspend fun ensureSession(): String = LLMController.ensureSession()
    override suspend fun openSession(restore: SessionSnapshot) =
        LLMController.openSession(restore)

    override suspend fun historySnapshot(): List<Message> = LLMController.historySnapshot()

    override suspend fun ingestImage(uri: String): HomeChatImage? {
        val ingested = LLMController.ingestUserImage(uri) ?: return null
        return HomeChatImage(
            id = UUID.randomUUID().toString(),
            path = ingested.path,
        )
    }
}