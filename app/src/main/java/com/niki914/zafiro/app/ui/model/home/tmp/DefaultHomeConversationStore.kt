package com.niki914.zafiro.app.ui.model.home.tmp

import com.niki914.zafiro.app.conversation.ConversationRecord
import com.niki914.zafiro.app.conversation.ConversationRepo
import com.niki914.zafiro.app.conversation.ForkKind
import com.niki914.zafiro.app.ui.model.home.HomeConversationStore
import com.niki914.zafiro.repo.XRepo

object DefaultHomeConversationStore : HomeConversationStore {
    override suspend fun lastOpenedConversationId(): String = XRepo.lastOpenedConversationId()
    override suspend fun setLastOpenedConversationId(value: String) =
        XRepo.setLastOpenedConversationId(value)

    override suspend fun loadLastConversationOnStartup(): Boolean =
        XRepo.loadLastConversationOnStartup()

    override suspend fun getConversation(
        id: String,
    ): ConversationRecord? {
        return ConversationRepo.getConversation(id)
    }

    override suspend fun updateDraft(conversationId: String, draftText: String) {
        ConversationRepo.updateDraft(conversationId = conversationId, draftText = draftText)
    }

    override suspend fun deleteConversation(id: String) {
        ConversationRepo.deleteConversation(id)
    }

    override suspend fun forkConversation(
        sourceId: String,
        keepEntryCount: Int,
        kind: ForkKind,
    ): String {
        return ConversationRepo.forkConversation(
            sourceId = sourceId,
            keepEntryCount = keepEntryCount,
            kind = kind,
        )
    }
}