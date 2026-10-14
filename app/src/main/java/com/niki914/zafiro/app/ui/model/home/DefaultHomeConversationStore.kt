package com.niki914.zafiro.app.ui.model.home

import com.niki914.zafiro.app.conversation.ConversationRecord
import com.niki914.zafiro.app.conversation.ConversationRepo
import com.niki914.zafiro.app.conversation.ConversationSummary
import com.niki914.zafiro.app.conversation.ForkKind
import com.niki914.zafiro.app.conversation.ForkResult
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

    override suspend fun draftText(): String = XRepo.draftText()

    override suspend fun setDraftText(value: String) = XRepo.setDraftText(value)

    override suspend fun bindConfig(conversationId: String) {
        val record = ConversationRepo.getConversation(conversationId) ?: return
        if (record.configId.isNotBlank()) return
        val activeId = XRepo.llmConfigs.document().activeId.orEmpty()
        if (activeId.isNotBlank()) {
            ConversationRepo.updateConfigId(conversationId, activeId)
        }
    }

    override suspend fun updateConfigId(conversationId: String, configId: String) {
        ConversationRepo.updateConfigId(conversationId, configId)
    }

    override suspend fun deleteConversation(id: String) {
        ConversationRepo.deleteConversation(id)
    }

    override suspend fun clearUnread(conversationId: String) {
        ConversationRepo.setHasUnread(conversationId, false)
    }

    override suspend fun forkAtTurn(
        sourceId: String,
        turnIndex: Int,
        kind: ForkKind,
    ): ForkResult? {
        return ConversationRepo.forkAtTurn(
            sourceId = sourceId,
            turnIndex = turnIndex,
            kind = kind,
        )
    }

    override suspend fun branchGroup(conversationId: String): List<ConversationSummary> {
        return ConversationRepo.branchGroup(conversationId)
    }

    override suspend fun latestBranchId(rootId: String): String? {
        return ConversationRepo.latestBranchId(rootId)
    }

    override suspend fun branchGroupIds(conversationId: String): List<String> {
        return ConversationRepo.branchGroupIds(conversationId)
    }
}
