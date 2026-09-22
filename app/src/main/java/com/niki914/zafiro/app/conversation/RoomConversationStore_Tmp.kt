package com.niki914.zafiro.app.conversation

import com.niki914.zafiro.api.model.ConversationId
import com.niki914.zafiro.business.agent.ConversationStore_Tmp
import com.niki914.zafiro.business.agent.StoredConversation_Tmp
import com.niki914.zafiro.repo.XRepo

// 存废：阶段 3 替换（临时存储实现：Room 只在 app 侧，故端口在这里落地；M7 定案后换正式实现）

/**
 * [ConversationStore_Tmp] 的 app 侧实现：Room 读 + 最近打开标记 + 快照装配。
 *
 * 装配用 `ConversationFormatter.toConversation`（与流式归约同一条 id 规则）。
 */
class RoomConversationStore_Tmp : ConversationStore_Tmp {

    override suspend fun load(id: ConversationId): StoredConversation_Tmp? {
        val record = ConversationRepo.getConversation(id.value) ?: return null
        return StoredConversation_Tmp(
            snapshot = record.snapshot,
            conversation = ConversationFormatter.toConversation(record.snapshot),
            draftText = record.draftText,
        )
    }

    override suspend fun exists(id: ConversationId): Boolean = ConversationRepo.exists(id.value)

    override suspend fun create(id: ConversationId, firstUserInput: String) {
        ConversationRepo.createConversation(id = id.value, firstUserInput = firstUserInput)
    }

    override suspend fun saveDraft(id: ConversationId, draftText: String) {
        ConversationRepo.updateDraft(conversationId = id.value, draftText = draftText)
    }

    override suspend fun setLastOpened(id: ConversationId?) {
        XRepo.setLastOpenedConversationId(id?.value.orEmpty())
    }
}
