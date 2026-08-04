package com.niki914.nexus.agentic.app.conversation

import com.niki914.kai.ChatTurn
import com.niki914.kai.ToolCallSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatTurnJsonCodecTest {
    @Test
    fun user_roundTripsContent() {
        val turn = ChatTurn.User("hello")

        val encoded = ChatTurnJsonCodec.encode(turn)

        assertEquals(StoredChatTurnKind.User, encoded?.kind)
        assertEquals(turn, ChatTurnJsonCodec.decode(encoded!!.kind.name, encoded.payloadJson))
    }

    @Test
    fun assistant_roundTripsToolCallsAndReasoning() {
        val turn = ChatTurn.Assistant(
            content = "answer",
            toolCalls = listOf(
                ToolCallSpec(
                    callId = "call-1",
                    toolName = "search",
                    argumentsJson = """{"query":"nexus"}""",
                ),
            ),
            reasoningContent = "thinking",
            reasoningSignature = "sig-1",
        )

        val encoded = ChatTurnJsonCodec.encode(turn)

        assertEquals(StoredChatTurnKind.Assistant, encoded?.kind)
        assertEquals(turn, ChatTurnJsonCodec.decode(encoded!!.kind.name, encoded.payloadJson))
    }

    @Test
    fun toolResult_roundTripsPayload() {
        val turn = ChatTurn.ToolResult(
            callId = "call-1",
            toolName = "search",
            resultJson = """{"items":[1]}""",
        )

        val encoded = ChatTurnJsonCodec.encode(turn)

        assertEquals(StoredChatTurnKind.ToolResult, encoded?.kind)
        assertEquals(turn, ChatTurnJsonCodec.decode(encoded!!.kind.name, encoded.payloadJson))
    }

    @Test
    fun system_encodeReturnsNull() {
        assertNull(ChatTurnJsonCodec.encode(ChatTurn.System("system prompt")))
    }

    @Test
    fun decode_invalidKindOrJsonReturnsNull() {
        assertNull(ChatTurnJsonCodec.decode("Missing", """{"content":"hello"}"""))
        assertNull(ChatTurnJsonCodec.decode(StoredChatTurnKind.User.name, "{broken"))
    }
}
