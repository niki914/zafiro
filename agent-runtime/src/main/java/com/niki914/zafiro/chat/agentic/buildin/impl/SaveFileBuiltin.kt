package com.niki914.zafiro.chat.agentic.buildin.impl

import com.niki914.xposed.api.util.ContextProvider
import com.niki914.zafiro.chat.agentic.buildin.BuiltinTool
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolResult
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.Base64

/** Where agent-made files may live and which relative names are acceptable. Pure, so path escapes are unit-tested. */
object OutputPaths {
    const val ROOT_NAME = "outputs"
    const val MAX_BYTES = 8L * 1024 * 1024
    private val OK_CHARS = Regex("[A-Za-z0-9._ \\-/]+")

    /** @return the target file inside [root], or null when [relative] is unsafe or malformed. */
    fun resolve(root: File, relative: String): File? {
        val rel = relative.trim().replace('\\', '/').trimStart('/')
        if (rel.isEmpty() || rel.length > 200 || !OK_CHARS.matches(rel)) return null
        val parts = rel.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." || it.startsWith(".") }) return null
        val file = File(root, rel)
        val rootPath = root.canonicalPath
        return file.takeIf { it.canonicalPath.startsWith(rootPath + File.separator) }
    }

    /** Markdown link the chat opens in the in-app viewer. Spaces and other odd characters are percent-encoded so the link parses. */
    fun link(name: String, relative: String): String =
        "[$name](zfile:" + java.net.URLEncoder.encode(relative, "UTF-8").replace("%2F", "/").replace("+", "%20") + ")"

    fun mime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "md", "markdown" -> "text/markdown"
        "html", "htm" -> "text/html"
        "txt", "log" -> "text/plain"
        "json" -> "application/json"
        "csv" -> "text/csv"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "svg" -> "image/svg+xml"
        "pdf" -> "application/pdf"
        "mp4" -> "video/mp4"
        else -> "application/octet-stream"
    }
}

/**
 * Lets an agent produce a file the owner can open: Markdown plans, HTML reports, images, CSV... It is saved in the app's
 * outputs folder, shown on the Files page, and returned as a link the chat opens in the in-app viewer.
 */
class SaveFileBuiltin(private val rootProvider: suspend () -> File = { File(ContextProvider.await().applicationContext.filesDir, OutputPaths.ROOT_NAME) }) : BuiltinTool() {
    override val name: String = "save_file"
    override val defaultEnabled: Boolean = true
    override val description: String =
        "Save a file the owner can open from the Files page or from your reply. op=save (path, content) writes text (Markdown, HTML, CSV, JSON...); " +
            "for binary files pass content_base64 instead. path is relative, like plans/week-47.md. op=list (dir) lists saved files. " +
            "After saving, put the returned link in your reply exactly as given so the owner can tap it. Saving to an existing path replaces it."
    override val inputSchemaJson: String =
        """{"type":"object","properties":{"op":{"type":"string","enum":["save","list"]},"path":{"type":"string"},"content":{"type":"string"},"content_base64":{"type":"string"},"dir":{"type":"string"}},"required":["op"]}"""

    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
        val args = parseScreenObject(request.argumentsJson)
            ?: return BuiltinToolResult.failure(code = "INVALID_ARGUMENTS", message = "Arguments must be a JSON object.")
        val root = rootProvider().also { it.mkdirs() }
        return when (args.text("op")) {
            "save" -> save(root, args)
            "list" -> list(root, args.text("dir").orEmpty())
            else -> BuiltinToolResult.failure(code = "INVALID_ARGUMENTS", message = "op must be save or list.")
        }
    }

    private fun save(root: File, args: kotlinx.serialization.json.JsonObject): BuiltinToolResult {
        val rel = args.text("path") ?: return BuiltinToolResult.failure(code = "INVALID_ARGUMENTS", message = "path is required.")
        val file = OutputPaths.resolve(root, rel) ?: return BuiltinToolResult.failure(
            code = "INVALID_PATH", message = "Unsafe or invalid path \"$rel\".", hint = "Use a relative path like plans/week-47.md (letters, digits, . _ - space, no .. and no leading dots).",
        )
        val b64 = args.text("content_base64")
        val bytes = try {
            if (b64 != null) Base64.getMimeDecoder().decode(b64) else (args["content"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                ?: return BuiltinToolResult.failure(code = "INVALID_ARGUMENTS", message = "Pass content or content_base64.")).toByteArray()
        } catch (e: IllegalArgumentException) {
            return BuiltinToolResult.failure(code = "INVALID_BASE64", message = "content_base64 is not valid base64.")
        }
        if (bytes.size > OutputPaths.MAX_BYTES) return BuiltinToolResult.failure(code = "TOO_LARGE", message = "Files are limited to ${OutputPaths.MAX_BYTES / 1024 / 1024} MB.")
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        val relative = file.relativeTo(root).path.replace(File.separatorChar, '/')
        return BuiltinToolResult.success(
            message = "Saved $relative (${bytes.size} bytes). Show the owner this link: ${OutputPaths.link(file.name, relative)}",
            data = buildJsonObject {
                put("path", relative); put("bytes", bytes.size); put("mime", OutputPaths.mime(file.name)); put("link", OutputPaths.link(file.name, relative))
            },
        )
    }

    private fun list(root: File, dir: String): BuiltinToolResult {
        val base = if (dir.isBlank()) root else OutputPaths.resolve(root, dir) ?: return BuiltinToolResult.failure(code = "INVALID_PATH", message = "Unsafe dir.")
        val files = base.walkTopDown().filter { it.isFile }.sortedByDescending { it.lastModified() }.take(100).toList()
        return BuiltinToolResult.success(
            message = "${files.size} file(s).",
            data = buildJsonObject {
                put("files", buildJsonArray {
                    files.forEach { f ->
                        val r = f.relativeTo(root).path.replace(File.separatorChar, '/')
                        add(buildJsonObject { put("path", r); put("bytes", f.length()); put("link", OutputPaths.link(f.name, r)) })
                    }
                })
            },
        )
    }
}
