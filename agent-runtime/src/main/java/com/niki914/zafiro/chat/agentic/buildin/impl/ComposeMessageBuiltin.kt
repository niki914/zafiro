package com.niki914.zafiro.chat.agentic.buildin.impl

import com.niki914.zafiro.chat.agentic.buildin.BuiltinTool
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URLEncoder

/**
 * Prepares an SMS or an email DRAFT. It never sends: it posts a notification and the human taps it to open the phone's own
 * composer with the text filled in, then presses send. (No SMS permission needed, nothing outward-facing happens unattended.)
 */
class ComposeMessageBuiltin : BuiltinTool() {
    override val name: String = "compose_message"
    override val description: String =
        "Prepare a draft SMS (kind=sms, to=phone number) or email (kind=email, to=address, optional subject). " +
            "It does NOT send: the human gets a notification, taps it, reviews the draft and sends it themselves."
    override val defaultEnabled: Boolean = true
    override val inputSchemaJson: String =
        """{"type":"object","properties":{"kind":{"type":"string","enum":["sms","email"]},"to":{"type":"string"},"subject":{"type":"string"},"body":{"type":"string"}},"required":["kind","to","body"]}"""

    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
        val args = runCatching { Json.parseToJsonElement(request.argumentsJson) as? JsonObject }.getOrNull()
            ?: return invalid("Arguments must be a JSON object.")
        fun str(k: String) = args[k]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val uri = buildUri(str("kind"), str("to"), str("subject"), str("body"))
            ?: return invalid("Need kind (sms|email), a valid 'to', and a non-empty 'body'.")
        val title = if (str("kind").lowercase() == "sms") "SMS draft ready - tap to review and send" else "Email draft ready - tap to review and send"
        val payload = buildJsonObject {
            put("title", title)
            put("content", str("body").take(200))
            put("uri", uri)
        }
        val posted = NotifyBuiltin().invoke(BuiltinToolRequest(name = "notify", argumentsJson = payload.toString()))
        return if (posted.ok) {
            BuiltinToolResult.success(message = "Draft prepared. Nothing was sent; the human must tap the notification and send it.")
        } else {
            BuiltinToolResult.failure(code = "DRAFT_NOTIFICATION_FAILED", message = "Could not post the draft notification.")
        }
    }

    private fun invalid(message: String) = BuiltinToolResult.failure(
        code = "INVALID_ARGUMENTS", message = message,
        hint = """Example: {"kind":"email","to":"a@b.com","subject":"Hi","body":"Hello"}""",
    )

    companion object {
        private val EMAIL = Regex("""^[^@\s,;<>]+@[^@\s,;<>]+\.[^@\s,;<>]+$""")
        private val PHONE = Regex("""^\+?[0-9][0-9 ()\-]{2,20}$""")

        /** The mailto:/sms: URI for a draft, or null when any input is unusable. */
        fun buildUri(kind: String, to: String, subject: String, body: String): String? {
            if (body.isBlank()) return null
            fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
            return when (kind.lowercase()) {
                "sms" -> if (PHONE.matches(to)) "sms:${to.replace(" ", "")}?body=${enc(body)}" else null
                "email" -> if (EMAIL.matches(to)) "mailto:$to?subject=${enc(subject)}&body=${enc(body)}" else null
                else -> null
            }
        }
    }
}
