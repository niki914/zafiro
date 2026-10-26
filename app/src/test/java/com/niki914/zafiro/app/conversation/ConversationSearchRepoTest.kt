// 保护：历史会话综合全文搜索——空白/未命中、标题命中、正文 Snippet 提取及 JSON 假阳性排除。
package com.niki914.zafiro.app.conversation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.niki914.okia.conversation.ConversationEntry
import com.niki914.okia.message.AssistantMessage
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.okia.message.ToolCallOutcome
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val DB_NAME = "test-conversation.db"

@RunWith(RobolectricTestRunner::class)
class ConversationSearchRepoTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DB_NAME)
        ConversationRepo.init(context)
    }

    @After
    fun tearDown() = runTest {
        ConversationRepo.closeForTest()
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun searchConversations_returnsEmptyOnBlankOrMismatch() = runTest {
        ConversationRepo.createConversation("s1", "Kotlin Coroutines")

        assertTrue(ConversationSearchRepo.searchConversations("").isEmpty())
        assertTrue(ConversationSearchRepo.searchConversations("   ").isEmpty())
        assertTrue(ConversationSearchRepo.searchConversations("Python").isEmpty())
    }

    @Test
    fun searchConversations_matchesTitle() = runTest {
        ConversationRepo.createConversation("s1", "First prompt message", title = "Kotlin Coroutines")

        val results = ConversationSearchRepo.searchConversations("Coroutines")

        assertEquals(1, results.size)
        assertTrue(results[0].matchedInTitle)
        assertFalse(results[0].matchedInBody)
    }

    @Test
    fun searchConversations_matchesMessageBodyAndExtractsSnippet() = runTest {
        val id = ConversationRepo.createConversation("s1", "General Chat")
        val longText = "这是一段很长很长的前置文本用来测试滑动窗口提取算法是否能准确将关键词聚焦居中并省略前后的超长内容"
        val entries = linearEntries(
            Message.User(listOf(ContentBlock.Text("hello"))),
            Message.Assistant(AssistantMessage(listOf(ContentBlock.Text(longText)))),
        )
        ConversationRepo.insertEntries(id, entries)

        val results = ConversationSearchRepo.searchConversations("滑动窗口")

        assertEquals(1, results.size)
        assertTrue(results[0].matchedInBody)
        assertTrue(results[0].matchedSnippet!!.contains("滑动窗口"))
    }

    @Test
    fun searchConversations_ignoresJsonKeysInSerialization() = runTest {
        val id = ConversationRepo.createConversation("s1", "General Chat")
        val entries = linearEntries(
            Message.ToolResult("uniqueToolCallId123", "search", ToolCallOutcome.Success("clean output")),
        )
        ConversationRepo.insertEntries(id, entries)

        val results = ConversationSearchRepo.searchConversations("uniqueToolCallId123")

        assertTrue(results.isEmpty())
    }

    private fun linearEntries(vararg messages: Message): List<ConversationEntry> {
        var parent: String? = null
        return messages.mapIndexed { index, message ->
            val entry = ConversationEntry(
                id = "entry-$index",
                parentId = parent,
                timestamp = 1000L + index,
                message = message,
            )
            parent = entry.id
            entry
        }
    }
}
