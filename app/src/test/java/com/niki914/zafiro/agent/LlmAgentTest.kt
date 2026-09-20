package com.niki914.zafiro.agent

import com.niki914.zafiro.api.TurnStart
import com.niki914.zafiro.api.model.Draft
import com.niki914.zafiro.api.model.DraftImage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M2 只测不触碰遗留引擎的部分：草稿镜像与 `stream()` 的同步拒绝。
 * 门禁的 `Started` 分支会真的收集 `LLMController.stream`，留给层 2 的折叠测试。
 */
class LlmAgentTest {

    @After
    fun tearDown() {
        LlmAgent.clearForTest()
    }

    @Test
    fun stream_rejectsEmptyDraft() {
        assertEquals(TurnStart.DraftEmpty, LlmAgent.stream())
    }

    @Test
    fun updateDraft_isPureTransformOnCurrentDraft() {
        LlmAgent.updateDraft { it.copy(text = "你") }
        LlmAgent.updateDraft { it.copy(text = it.text + "好") }
        assertEquals("你好", LlmAgent.draft.value.text)
    }

    @Test
    fun clearDraft_resetsTextAndImages() {
        LlmAgent.updateDraft {
            Draft(
                text = "带图",
                images = listOf(DraftImage.Ready(com.niki914.zafiro.api.model.Attachment("/tmp/a.jpg"))),
            )
        }
        LlmAgent.clearDraft()
        assertEquals(Draft(), LlmAgent.draft.value)
        assertTrue(LlmAgent.draft.value.images.isEmpty())
    }
}
