package com.niki914.zafiro.app.ui.model

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.niki914.zafiro.api.util.tryOrNull
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.model.ToolPresentation.inputOf
import com.niki914.zafiro.app.ui.model.ToolPresentation.previewOf
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.niki914.zafiro.remoteview.R as RemoteViewR

/**
 * 折叠块 UI 展示数据：图标、显示名（本地化 res id）、输入预览。纯函数对象。
 * 图标访问器 @Composable（python 走自绘 drawable）；显示名/预览为普通函数，
 * ViewModel 与 ConversationFormatter 均可调用（直播与恢复共用同一映射）。
 * inputOf 取工具参数原文（复制/全量展示用），previewOf 是发布于 [inputOf] 的显示变换（首行压平）；
 * 预留演进：后续可改为 agent 传的意图字段，直接展示"这个操作是要干啥"（如"罗列文件"），不再依赖参数猜。
 */
object ToolPresentation {
    /** Thinking 图标：自绘 spark drawable（ic_thinking）。 */
    val Thinking: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_thinking)

    private val TerminalIcon = Icons.Filled.Terminal
    private val Skill = Icons.AutoMirrored.Filled.MenuBook
    private val Camera = Icons.Filled.CameraAlt
    private val ImageTool = Icons.Filled.Image

    /** 默认（兜底）图标：扳手。 */
    val Default = Icons.Filled.Build

    /** 多工具链头部图标：堆叠层。 */
    val Multi = Icons.Filled.Layers

    @Composable
    fun forTool(name: String): ImageVector {
        val python = ImageVector.vectorResource(R.drawable.python_logo)
        return when {
            name.contains("python", ignoreCase = true) -> python
            name.contains(
                "shell",
                ignoreCase = true
            ) || name.contains(
                "ssh",
                ignoreCase = true
            ) -> TerminalIcon

            name == "screenshot" -> Camera
            name == "view_image" -> ImageTool
            name.contains("skill", ignoreCase = true) -> Skill
            else -> Default
        }
    }

    /** 内置工具 → 本地化显示名 res id；Custom Tool / MCP 命中不了 → null（回退原始名）。 */
    fun displayNameResOf(name: String): Int? = when (name) {
        "shell" -> R.string.ui_tool_display_shell
        "ssh" -> R.string.ui_tool_display_ssh
        "load_skill" -> R.string.ui_tool_display_load_skill
        "execute_python" -> R.string.ui_tool_display_execute_python
        "create_custom_tool" -> R.string.ui_tool_display_create_custom_tool
        "launch_app" -> R.string.ui_tool_display_launch_app
        "memory" -> R.string.ui_tool_display_memory
        "notify" -> R.string.ui_tool_display_notify
        "open_uri" -> R.string.ui_tool_display_open_uri
        "read_custom_tool" -> R.string.ui_tool_display_read_custom_tool
        "find_installed_apps" -> R.string.ui_tool_display_find_installed_apps
        "screen_operation_accessibility" -> R.string.ui_tool_display_screen_operation_accessibility
        "screen_operation_shell" -> R.string.ui_tool_display_screen_operation_shell
        "screenshot" -> R.string.ui_tool_display_screenshot
        "view_image" -> R.string.ui_tool_display_view_image
        "py_meta_tools" -> R.string.ui_tool_display_py_meta_tools
        else -> null
    }

    /**
     * 工具执行中的展示名：内置工具走本地化显示名，未命中映射（Custom Tool / MCP）
     * 回退参数携带的 label，再回退原始工具名。
     */
    fun displayName(context: Context, toolName: String, label: String): String =
        displayNameResOf(toolName)?.let { context.getString(it) }
            ?: label.takeIf { it.isNotBlank() }
            ?: toolName

    /**
     * 工具执行中文案：悬浮球卡片与常驻通知共用同一口径（文案复用 remote-view 的
     * [RemoteViewR.string.floating_ball_tool_running]，不另起一份翻译）。
     */
    fun runningText(context: Context, toolName: String, label: String): String =
        context.getString(
            RemoteViewR.string.floating_ball_tool_running,
            displayName(context, toolName, label),
        )

    /**
     * 工具参数原文：shell/ssh 取完整 command、execute_python 取完整 code、load_skill 取 id；
     * 其余工具 / 参数缺失 → null。复制按原文，展示按 [previewOf] 裁剪。
     */
    fun inputOf(name: String, argumentsJson: String?): String? {
        if (argumentsJson.isNullOrBlank()) return null
        val args = tryOrNull {
            Json.parseToJsonElement(argumentsJson).jsonObject
        } ?: return null
        return when (name) {
            "shell", "ssh" -> args["command"] ?: args["action"]
            "load_skill" -> args["id"]
            "execute_python" -> args["code"]
            "open_uri" -> args["uri"] ?: args["url"]
            "launch_app" -> args["name"] ?: args["package_name"] ?: args["app"]
            "find_installed_apps" -> args["query"] ?: args["keyword"]
            "notify" -> args["content"] ?: args["title"]
            "memory" -> args["content"] ?: args["query"]
            "view_image" -> args["path"] ?: args["uri"]
            "py_meta_tools" -> args["tool"] ?: args["action"]
            "screen_operation_accessibility", "screen_operation_shell" -> args["action"]
            else -> null
        }?.let { (it as? JsonPrimitive)?.contentOrNull }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private val PrettyJson = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    /**
     * 若字符串是合法的 JSON 对象或数组，则进行 Pretty 缩进排版；
     * 若不是 JSON、是标量或超过安全长度限制，则返回原始文本。
     */
    fun prettyPrintIfJson(raw: String?, maxChars: Int = 50_000): String? {
        if (raw.isNullOrBlank()) return raw
        val trimmed = raw.trim()
        if (trimmed.length !in 2..maxChars) return raw
        val first = trimmed.first()
        val last = trimmed.last()
        val isObject = first == '{' && last == '}'
        val isArray = first == '[' && last == ']'
        if (!isObject && !isArray) return raw
        return tryOrNull {
            val element = Json.parseToJsonElement(trimmed)
            if (element is JsonPrimitive) return@tryOrNull raw
            PrettyJson.encodeToString(JsonElement.serializer(), element)
        } ?: raw
    }

    /**
     * 精简输出格式化：剔除无意义的协议信封（#!tool-result、JSON 包装）、屏蔽全屏 YAML 视图树巨量噪音，
     * 提取实际有价值的文本结果；无法提取时回退原始文本。
     */
    fun formatConciseOutput(name: String, rawResult: String?): String? {
        if (rawResult.isNullOrBlank()) return null
        val unwrapped = if (rawResult.startsWith("#!tool-result")) {
            if (rawResult.contains("\n\n")) {
                rawResult.substringAfter("\n\n").trim()
            } else {
                rawResult.removePrefix("#!tool-result").trim()
            }
        } else rawResult.trim()

        val json = tryOrNull { Json.parseToJsonElement(unwrapped).jsonObject }
        if (json != null) {
            // 1. 优先提取命令行/Python 执行的 stdout/output/stderr
            val stdoutParts = listOf("stdout", "output", "stderr")
                .mapNotNull { key -> json[key]?.jsonPrimitive?.contentOrNull }
                .filter { it.isNotBlank() }
            if (stdoutParts.isNotEmpty()) {
                return stdoutParts.joinToString("\n")
            }

            // 2. find_installed_apps 列表提取
            if (name == "find_installed_apps") {
                val dataArray = tryOrNull { json["data"]?.jsonArray }
                if (dataArray != null && dataArray.isNotEmpty()) {
                    val apps = dataArray.mapNotNull { item ->
                        val obj = tryOrNull { item.jsonObject } ?: return@mapNotNull null
                        val appName = obj["app_name"]?.jsonPrimitive?.contentOrNull
                        val pkg = obj["package_name"]?.jsonPrimitive?.contentOrNull
                        if (!appName.isNullOrBlank() && !pkg.isNullOrBlank()) {
                            "$appName ($pkg)"
                        } else {
                            appName ?: pkg
                        }
                    }
                    if (apps.isNotEmpty()) return apps.joinToString("\n")
                }
            }

            // 3. message 提示文案
            val msg = json["message"]?.jsonPrimitive?.contentOrNull
            if (!msg.isNullOrBlank()) return msg

            // 4. data 标量
            val data = json["data"]
            if (data is JsonPrimitive && data.content.isNotBlank()) {
                return data.content
            }
        }

        return unwrapped
    }

    /** 输入预览：首段非空行压成单行；空 → null（只显示工具名）。 */
    fun previewOf(input: String?): String? =
        input?.lineSequence()?.firstOrNull { it.isNotBlank() }?.collapseToSingleLine()

    private fun String.collapseToSingleLine(): String? =
        replace(Regex("\\s+"), " ").trim().takeIf { it.isNotEmpty() }
}