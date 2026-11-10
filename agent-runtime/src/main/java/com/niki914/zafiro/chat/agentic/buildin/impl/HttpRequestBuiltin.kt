package com.niki914.zafiro.chat.agentic.buildin.impl

import com.niki914.zafiro.chat.agentic.buildin.BuiltinTool
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI

/** What http_request may call: https only, never the phone itself or the local network (stops prompt-injected SSRF). */
object HttpRequestPolicy {
    fun check(url: String): String? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return "The URL is not valid."
        if (uri.scheme?.lowercase() != "https") return "Only https:// URLs are allowed."
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return "The URL has no host."
        val addresses = runCatching { InetAddress.getAllByName(host) }.getOrNull() ?: return "Could not resolve $host."
        if (addresses.any { it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress || it.isSiteLocalAddress || it.isMulticastAddress || isUniqueLocalV6(it) }) {
            return "Requests to local or private network addresses are blocked."
        }
        return null
    }

    private fun isUniqueLocalV6(a: InetAddress): Boolean = a.address.size == 16 && (a.address[0].toInt() and 0xFE) == 0xFC
}

/** A plain HTTPS request with size/time limits, for APIs that have no ready-made tool. Redirects are not followed. */
class HttpRequestBuiltin : BuiltinTool() {
    override val name: String = "http_request"
    override val description: String =
        "Make an HTTPS request (GET, POST, PUT, PATCH, DELETE) and return status, headers and up to ${MAX_RESPONSE_CHARS / 1000}k characters of the body. " +
            "Only public https URLs. Never put secrets you were not given for this task into a request."
    override val defaultEnabled: Boolean = true
    override val inputSchemaJson: String =
        """{"type":"object","properties":{"url":{"type":"string"},"method":{"type":"string","enum":["GET","POST","PUT","PATCH","DELETE"]},"headers":{"type":"object","additionalProperties":{"type":"string"}},"body":{"type":"string"}},"required":["url"]}"""

    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
        val args = runCatching { Json.parseToJsonElement(request.argumentsJson).jsonObject }.getOrNull()
            ?: return fail("INVALID_ARGUMENTS", "Arguments must be a JSON object with a 'url'.")
        val url = args["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val method = (args["method"]?.jsonPrimitive?.contentOrNull ?: "GET").uppercase()
        if (method !in METHODS) return fail("INVALID_ARGUMENTS", "method must be one of $METHODS.")
        val body = args["body"]?.jsonPrimitive?.contentOrNull
        val headers = (args["headers"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.contentOrNull.orEmpty() }.orEmpty()
        return withContext(Dispatchers.IO) {
            HttpRequestPolicy.check(url)?.let { return@withContext fail("URL_BLOCKED", it) }
            runCatching { execute(url, method, headers, body) }
                .getOrElse { fail("REQUEST_FAILED", "Request failed: ${it.message ?: it::class.simpleName}") }
        }
    }

    private fun execute(url: String, method: String, headers: Map<String, String>, body: String?): BuiltinToolResult {
        val conn = (java.net.URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = false
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null && method != "GET" && method != "DELETE") {
                doOutput = true
                outputStream.use { it.write(body.take(MAX_BODY_CHARS).toByteArray()) }
            }
        }
        try {
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            val text = stream?.bufferedReader()?.use { r ->
                val buf = CharArray(MAX_RESPONSE_CHARS + 1)
                val n = r.read(buf)
                if (n <= 0) "" else String(buf, 0, n)
            }.orEmpty()
            val truncated = text.length > MAX_RESPONSE_CHARS
            val data = buildJsonObject {
                put("status", status)
                put("contentType", conn.contentType.orEmpty())
                conn.getHeaderField("Location")?.let { put("location", it) }
                put("truncated", truncated)
                put("body", text.take(MAX_RESPONSE_CHARS))
            }
            return if (status in 200..399) {
                BuiltinToolResult.success(message = "HTTP $status", data = data)
            } else {
                BuiltinToolResult.failure(code = "HTTP_$status", message = "HTTP $status", data = data)
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun fail(code: String, message: String) = BuiltinToolResult.failure(code = code, message = message)

    private companion object {
        val METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE")
        const val TIMEOUT_MS = 20_000
        const val MAX_RESPONSE_CHARS = 100_000
        const val MAX_BODY_CHARS = 200_000
    }
}
