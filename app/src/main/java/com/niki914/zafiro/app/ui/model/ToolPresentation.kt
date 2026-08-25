package com.niki914.zafiro.app.ui.model

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.niki914.zafiro.app.R
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 折叠块 UI 展示数据：图标、显示名（本地化 res id）、摘要预览。纯函数对象。
 * 图标访问器 @Composable（python 走自绘 drawable）；显示名/摘要为普通函数，
 * ViewModel 与 ConversationFormatter 均可调用（直播与恢复共用同一映射）。
 * summaryOf 目前按工具名从参数提取；预留演进：后续可改为 agent 传的意图字段，
 * 直接展示"这个操作是要干啥"（如"罗列文件"），不再依赖参数猜。
 */
object ToolPresentation {
    /** Thinking 图标（用户待定，暂用四角星星）。 */
    val Thinking = Icons.Filled.AutoAwesome

    private val TerminalIcon = Icons.Filled.Terminal
    private val Skill = Icons.AutoMirrored.Filled.MenuBook

    /** 默认（兜底）图标：扳手。 */
    val Default = Icons.Filled.Build

    /** 多工具链头部图标：堆叠层。 */
    val Multi = Icons.Filled.Layers

    @Composable
    fun forTool(name: String): ImageVector {
        val python = ImageVector.vectorResource(R.drawable.python_logo)
        return when {
            name.contains("python", ignoreCase = true) -> python
            name.contains("terminal", ignoreCase = true) || name.contains("shell", ignoreCase = true) -> TerminalIcon
            name.contains("skill", ignoreCase = true) -> Skill
            else -> Default
        }
    }

    /** 内置工具 → 本地化显示名 res id；Custom Tool / MCP 命中不了 → null（回退原始名）。 */
    fun displayNameResOf(name: String): Int? = when (name) {
        "terminal" -> R.string.ui_tool_display_terminal
        "load_skill" -> R.string.ui_tool_display_load_skill
        "execute_python" -> R.string.ui_tool_display_execute_python
        "create_custom_tool" -> R.string.ui_tool_display_create_custom_tool
        "launch_app" -> R.string.ui_tool_display_launch_app
        "memory" -> R.string.ui_tool_display_memory
        "notify" -> R.string.ui_tool_display_notify
        "open_uri" -> R.string.ui_tool_display_open_uri
        "read_custom_tool" -> R.string.ui_tool_display_read_custom_tool
        "search_apps" -> R.string.ui_tool_display_search_apps
        "screen_operation_accessibility" -> R.string.ui_tool_display_screen_operation_accessibility
        "screen_operation_shell" -> R.string.ui_tool_display_screen_operation_shell
        else -> null
    }

    /**
     * 工具参数摘要：Terminal 取 command、load_skill 取 id、execute_python 取代码首行；
     * 其余工具 / 参数缺失 → null（只显示标题，无预览）。
     */
    fun summaryOf(name: String, argumentsJson: String?): String? {
        if (argumentsJson.isNullOrBlank()) return null
        val args = try {
            Json.parseToJsonElement(argumentsJson).jsonObject
        } catch (_: Exception) {
            return null
        }
        val raw = when (name) {
            "terminal" -> args["command"]
            "load_skill" -> args["id"]
            "execute_python" -> args["code"]
            else -> null
        }?.jsonPrimitive?.contentOrNull
        return raw?.lineSequence()?.firstOrNull { it.isNotBlank() }?.collapseToSingleLine()
    }

    /** Thinking 标题摘要：首段非空行，压成单行；空文本 → null（只显示 "Thinking"）。 */
    fun thinkingPreviewOf(text: String): String? =
        text.lineSequence().firstOrNull { it.isNotBlank() }?.collapseToSingleLine()

    private fun String.collapseToSingleLine(): String? =
        replace(Regex("\\s+"), " ").trim().takeIf { it.isNotEmpty() }
}