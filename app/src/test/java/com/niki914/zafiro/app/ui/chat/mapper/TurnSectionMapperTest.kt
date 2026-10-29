// 保护：TurnSectionMapper 正确将多步骤聚合成 WrappedSteps，实现单步穿透与流式平铺
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
    fun multiSteps_wrapped_and_singleStep_penetrated_whenTextArrives() {
        val turn = HomeChatTurn(
            id = 1L,
            userText = "hello",
            blocks = listOf(
                HomeChatBlock.Thinking(id = 0, text = "Thinking stream...", isComplete = true),
                HomeChatBlock.Tool(HomeToolStatus(name = "tool1", state = HomeToolState.Succeeded)),
                HomeChatBlock.Text(text = "Middle response"),
                HomeChatBlock.Tool(HomeToolStatus(name = "tool2", state = HomeToolState.Succeeded)),
                HomeChatBlock.Text(text = "Final response"),
            ),
        )

        val ui = turn.toTurnUiModel()

        // 预期结构：WrappedSteps(Thinking + tool1) -> Text -> Step(tool2 穿透) -> Text
        assertEquals(4, ui.sections.size)

        val firstWrapped = ui.sections[0] as TurnSection.WrappedSteps
        assertEquals(2, firstWrapped.steps.size)
        assertTrue(firstWrapped.steps[0].isThinking)
        assertEquals("tool1", firstWrapped.steps[1].title)
        assertFalse("正文到达后收纳块默认折叠", firstWrapped.isExpanded)

        val text1 = ui.sections[1] as TurnSection.Text
        assertEquals("Middle response", text1.text)

        val singleStep = ui.sections[2] as TurnSection.Step
        assertEquals("tool2", singleStep.action.title)
        assertFalse("正文到达后单步卡片默认收起", singleStep.action.isExpanded)

        val text2 = ui.sections[3] as TurnSection.Text
        assertEquals("Final response", text2.text)
    }

    @Test
    fun streamingSteps_remainFlat_beforeTextArrives() {
        val turn = HomeChatTurn(
            id = 2L,
            userText = "run",
            blocks = listOf(
                HomeChatBlock.Thinking(id = 0, text = "Thinking...", isComplete = true),
                HomeChatBlock.Tool(HomeToolStatus(name = "shell", state = HomeToolState.Running)),
            ),
        )

        val ui = turn.toTurnUiModel(isGenerating = true)

        // 流式进行中且无后序正文：保持平铺，不提前打包
        assertEquals(2, ui.sections.size)
        assertTrue(ui.sections[0] is TurnSection.Step)
        assertTrue(ui.sections[1] is TurnSection.Step)

        val runningStep = (ui.sections[1] as TurnSection.Step).action
        assertEquals("shell", runningStep.title)
        assertTrue("运行中的工具标记为 isRunning", runningStep.isRunning)
        assertTrue("流式最新的运行块保持展开", runningStep.isExpanded)
    }

    @Test
    fun expandedStates_correctlyDispatchedToWrappedStepsAndInnerSteps() {
        val turn = HomeChatTurn(
            id = 10L,
            userText = "query",
            blocks = listOf(
                HomeChatBlock.Thinking(id = 0, text = "Thinking...", isComplete = true),
                HomeChatBlock.Tool(HomeToolStatus(name = "shell", state = HomeToolState.Succeeded)),
                HomeChatBlock.Tool(HomeToolStatus(name = "python", state = HomeToolState.Succeeded)),
                HomeChatBlock.Text(text = "All completed"),
            ),
        )

        val userToggledKeys = mapOf(
            "10_wrapped_0" to true,
            "10_tool_2" to true, // 第二个工具在 blocks 索引为 2
        )

        val ui = turn.toTurnUiModel(
            userToggledKeys = userToggledKeys,
            activeThinkingKey = "10_0",
        )

        val wrapped = ui.sections[0] as TurnSection.WrappedSteps
        assertTrue("收纳块外层展开状态正确传递", wrapped.isExpanded)
        assertEquals(3, wrapped.steps.size)
        assertFalse("内层未点击的思考块默认收起", wrapped.steps[0].isExpanded)
        assertFalse("内层未点击的工具块默认收起", wrapped.steps[1].isExpanded)
        assertTrue("内层用户手动展开的工具块保持展开", wrapped.steps[2].isExpanded)
    }

    @Test
    fun wrappedSubSteps_toggleCallbacks_sendInvertedSubExpandedState() {
        val turn = HomeChatTurn(
            id = 15L,
            userText = "test",
            blocks = listOf(
                HomeChatBlock.Tool(HomeToolStatus(name = "tool1", state = HomeToolState.Succeeded)),
                HomeChatBlock.Tool(HomeToolStatus(name = "tool2", state = HomeToolState.Succeeded)),
                HomeChatBlock.Text(text = "done"),
            ),
        )

        val toggleEvents = mutableListOf<Pair<String, Boolean>>()
        val ui = turn.toTurnUiModel(
            onToggleAction = { key, nextExpanded -> toggleEvents.add(key to nextExpanded) },
        )

        val wrapped = ui.sections[0] as TurnSection.WrappedSteps
        wrapped.steps[1].onToggle()

        assertEquals(listOf("15_tool_1" to true), toggleEvents)
    }

    @Test
    fun delayedAutoCollapse_staysExpandedUntilNextBlockOrTextArrives() {
        // 1. Thinking 完成但后序尚无新块或正文且在生成中：应保持自动展开
        val turn1 = HomeChatTurn(
            id = 20L,
            userText = "q",
            blocks = listOf(
                HomeChatBlock.Thinking(id = 0, text = "Thinking...", isComplete = true),
            ),
        )
        val ui1 = turn1.toTurnUiModel(isGenerating = true)
        val step1 = ui1.sections[0] as TurnSection.Step
        assertTrue("无后序块且生成中，完成的 Thinking 保持展开", step1.action.isExpanded)

        // 2. 下一个块（Tool）出现：前一个 Thinking 自动收起，新 Tool 保持展开
        val turn2 = HomeChatTurn(
            id = 20L,
            userText = "q",
            blocks = listOf(
                HomeChatBlock.Thinking(id = 0, text = "Thinking...", isComplete = true),
                HomeChatBlock.Tool(HomeToolStatus(name = "shell", state = HomeToolState.Succeeded)),
            ),
        )
        val ui2 = turn2.toTurnUiModel(isGenerating = true)
        val stepThinking2 = ui2.sections[0] as TurnSection.Step
        val stepTool2 = ui2.sections[1] as TurnSection.Step
        assertFalse("后序工具块出现后，Thinking 自动收起", stepThinking2.action.isExpanded)
        assertTrue("后序无新块时，最新的 Tool 保持展开", stepTool2.action.isExpanded)

        // 3. 正文（Text）出现：单步穿透时自动收起
        val turn3 = HomeChatTurn(
            id = 20L,
            userText = "q",
            blocks = listOf(
                HomeChatBlock.Tool(HomeToolStatus(name = "shell", state = HomeToolState.Succeeded)),
                HomeChatBlock.Text(text = "Final answer"),
            ),
        )
        val ui3 = turn3.toTurnUiModel()
        val stepTool3 = ui3.sections[0] as TurnSection.Step
        assertFalse("正文出现后单步工具自动收起", stepTool3.action.isExpanded)

        // 4. 用户手动干预最高优先级：用户主动展开的单步，在正文出现后依然保持展开
        val ui4 = turn3.toTurnUiModel(
            userToggledKeys = mapOf("20_tool_0" to true),
        )
        val stepTool4 = ui4.sections[0] as TurnSection.Step
        assertTrue("用户手动展开的 Tool 在正文出现后依然保持展开", stepTool4.action.isExpanded)
    }
}
