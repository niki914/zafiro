package com.niki914.zafiro.chat.agentic.buildin.impl

import com.niki914.zafiro.api.screen.ShowTarget
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.TextResultBuiltinTool
import com.niki914.zafiro.chat.agentic.buildin.TextToolResult
import kotlinx.serialization.json.JsonObject

/**
 * 往一块屏上放应用或链接。
 * `app_name`、`package_name`、`uri` 恰好填一个，原样交给 [ShowTarget]。
 */
class ShowBuiltin__V2 : TextResultBuiltinTool() {
    override val name: String = "show"
    override val defaultEnabled: Boolean = true
    override val description: String =
        "Show an app or a URI on one screen. Provide screen, and exactly one of " +
            "app_name, package_name, or uri."
    override val inputSchemaJson: String? = SCHEMA

    override suspend fun invokeText(request: BuiltinToolRequest): TextToolResult {
        val args = parseScreenObject(request.argumentsJson)
            ?: return TextToolResult.failure(
                code = SCREEN_INVALID_JSON,
                message = "arguments must be a JSON object.",
            )
        val id = args.screenId() ?: return invalidArguments("screen is required.")
        val target = showTarget(args) ?: return invalidArguments(
            "Provide exactly one of app_name, package_name, or uri.",
        )
        val screens = screenDirectory() ?: return missingService()
        val screen = screens.require(id) ?: return unknownScreen(id)
        return showToText(screen.show(target))
    }

    private fun showTarget(args: JsonObject): ShowTarget? {
        val appName = args.text("app_name")
        val packageName = args.text("package_name")
        val uri = args.text("uri")
        val filled = listOfNotNull(appName, packageName, uri)
        if (filled.size != 1) return null
        return when {
            appName != null -> ShowTarget.AppName(appName)
            packageName != null -> ShowTarget.PackageName(packageName)
            uri != null -> ShowTarget.Uri(uri)
            else -> null
        }
    }

    private companion object {
        private val SCHEMA = """
            {
              "type": "object",
              "properties": {
                "screen": { "type": "string" },
                "app_name": { "type": "string" },
                "package_name": { "type": "string" },
                "uri": { "type": "string" }
              },
              "required": ["screen"]
            }
        """.trimIndent()
    }
}
