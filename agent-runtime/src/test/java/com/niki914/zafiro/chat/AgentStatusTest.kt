package com.niki914.zafiro.chat

import com.niki914.okia.conversation.Conversation
import com.niki914.okia.conversation.MessageEntry
import com.niki914.okia.message.AssistantMessage
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.zafiro.chat.util.SilentLoggerRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class AgentStatusTest {

    /** JVM 单测无 Android 框架：不止住默认 backend，发布状态时的日志会抛 "android.util.Log not mocked"。 */
    @get:Rule
    val silentLogger = SilentLoggerRule()

    /** 假会话树：用例按回合追加消息。 */
    private val history = mutableListOf<MessageEntry>()
    private var live: AssistantMessage? = null

    @Before
    fun setUp() {
        history.clear()
        live = null
        AgentStatusHolder.resetForTest()
        AgentStatusHolder.conversationSourceForTest = {
            Conversation(id = "conv", leafId = null, history = history.toList(), live = live)
        }
    }

    @After
    fun tearDown() {
        AgentStatusHolder.resetForTest()
    }

    // ── 正文取值 ────────────────────────────────────────────────────────

    @Test
    fun `进行中先显示提问 出第一句后显示第一句`() {
        givenPreviousTurn()
        AgentStatusHolder.onRoundStarted("本回合提问")
        assertEquals("本回合提问", preview())

        commitUser("本回合提问")
        commitAssistant("第一句")
        AgentStatusHolder.onEvent(textDelta())
        assertEquals("第一句", preview())

        // 首句已定：后续文本不改变进行中的正文
        commitAssistant("第二句")
        AgentStatusHolder.onEvent(textDelta())
        assertEquals("第一句", preview())
    }

    @Test
    fun `完成显示最后一句`() {
        givenPreviousTurn()
        AgentStatusHolder.onRoundStarted("本回合提问")
        commitUser("本回合提问")
        commitAssistant("第一句")
        commitAssistant("最后一句")

        AgentStatusHolder.onEvent(LlmStreamEvent.Completed)
        assertEquals(TurnOutcome.Completed, AgentStatusHolder.status.value.outcome)
        assertEquals("最后一句", preview())
    }

    @Test
    fun `失败与待命为空`() {
        givenPreviousTurn()
        AgentStatusHolder.onRoundStarted("本回合提问")
        commitUser("本回合提问")
        commitAssistant("说了一半")
        AgentStatusHolder.onEvent(LlmStreamEvent.Error(message = null))
        assertNull(preview())
        assertEquals("Zafiro · 失败", AgentStatusHolder.status.value.logLine)

        AgentStatusHolder.onConversationChanged()
        assertNull(preview())
        assertEquals("Zafiro · 待命", AgentStatusHolder.status.value.logLine)
    }

    @Test
    fun `打断为空`() {
        givenPreviousTurn()
        AgentStatusHolder.onRoundStarted("本回合提问")
        commitUser("本回合提问")
        commitAssistant("说了一半")
        AgentStatusHolder.onPhase(AgentPhase.Idle)
        assertEquals(TurnOutcome.Interrupted, AgentStatusHolder.status.value.outcome)
        assertNull(preview())
    }

    @Test
    fun `本回合消息未提交时不读上一轮的 agent 文本`() {
        givenPreviousTurn()
        AgentStatusHolder.onRoundStarted("本回合提问")
        // 事件推动重算，但本回合的用户消息还没进树
        AgentStatusHolder.onEvent(textDelta())
        assertEquals("本回合提问", preview())
    }

    @Test
    fun `助手消息未进树时取流式消息`() {
        givenPreviousTurn()
        AgentStatusHolder.onRoundStarted("本回合提问")
        commitUser("本回合提问")
        live = AssistantMessage(listOf(ContentBlock.Text("正在说")))
        AgentStatusHolder.onEvent(textDelta())
        assertEquals("正在说", preview())
    }

    @Test
    fun `思考块不进正文`() {
        givenPreviousTurn()
        AgentStatusHolder.onRoundStarted("本回合提问")
        commitUser("本回合提问")
        commit(Message.Assistant(AssistantMessage(listOf(ContentBlock.Thinking("先想一想")))))
        commitAssistant("真正的回答")
        AgentStatusHolder.onEvent(textDelta())
        assertEquals("真正的回答", preview())
    }

    @Test
    fun `正文压成单行并截断`() {
        givenPreviousTurn()
        AgentStatusHolder.onRoundStarted("A\nB\t" + "字".repeat(300))
        val preview = preview()!!
        assertEquals(120, preview.length)
        assertEquals("A B " + "字".repeat(116), preview)
    }

    @Test
    fun `截断不切开代理对`() {
        givenPreviousTurn()
        // 截断点恰好落在代理对中间：整对丢弃，不产生孤立代理
        AgentStatusHolder.onRoundStarted("字".repeat(119) + "😀" + "尾")
        assertEquals("字".repeat(119), preview())

        // 代理对完整落在上限内：保留
        AgentStatusHolder.onRoundStarted("字".repeat(118) + "😀" + "尾")
        val preview = preview()!!
        assertEquals(120, preview.length)
        assertEquals("字".repeat(118) + "😀", preview)
    }

    // ── 标题与日志 ──────────────────────────────────────────────────────

    @Test
    fun `日志行即通知的标题加正文`() {
        givenPreviousTurn()
        AgentStatusHolder.onRoundStarted("帮我看一下电池")
        assertEquals(
            "Zafiro · 生成中 | 帮我看一下电池",
            AgentStatusHolder.status.value.logLine,
        )
    }

    @Test
    fun `待命只有标题没有正文`() {
        val idle = AgentStatus()
        assertEquals("Zafiro · 待命", idle.title)
        assertEquals("Zafiro · 待命", idle.logLine)
        assertNull(idle.preview)
    }

    @Test
    fun `空提问没有正文`() {
        givenPreviousTurn()
        AgentStatusHolder.onRoundStarted("   \n ")
        assertNull(preview())
        assertEquals("Zafiro · 生成中", AgentStatusHolder.status.value.logLine)
    }

    @Test
    fun `各阶段的标题`() {
        givenPreviousTurn()
        AgentStatusHolder.onRoundStarted("问")
        assertEquals("Zafiro · 生成中", AgentStatusHolder.status.value.title)

        AgentStatusHolder.onEvent(LlmStreamEvent.ToolRunning(ToolCallStatus(name = "terminal")))
        assertEquals("Zafiro · 执行工具", AgentStatusHolder.status.value.title)

        AgentStatusHolder.onPermissionPending("p1")
        assertEquals("Zafiro · 等待授权", AgentStatusHolder.status.value.title)

        AgentStatusHolder.onPermissionPending(null)
        assertEquals("Zafiro · 执行工具", AgentStatusHolder.status.value.title)

        AgentStatusHolder.onEvent(LlmStreamEvent.Completed)
        assertEquals("Zafiro · 已完成", AgentStatusHolder.status.value.title)
    }

    // ── 阶段机 ──────────────────────────────────────────────────────────

    @Test
    fun `工具运行进入工具阶段 结算不回落 回合结束回到空闲`() {
        givenPreviousTurn()
        AgentStatusHolder.onRoundStarted("跑个命令")
        assertEquals(AgentPhase.Generating, AgentStatusHolder.status.value.phase)

        AgentStatusHolder.onEvent(LlmStreamEvent.ToolRunning(ToolCallStatus(name = "terminal")))
        assertEquals(AgentPhase.ToolRunning, AgentStatusHolder.status.value.phase)

        // 结算不回落：并行工具以最后开始的阶段为准
        AgentStatusHolder.onEvent(LlmStreamEvent.ToolSucceeded(ToolCallStatus(name = "terminal")))
        assertEquals(AgentPhase.ToolRunning, AgentStatusHolder.status.value.phase)

        // 下一段文本回到生成
        AgentStatusHolder.onEvent(textDelta())
        assertEquals(AgentPhase.Generating, AgentStatusHolder.status.value.phase)

        AgentStatusHolder.onPhase(AgentPhase.Idle)
        assertEquals(AgentPhase.Idle, AgentStatusHolder.status.value.phase)
    }

    @Test
    fun `授权等待优先于生成与工具阶段`() {
        givenPreviousTurn()
        AgentStatusHolder.onRoundStarted("跑个命令")
        AgentStatusHolder.onEvent(LlmStreamEvent.ToolRunning(ToolCallStatus(name = "terminal")))
        AgentStatusHolder.onPermissionPending("p1")
        assertEquals(AgentPhase.WaitingPermission, AgentStatusHolder.status.value.phase)

        // 等待期间到达的执行事件不覆盖等待阶段
        AgentStatusHolder.onEvent(LlmStreamEvent.RoundStarted)
        assertEquals(AgentPhase.WaitingPermission, AgentStatusHolder.status.value.phase)

        // 等待结束后回到等待期间最后到达的执行阶段
        AgentStatusHolder.onPermissionPending(null)
        assertEquals(AgentPhase.Generating, AgentStatusHolder.status.value.phase)
    }

    @Test
    fun `授权请求随回合结束清除`() {
        givenPreviousTurn()
        AgentStatusHolder.onRoundStarted("跑个命令")
        AgentStatusHolder.onPermissionPending("p1")
        AgentStatusHolder.onEvent(LlmStreamEvent.Completed)
        assertNull(AgentStatusHolder.status.value.permissionId)
    }

    @Test
    fun `回合尾不覆盖已完成或失败`() {
        givenPreviousTurn()
        AgentStatusHolder.onRoundStarted("问")
        AgentStatusHolder.onEvent(LlmStreamEvent.Completed)
        AgentStatusHolder.onPhase(AgentPhase.Idle)
        assertEquals(TurnOutcome.Completed, AgentStatusHolder.status.value.outcome)
    }

    @Test
    fun `空闲时重复收尾不产生已停止`() {
        AgentStatusHolder.onPhase(AgentPhase.Idle)
        assertEquals(AgentStatus(), AgentStatusHolder.status.value)
    }

    @Test
    fun `上一轮未收尾就开新一轮时正文更新`() {
        givenPreviousTurn()
        AgentStatusHolder.onRoundStarted("第一问")
        // 阶段不变（仍是 Generating），但提问已换：不能因阶段相同而丢弃
        AgentStatusHolder.onRoundStarted("第二问")
        assertEquals("第二问", preview())
    }

    @Test
    fun `载入或新建会话回到待命`() {
        givenPreviousTurn()
        AgentStatusHolder.onRoundStarted("上一轮的提问")
        commitUser("上一轮的提问")
        commitAssistant("上一轮的回答")
        AgentStatusHolder.onEvent(LlmStreamEvent.Completed)

        AgentStatusHolder.onConversationChanged()
        assertEquals(AgentStatus(), AgentStatusHolder.status.value)
    }

    // ── 夹具 ────────────────────────────────────────────────────────────

    private fun preview(): String? = AgentStatusHolder.status.value.preview

    private fun textDelta() = LlmStreamEvent.TextDelta(delta = "a", fullText = "a")

    private fun givenPreviousTurn() {
        commitUser("上一轮提问")
        commitAssistant("上一轮回答")
    }

    private fun commitUser(text: String) =
        commit(Message.User(listOf(ContentBlock.Text(text))))

    private fun commitAssistant(text: String) =
        commit(Message.Assistant(AssistantMessage(listOf(ContentBlock.Text(text)))))

    private fun commit(message: Message) {
        history += MessageEntry(
            id = "m${history.size}",
            timestamp = history.size.toLong(),
            message = message,
        )
    }
}
