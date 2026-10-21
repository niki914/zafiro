// 保护：TurnSectionMapper 正确将连续工具聚合成 ToolRun，并将 Text、Thinking、Error 映射为独立段落
package com.niki914.zafiro.app.ui.chat.mapper

import com.niki914.zafiro.app.ui.chat.model.ActionBodyUi
import com.niki914.zafiro.app.ui.chat.model.TurnSection
import com.niki914.zafiro.app.ui.model.home.HomeChatBlock
import com.niki914.zafiro.app.ui.model.home.HomeChatTurn
import com.niki914.zafiro.app.ui.model.home.HomeToolState
import com.niki914.zafiro.app.ui.model.home.HomeToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnSectionMapperTest {

    @Test
    fun consecutiveTools_aggregatedIntoSingleToolRun_andSeparatedByText() {
        val turn = HomeChatTurn(
            id = 1L,
            userText = "hello",
            blocks = listOf(
                HomeChatBlock.Thinking(id = 0, text = "Thinking stream...", isComplete = true),
                HomeChatBlock.Tool(HomeToolStatus(name = "tool1", state = HomeToolState.Succeeded)),
                HomeChatBlock.Tool(HomeToolStatus(name = "tool2", state = HomeToolState.Succeeded)),
                HomeChatBlock.Text(text = "Middle response"),
                HomeChatBlock.Tool(HomeToolStatus(name = "tool3", state = HomeToolState.Succeeded)),
            ),
        )

        val ui = turn.toTurnUiModel()

        // 预期结构：Thinking -> ToolRun(tool1, tool2) -> Text -> ToolRun(tool3)
        assertEquals(4, ui.sections.size)
        assertTrue(ui.sections[0] is TurnSection.Thinking)

        val firstToolRun = ui.sections[1] as TurnSection.ToolRun
        assertEquals(2, firstToolRun.actions.size)
        assertEquals("tool1", firstToolRun.actions[0].title)
        assertEquals("tool2", firstToolRun.actions[1].title)

        val textSection = ui.sections[2] as TurnSection.Text
        assertEquals("Middle response", textSection.text)

        val secondToolRun = ui.sections[3] as TurnSection.ToolRun
        assertEquals(1, secondToolRun.actions.size)
        assertEquals("tool3", secondToolRun.actions[0].title)
    }

    @Test
    fun expandedStates_correctlyDispatchedToToolRunAndThinking() {
        val turn = HomeChatTurn(
            id = 10L,
            userText = "query",
            blocks = listOf(
                HomeChatBlock.Thinking(id = 0, text = "Thinking...", isComplete = false),
                HomeChatBlock.Tool(HomeToolStatus(name = "shell", state = HomeToolState.Succeeded)),
                HomeChatBlock.Tool(HomeToolStatus(name = "python", state = HomeToolState.Succeeded)),
            ),
        )

        val userToggledKeys = mapOf(
            "10_thinking_0" to true,
            "10_tools_0" to true,
            "10_tool_2" to true, // 第二个工具在 blocks 索引为 2
        )

        val ui = turn.toTurnUiModel(
            userToggledKeys = userToggledKeys,
            activeThinkingKey = "10_0",
        )

        val thinking = ui.sections[0] as TurnSection.Thinking
        assertTrue("思考块展开状态正确传递", thinking.action.isExpanded)
        val thinkingBody = thinking.action.body as ActionBodyUi.Custom.Thinking
        assertTrue("活跃思考块 autoScrollToEnd 置位", thinkingBody.autoScrollToEnd)

        val toolRun = ui.sections[1] as TurnSection.ToolRun
        assertTrue("工具链展开状态正确传递", toolRun.isExpanded)
        assertTrue("子工具展开状态正确匹配", toolRun.actions[1].isExpanded)
    }

    @Test
    fun delayedAutoCollapse_staysExpandedUntilNextBlockOrTextArrives() {
        // 1. Thinking 完成但后序尚无新块或正文：应保持自动展开
        val turn1 = HomeChatTurn(
            id = 20L,
            userText = "q",
            blocks = listOf(
                HomeChatBlock.Thinking(id = 0, text = "Thinking...", isComplete = true),
            ),
        )
        val ui1 = turn1.toTurnUiModel()
        val thinkingSection1 = ui1.sections[0] as TurnSection.Thinking
        assertTrue("无后序块时完成的 Thinking 保持展开", thinkingSection1.action.isExpanded)

        // 2. 下一个块（Tool）出现：前一个 Thinking 自动收起，新 Tool 保持展开
        val turn2 = HomeChatTurn(
            id = 20L,
            userText = "q",
            blocks = listOf(
                HomeChatBlock.Thinking(id = 0, text = "Thinking...", isComplete = true),
                HomeChatBlock.Tool(HomeToolStatus(name = "shell", state = HomeToolState.Succeeded)),
            ),
        )
        val ui2 = turn2.toTurnUiModel()
        val thinkingSection2 = ui2.sections[0] as TurnSection.Thinking
        val toolRun2 = ui2.sections[1] as TurnSection.ToolRun
        assertFalse("后序工具块出现后，Thinking 自动收起", thinkingSection2.action.isExpanded)
        assertTrue("后序无新块时，已完成的 Tool 保持展开", toolRun2.actions[0].isExpanded)

        // 3. 正文（Text）出现：所有前序过程块全部自动收起
        val turn3 = HomeChatTurn(
            id = 20L,
            userText = "q",
            blocks = listOf(
                HomeChatBlock.Thinking(id = 0, text = "Thinking...", isComplete = true),
                HomeChatBlock.Tool(HomeToolStatus(name = "shell", state = HomeToolState.Succeeded)),
                HomeChatBlock.Text(text = "Final answer"),
            ),
        )
        val ui3 = turn3.toTurnUiModel()
        val thinkingSection3 = ui3.sections[0] as TurnSection.Thinking
        val toolRun3 = ui3.sections[1] as TurnSection.ToolRun
        assertFalse("正文出现后 Thinking 保持折叠", thinkingSection3.action.isExpanded)
        assertFalse("正文出现后 Tool 自动收起", toolRun3.actions[0].isExpanded)

        // 4. 用户手动干预最高优先级：用户主动展开的块，在正文出现后依然保持展开
        val ui4 = turn3.toTurnUiModel(
            userToggledKeys = mapOf("20_tool_1" to true),
        )
        val toolRun4 = ui4.sections[1] as TurnSection.ToolRun
        assertTrue("用户手动展开的 Tool 在正文出现后依然保持展开", toolRun4.actions[0].isExpanded)
    }
}
