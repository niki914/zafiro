package com.niki914.zafiro.business.agent

import com.niki914.zafiro.api.TurnStart
import com.niki914.zafiro.api.model.Attachment
import com.niki914.zafiro.api.model.Draft
import com.niki914.zafiro.api.model.DraftImage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 只测不触碰遗留引擎的部分：草稿镜像与 `stream()` 的同步拒绝。
 * 门禁的 `Started` 分支会真的走 `ensureConversation`（要 store 与引擎），留给层 2。
 */
class AgentImplTest {

    @After
    fun tearDown() {
        AgentImpl.clearForTest()
    }

    @Test
    fun stream_rejectsEmptyDraft() {
        assertEquals(TurnStart.DraftEmpty, AgentImpl.stream())
    }

    @Test
    fun updateDraft_isPureTransformOnCurrentDraft() {
        AgentImpl.updateDraft { it.copy(text = "你") }
        AgentImpl.updateDraft { it.copy(text = it.text + "好") }
        assertEquals("你好", AgentImpl.draft.value.text)
    }

    @Test
    fun clearDraft_resetsTextAndImages() {
        AgentImpl.updateDraft {
            Draft(
                text = "带图",
                images = listOf(DraftImage.Ready(Attachment("/tmp/a.jpg"))),
            )
        }
        AgentImpl.clearDraft()
        assertEquals(Draft(), AgentImpl.draft.value)
        assertTrue(AgentImpl.draft.value.images.isEmpty())
    }
}
