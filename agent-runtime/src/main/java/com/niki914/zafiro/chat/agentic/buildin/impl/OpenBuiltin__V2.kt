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
class OpenBuiltin__V2 : TextResultBuiltinTool() {
    override val name: String = "open"
    override val defaultEnabled: Boolean = true
    override val description: String =
        "Open an installed app or a URI/URL on a screen. Provide 'screen' (e.g. 'phone'), and " +
            "EXACTLY ONE of: 'app_name' (fuzzy app display name, e.g. '微信'), 'package_name' " +
            "(exact Android package name, e.g. 'com.tencent.mm'), or 'uri' (valid Android URI / web URL, " +
            "e.g. 'https://example.com' or custom deep link)."
    override val inputSchemaJson: String? = SCHEMA

    override suspend fun invokeText(request: BuiltinToolRequest): TextToolResult {
        val args = parseScreenObject(request.argumentsJson)
            ?: return TextToolResult.failure(
                code = SCREEN_INVALID_JSON,
                message = "arguments must be a JSON object.",
            )
        val id = args.screenId() ?: return invalidArguments("screen is required.")
        val target = openTarget(args) ?: return invalidArguments(
            "Provide exactly one of app_name, package_name, or uri.",
        )
        val screens = screenDirectory() ?: return missingService()
        val screen = screens.require(id) ?: return unknownScreen(id)
        return showToText(screen.show(target))
    }

    private fun openTarget(args: JsonObject): ShowTarget? {
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
                "screen": {
                  "type": "string",
                  "description": "Target screen ID to open on, e.g. 'phone'."
                },
                "app_name": {
                  "type": "string",
                  "description": "App display name to launch (e.g. '微信', '设置'). Fuzzy matching is supported; ambiguous matches return candidates."
                },
                "package_name": {
                  "type": "string",
                  "description": "Exact Android package name to launch, for example 'com.tencent.mm'."
                },
                "uri": {
                  "type": "string",
                  "description": "Known-valid Android URI or web URL to open via ACTION_VIEW intent (e.g. 'https://example.com', 'tel:10086', or custom deep link)."
                },
                "ui_intent": {
                  "type": "string"
                }
              },
              "required": ["screen"]
            }
        """.trimIndent()
    }
}
