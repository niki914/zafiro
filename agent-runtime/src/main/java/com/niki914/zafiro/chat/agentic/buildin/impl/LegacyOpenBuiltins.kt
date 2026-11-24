package com.niki914.zafiro.chat.agentic.buildin.impl

import com.niki914.zafiro.api.screen.PhoneScreenId
import com.niki914.zafiro.api.screen.ShowResult
import com.niki914.zafiro.api.screen.ShowTarget
import com.niki914.zafiro.chat.agentic.buildin.BuiltinTool
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolResult

/**
 * `open_uri` and `launch_app` were removed upstream in favour of the single `open` tool. Existing agent prompts, workspaces and
 * recipes (WhatsApp digest, email) call them by name, so they stay as thin aliases over the phone screen's `show`.
 */
private suspend fun show(target: ShowTarget): BuiltinToolResult {
    val phone = screenDirectory()?.get(PhoneScreenId) ?: return BuiltinToolResult.failure(code = SCREEN_SERVICE_UNAVAILABLE, message = "Screen service is not installed.")
    return when (val r = phone.show(target)) {
        is ShowResult.Shown -> BuiltinToolResult.success(message = r.message ?: "Opened.")
        is ShowResult.Ambiguous -> BuiltinToolResult.failure(code = SCREEN_AMBIGUOUS_APP, message = "Several apps match '${r.name}': ${r.candidates.joinToString { it.packageName }}. Use package_name.")
        is ShowResult.Unsupported -> BuiltinToolResult.failure(code = SCREEN_UNSUPPORTED, message = "Cannot open that here.")
        is ShowResult.Failed -> BuiltinToolResult.failure(code = r.code, message = r.code)
    }
}

class OpenUriBuiltin : BuiltinTool() {
    override val name = "open_uri"
    override val defaultEnabled = true
    override val description = "Open a web address or deep link (https://, whatsapp://send?phone=...&text=..., tel:, mailto:) on the phone. Same as the open tool with uri."
    override val inputSchemaJson = """{"type":"object","properties":{"uri":{"type":"string"}},"required":["uri"]}"""
    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
        val uri = parseScreenObject(request.argumentsJson)?.text("uri") ?: return invalidArguments("uri is required.")
        return show(ShowTarget.Uri(uri))
    }
}

class LaunchAppBuiltin : BuiltinTool() {
    override val name = "launch_app"
    override val defaultEnabled = true
    override val description = "Launch an installed app by app_name (fuzzy) or exact package_name. Same as the open tool."
    override val inputSchemaJson = """{"type":"object","properties":{"app_name":{"type":"string"},"package_name":{"type":"string"}}}"""
    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
        val a = parseScreenObject(request.argumentsJson) ?: return invalidArguments("Arguments must be a JSON object.")
        a.text("package_name")?.let { return show(ShowTarget.PackageName(it)) }
        a.text("app_name")?.let { return show(ShowTarget.AppName(it)) }
        return invalidArguments("Provide package_name or app_name.")
    }
}
