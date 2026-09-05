package com.niki914.zafiro.chat.agentic.buildin.impl

import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.TextResultBuiltinTool
import com.niki914.zafiro.chat.agentic.buildin.TextToolResult
import com.niki914.zafiro.chat.agentic.python.PyRuntime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * py_download_file 工具：从 URL 下载文件到 Python 临时目录（与 py_install_apk 一致，
 * 进程私有可写、零外部存储权限负担；未来做文件访问权限时再统一存储位置）。
 */
class PyDownloadFileBuiltin(
    private val executor: suspend (code: String, timeoutMs: Long) -> String = PyRuntime::exec,
) : TextResultBuiltinTool() {

    override val name: String = "py_download_file"
    override val description: String = """
Download a file from a URL to a private app temporary directory.
Use when you need to download images, documents, or other files from the web.
Returns the local file path on success.
    """.trimIndent()
    override val defaultEnabled: Boolean = true
    override val inputSchemaJson: String? = SCHEMA

    override suspend fun invokeText(request: BuiltinToolRequest): TextToolResult {
        val args = try {
            Json.parseToJsonElement(request.argumentsJson.ifBlank { "{}" }).jsonObject
        } catch (e: Exception) {
            return TextToolResult.failure(
                code = "INVALID_ARGUMENTS",
                message = "argumentsJson is not valid JSON: ${e.message}"
            )
        }

        val url = (args["url"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (url.isBlank()) {
            return TextToolResult.failure(
                code = "MISSING_URL",
                message = "Field 'url' is required and must be a non-empty string."
            )
        }

        val filename = (args["filename"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

        val code = buildDownloadScript(url, filename)
        return try {
            val output = executor(code, DOWNLOAD_TIMEOUT_MS)
            val result = Json.parseToJsonElement(output.trim()).jsonObject
            val ok = (result["ok"] as? JsonPrimitive)?.booleanOrNull ?: false
            if (ok) {
                val path = (result["path"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                TextToolResult.success("Downloaded to $path")
            } else {
                val error = (result["error"] as? JsonPrimitive)?.contentOrNull ?: "Download failed"
                TextToolResult.failure(code = "DOWNLOAD_FAILED", message = error)
            }
        } catch (e: Exception) {
            TextToolResult.failure(
                code = "DOWNLOAD_ERROR",
                message = e.message ?: "Download failed"
            )
        }
    }

    private fun buildDownloadScript(url: String, filename: String): String {
        return """
import os
import tempfile
import urllib.request
import uuid
import json

url = ${repr(url)}
filename = ${repr(filename)}
download_dir = tempfile.gettempdir()

try:
    os.makedirs(download_dir, exist_ok=True)
    
    # Determine filename
    if filename:
        dest = os.path.join(download_dir, filename)
    else:
        # Extract from URL or generate UUID
        from urllib.parse import urlparse
        parsed = urlparse(url)
        path = parsed.path
        name = os.path.basename(path)
        if not name or '.' not in name:
            name = str(uuid.uuid4())
        dest = os.path.join(download_dir, name)
    
    # Download
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (Linux; Android 14)"})
    with urllib.request.urlopen(req, timeout=120) as resp, open(dest, "wb") as f:
        while True:
            chunk = resp.read(8192)
            if not chunk:
                break
            f.write(chunk)
    
    size = os.path.getsize(dest)
    print(json.dumps({"ok": True, "path": dest, "size": size}))
except Exception as e:
    print(json.dumps({"ok": False, "error": str(e)}))
        """.trimIndent()
    }

    companion object {
        private const val DOWNLOAD_TIMEOUT_MS = 120_000L
        private val SCHEMA = """
{
  "type": "object",
  "properties": {
    "url": {
      "type": "string",
      "description": "URL of the file to download."
    },
    "filename": {
      "type": "string",
      "description": "Optional filename. If omitted, extracted from URL or generated."
    }
  },
  "required": ["url"]
}
        """.trimIndent()
    }
}

private fun repr(s: String): String {
    return "\"${s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\""
}
