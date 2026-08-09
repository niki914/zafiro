package com.niki914.okia

import com.niki914.okia.hooks.Hooks
import com.niki914.okia.hooks.ToolCallHolder
import com.niki914.okia.hooks.ToolResultHolder
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.okia.message.ToolCallOutcome
import com.niki914.okia.tooling.ToolCallContext
import com.niki914.okia.tooling.ToolDescriptor
import com.niki914.okia.tooling.ToolExecutor
import com.niki914.okia.tooling.ToolKind
import com.niki914.okia.tooling.ToolRegistry
import kotlinx.coroutines.runBlocking

/**
 * 标准 Okia 调用演示：2 个 Tooling + 1 个 Hook + 清屏打印完整对话。
 * 骨架阶段：库侧未实现处全部 TODO() 占位；本文件演示 host 侧使用形态，
 * 库实现后即可直接运行。
 * Design source: independent demo；形态来自 okia PRD §5.4（UI 状态流）/ §5.9（Hooks）。
 */
fun main() = runBlocking {
    // 清屏
    clearScreen()

    // 1. 注册两个工具（Tooling）
    val toolRegistry: ToolRegistry = TODO()
    toolRegistry.register(CalculatorTool.descriptor, CalculatorTool())
    toolRegistry.register(SearchTool.descriptor, SearchTool())

    // 2. 注册一个 hook（Hooks）
    val auditHook: Hooks = AuditHook()

    // 3. 打开门面并提交输入（默认协议，M0 DeepSeek）
    val okia: Okia = Okia.open {
        endpoint = "https://api.deepseek.com/v1"
        model = "deepseek-chat"
        hooks = listOf(auditHook)
    }

    // 4. 跑完整个回合（LLM ↔ 工具循环）
    okia.send("帮我计算 1+1，然后搜索今天的天气") { event ->
        // 流式事件处理（宿主 IPC 形态）
        TODO()
    }

    // 5. 清屏后打印完整对话
    clearScreen()
    printConversation(okia)

    // 6. 释放实例
    okia.close()
}

/** 工具 1：四则运算。 */
class CalculatorTool : ToolExecutor {

    override suspend fun execute(call: ToolCallContext): ToolCallOutcome = TODO()

    override fun onInterrupt(call: ToolCallContext): ToolCallOutcome = TODO()

    companion object {
        val descriptor = ToolDescriptor(
            name = "calculator",
            description = "四则运算",
            inputSchemaJson = null,
            kind = ToolKind.Local
        )
    }
}

/** 工具 2：网络搜索。 */
class SearchTool : ToolExecutor {

    override suspend fun execute(call: ToolCallContext): ToolCallOutcome = TODO()

    override fun onInterrupt(call: ToolCallContext): ToolCallOutcome = TODO()

    companion object {
        val descriptor = ToolDescriptor(
            name = "web_search",
            description = "网络搜索",
            inputSchemaJson = null,
            kind = ToolKind.Local
        )
    }
}

/** 1 个 hook：工具调用审计。 */
class AuditHook : Hooks {

    override suspend fun beforeToolCall(call: ToolCallHolder): Unit = TODO()

    override suspend fun afterToolCall(call: ToolCallHolder, result: ToolResultHolder): Unit = TODO()
}

// 清屏：ANSI 控制序列（跨平台终端）
private fun clearScreen() {
    print("\u001b[2J\u001b[H")
}

// 打印完整对话：角色 + 内容块 + 工具结果
private fun printConversation(okia: Okia) {
    okia.conversation.value.history.forEach { message ->
        when (message) {
            is Message.User -> println("[user] ${renderBlocks(message.content)}")
            is Message.Assistant -> println("[assistant] ${renderBlocks(message.message.content)}")
            is Message.ToolResult -> println("[tool:${message.toolName}] ${renderOutcome(message.outcome)}")
        }
    }
}

// 内容块渲染：文本 / 思考 / 图像 / 工具调用
private fun renderBlocks(blocks: List<ContentBlock>): String =
    blocks.joinToString("\n") { block ->
        when (block) {
            is ContentBlock.Text -> block.text
            is ContentBlock.Thinking -> "thinking: ${block.text}"
            is ContentBlock.Image -> "[image:${block.mimeType}]"
            is ContentBlock.ToolCall -> "tool(${block.name}): ${block.argumentsJson}"
        }
    }

// 工具结果渲染：5 态终态
private fun renderOutcome(outcome: ToolCallOutcome): String = when (outcome) {
    is ToolCallOutcome.Success -> "success: ${outcome.content}"
    is ToolCallOutcome.Failure -> "failure: ${outcome.message}"
    is ToolCallOutcome.Intercepted -> "intercepted: ${outcome.reason}"
    is ToolCallOutcome.Interrupted -> "interrupted: ${outcome.content ?: ""}"
    is ToolCallOutcome.Unknown -> "unknown: ${outcome.message}"
}
