// 保护：beforeSerialization 钩子注入环境上下文时的时态分流、摘要检查点跳过、长会话裁剪后时间戳对齐与未启用短路契约
package com.niki914.zafiro.envcontext

import com.niki914.okia.conversation.Conversation
import com.niki914.okia.conversation.MessageEntry
import com.niki914.okia.hooks.SerializationHolder
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.okia.protocol.RequestSnapshot
import com.niki914.okia.transport.HttpTimeouts
import com.niki914.zafiro.api.screen.ScreenId
import com.niki914.zafiro.api.screen.VirtualDisplayContext
import com.niki914.zafiro.api.text.SummaryBlock
import com.niki914.zafiro.settings.model.RuntimeEnvContextConfig
import com.niki914.zafiro.settings.model.RuntimeLlmConfig
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvContextInjectionHookTest {

    private fun stubSnapshot() = RequestSnapshot(
        endpoint = "https://example.com/v1",
        apiKey = "test-key",
        model = "test-model",
        systemPrompt = "system prompt",
        temperature = 0.5f,
        maxTokens = 2048,
        headers = emptyMap(),
        timeouts = HttpTimeouts(connectMs = 1000L, readMs = 1000L, writeMs = 1000L),
        tools = emptyList(),
    )

    @Test
    fun beforeSerialization_preservesPrefixCacheForHistoricalTurns() = runTest {
        val shanghaiZone = ZoneId.of("Asia/Shanghai")
        val turn1Timestamp = ZonedDateTime.of(2026, 11, 15, 14, 0, 0, 0, shanghaiZone)
            .toInstant()
            .toEpochMilli()
        val turn2Timestamp = ZonedDateTime.of(2026, 11, 15, 15, 30, 0, 0, shanghaiZone)
            .toInstant()
            .toEpochMilli()

        val config = RuntimeEnvContextConfig(
            enabled = true,
            keys = setOf(RuntimeEnvContextConfig.KEY_TIME, RuntimeEnvContextConfig.KEY_DEVICE),
            variables = emptyMap(),
        )

        val committedTurn1User = Message.User(listOf(ContentBlock.Text("Turn 1 Question")))
        val committedConversation = Conversation(
            id = "conv-1",
            leafId = "entry-1",
            history = listOf(
                MessageEntry(id = "entry-1", timestamp = turn1Timestamp, message = committedTurn1User),
            ),
        )

        val turn2User = Message.User(listOf(ContentBlock.Text("Turn 2 Question")))
        val hook = EnvContextInjectionHook(
            getLlmConfig = { RuntimeLlmConfig(envContext = config) },
            deviceStateProvider = {
                DeviceStateSnapshot(model = "OnePlus 12", battery = "Battery: 80%", networkType = "Wi-Fi")
            },
            getConversation = { committedConversation },
            clock = { turn2Timestamp },
            zoneId = { shanghaiZone },
        )

        val holder = SerializationHolder(
            stubSnapshot(),
            listOf(committedTurn1User, turn2User),
        )

        hook.beforeSerialization(holder)

        assertEquals("env_context", holder.lastWriter)
        val resultHistory = holder.history
        assertEquals(2, resultHistory.size)

        val historicalUser = resultHistory[0] as Message.User
        val historicalText = (historicalUser.content[0] as ContentBlock.Text).text
        val activeUser = resultHistory[1] as Message.User
        val activeText = (activeUser.content[0] as ContentBlock.Text).text

        // Turn 1 uses historical timestamp (14:00) and static device model (no dynamic battery/network to preserve cache)
        assertTrue(historicalText.contains("Turn 1 Question"))
        assertTrue(historicalText.contains("14:00"))
        assertTrue(historicalText.contains("OnePlus 12"))
        assertTrue(!historicalText.contains("80%"))

        // Turn 2 uses fresh active timestamp (15:30) and full dynamic device state
        assertTrue(activeText.contains("Turn 2 Question"))
        assertTrue(activeText.contains("15:30"))
        assertTrue(activeText.contains("OnePlus 12 · Battery: 80% · Wi-Fi"))
    }

    @Test
    fun beforeSerialization_skipsSummaryBlockAndAlignsPrunedHistory() = runTest {
        val shanghaiZone = ZoneId.of("Asia/Shanghai")
        val turn1Timestamp = ZonedDateTime.of(2026, 11, 15, 10, 0, 0, 0, shanghaiZone)
            .toInstant()
            .toEpochMilli()
        val turn2Timestamp = ZonedDateTime.of(2026, 11, 15, 12, 0, 0, 0, shanghaiZone)
            .toInstant()
            .toEpochMilli()
        val turn3Timestamp = ZonedDateTime.of(2026, 11, 15, 16, 0, 0, 0, shanghaiZone)
            .toInstant()
            .toEpochMilli()

        val config = RuntimeEnvContextConfig(
            enabled = true,
            keys = setOf(RuntimeEnvContextConfig.KEY_TIME),
            variables = emptyMap(),
        )

        val turn1Msg = Message.User(listOf(ContentBlock.Text("Turn 1 Old Question")))
        val turn2Msg = Message.User(listOf(ContentBlock.Text("Turn 2 Kept Question")))
        val turn3Msg = Message.User(listOf(ContentBlock.Text("Turn 3 Active Question")))

        val summaryMsg = Message.User(
            listOf(ContentBlock.Text(SummaryBlock.block(SummaryBlock.Payload(keepTurns = 1, summary = "Summary of turn 1"))))
        )

        // 完整会话包含 Turn 1 ~ Turn 3
        val committedConversation = Conversation(
            id = "conv-pruned",
            leafId = "entry-3",
            history = listOf(
                MessageEntry(id = "e1", timestamp = turn1Timestamp, message = turn1Msg),
                MessageEntry(id = "e2", timestamp = turn2Timestamp, message = turn2Msg),
                MessageEntry(id = "e3", timestamp = turn3Timestamp, message = turn3Msg),
            ),
        )

        // 请求历史中：Turn 1 已被裁剪并由 Summary 检查点替代
        val holder = SerializationHolder(
            stubSnapshot(),
            listOf(summaryMsg, turn2Msg, turn3Msg),
        )

        val hook = EnvContextInjectionHook(
            getLlmConfig = { RuntimeLlmConfig(envContext = config) },
            deviceStateProvider = { DeviceStateSnapshot(model = "Pixel 8") },
            getConversation = { committedConversation },
            clock = { turn3Timestamp },
            zoneId = { shanghaiZone },
        )

        hook.beforeSerialization(holder)

        val resultSummary = holder.history[0] as Message.User
        val resultSummaryText = (resultSummary.content[0] as ContentBlock.Text).text
        val resultTurn2 = holder.history[1] as Message.User
        val resultTurn2Text = (resultTurn2.content[0] as ContentBlock.Text).text

        // 1. Summary 块不应注入 <zfr-context>
        assertFalse(resultSummaryText.contains("<zfr-context>"))
        // 2. 被保留的 Turn 2 应当对齐到其真实时间戳 (12:00)，而不是被裁剪掉的 Turn 1 时间戳 (10:00)
        assertTrue(resultTurn2Text.contains("12:00"))
        assertFalse(resultTurn2Text.contains("10:00"))
    }

    @Test
    fun beforeSerialization_appendsContextToPureImageMessage() = runTest {
        val config = RuntimeEnvContextConfig(
            enabled = true,
            keys = setOf(RuntimeEnvContextConfig.KEY_TIME),
            variables = emptyMap(),
        )

        val pureImageUser = Message.User(listOf(ContentBlock.Image(path = "/test.png", mimeType = "image/png")))
        val holder = SerializationHolder(stubSnapshot(), listOf(pureImageUser))

        val hook = EnvContextInjectionHook(
            getLlmConfig = { RuntimeLlmConfig(envContext = config) },
            deviceStateProvider = { DeviceStateSnapshot(model = "Pixel 8") },
            clock = { 1700000000000L },
        )

        hook.beforeSerialization(holder)

        val updatedUser = holder.history[0] as Message.User
        assertEquals(2, updatedUser.content.size)
        assertTrue(updatedUser.content[0] is ContentBlock.Image)
        val textBlock = updatedUser.content[1] as ContentBlock.Text
        assertTrue(textBlock.text.contains("<zfr-context>"))
    }

    @Test
    fun beforeSerialization_noopWhenDisabled() = runTest {
        val hook = EnvContextInjectionHook(
            getLlmConfig = {
                RuntimeLlmConfig(envContext = RuntimeEnvContextConfig(enabled = false))
            },
        )

        val initialHistory = listOf(
            Message.User(listOf(ContentBlock.Text("Hello"))),
        )
        val holder = SerializationHolder(stubSnapshot(), initialHistory)

        hook.beforeSerialization(holder)

        assertNull(holder.lastWriter)
        assertEquals(initialHistory, holder.history)
    }

    @Test
    fun beforeSerialization_injectsVirtualDisplayOnlyInLatestTurnAndHandlesNoApp() = runTest {
        val shanghaiZone = ZoneId.of("Asia/Shanghai")
        val turn1Timestamp = ZonedDateTime.of(2026, 11, 15, 14, 0, 0, 0, shanghaiZone)
            .toInstant()
            .toEpochMilli()
        val turn2Timestamp = ZonedDateTime.of(2026, 11, 15, 15, 30, 0, 0, shanghaiZone)
            .toInstant()
            .toEpochMilli()

        val config = RuntimeEnvContextConfig(
            enabled = true,
            keys = setOf(RuntimeEnvContextConfig.KEY_VIRTUAL_DISPLAY),
            variables = emptyMap(),
        )

        val turn1User = Message.User(listOf(ContentBlock.Text("Turn 1")))
        val committedConversation = Conversation(
            id = "conv-vd",
            leafId = "entry-1",
            history = listOf(
                MessageEntry(id = "entry-1", timestamp = turn1Timestamp, message = turn1User),
            ),
        )
        val turn2User = Message.User(listOf(ContentBlock.Text("Turn 2")))

        val hook = EnvContextInjectionHook(
            getLlmConfig = { RuntimeLlmConfig(envContext = config) },
            getConversation = { committedConversation },
            virtualDisplayProvider = {
                VirtualDisplayContext(screenId = ScreenId("vd-2"))
            },
            clock = { turn2Timestamp },
            zoneId = { shanghaiZone },
        )

        val holder = SerializationHolder(stubSnapshot(), listOf(turn1User, turn2User))
        hook.beforeSerialization(holder)

        val historicalText = ((holder.history[0] as Message.User).content[0] as ContentBlock.Text).text
        val activeText = ((holder.history[1] as Message.User).content[0] as ContentBlock.Text).text

        assertFalse(historicalText.contains("Virtual Display:"))
        assertTrue(activeText.contains("Virtual Display: vd-2, No App Launched"))
        assertFalse(activeText.contains("Unknown Keys"))
    }
}
