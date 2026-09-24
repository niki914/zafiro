package com.niki914.zafiro.app.ui.model.home

import com.niki914.zafiro.api.model.AgentPhase
import com.niki914.zafiro.api.model.Attachment
import com.niki914.zafiro.api.model.Conversation
import com.niki914.zafiro.api.model.ConversationTurn
import com.niki914.zafiro.api.model.ToolInvocation
import com.niki914.zafiro.api.model.ToolOutcome
import com.niki914.zafiro.api.model.TurnBlock
import com.niki914.zafiro.api.model.TurnFailureCode
import com.niki914.zafiro.api.model.TurnId
import com.niki914.zafiro.app.ui.model.home.HomeChatBlock
import com.niki914.zafiro.app.ui.model.home.HomeToolState
import com.niki914.zafiro.chat.LlmErrorCode
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationUiMapperTest {

    @Test
    fun mapsImagesThinkingAndToolOutcomes() {
        val turns = Conversation(
            turns = listOf(
                ConversationTurn(
                    id = TurnId("t0"),
                    userText = "look",
                    attachments = listOf(Attachment("/user.png", "image/png")),
                    blocks = listOf(
                        TurnBlock.Thinking("t0:0", "reasoning", isComplete = true),
                        TurnBlock.Tool(
                            "t0:1",
                            ToolInvocation("running", "search", "Search", "{}"),
                        ),
                        TurnBlock.Tool(
                            "t0:2",
                            ToolInvocation("success", "view_image", "View image"),
                            ToolOutcome.Succeeded("done", listOf(Attachment("/result.png"))),
                        ),
                        TurnBlock.Tool(
                            "t0:3",
                            ToolInvocation("failed", "terminal", "Terminal"),
                            ToolOutcome.Failed("denied", "partial"),
                        ),
                    ),
                ),
            ),
        ).toHomeTurns()

        val turn = turns.single()
        assertEquals(0L, turn.id)
        assertEquals("/user.png", turn.images.single().path)
        assertEquals(
            HomeChatBlock.Thinking(0, "reasoning", isComplete = true),
            turn.blocks[0],
        )
        assertEquals(HomeToolState.Running, (turn.blocks[1] as HomeChatBlock.Tool).status.state)
        val success = (turn.blocks[2] as HomeChatBlock.Tool).status
        assertEquals(HomeToolState.Succeeded, success.state)
        assertEquals("done", success.resultText)
        assertEquals("/result.png", success.images.single().path)
        val failed = (turn.blocks[3] as HomeChatBlock.Tool).status
        assertEquals(HomeToolState.Failed, failed.state)
        assertEquals("denied", failed.failedReason)
        assertEquals("partial", failed.resultText)
    }

    @Test
    fun mapsFailureCodeAndStatusPhase() {
        val turn = Conversation(
            turns = listOf(
                ConversationTurn(
                    id = TurnId("t0"),
                    userText = "q",
                    blocks = listOf(TurnBlock.Failure("t0:0", "network", TurnFailureCode.Transport)),
                ),
            ),
        ).toHomeTurns().single()

        assertEquals(
            HomeChatBlock.Error("network", LlmErrorCode.Transport),
            turn.blocks.single(),
        )
        assertEquals(true, AgentPhase.ToolRunning.isGenerating())
        assertEquals(false, AgentPhase.Idle.isGenerating())
    }
}
