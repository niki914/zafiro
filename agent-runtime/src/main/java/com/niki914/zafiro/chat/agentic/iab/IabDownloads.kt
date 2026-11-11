package com.niki914.zafiro.chat.agentic.iab

import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

/**
 * 下载文件名清洗与 MIME → 扩展名推断（纯 JVM，可单测；
 * 防止 `../../etc/passwd` 式路径穿越与超长/空白文件名）。
 */
object IabDownloads {

    /** 允许字符白名单：字母数字 . _ - 与中日韩；其余一律替换为 `_`。 */
    fun sanitizeName(raw: String): String {
        val segment = raw.substringAfterLast('/').substringAfterLast('\\').trim().take(128)
        val cleaned = segment
            .map { c -> if (c.isLetterOrDigit() || c == '.' || c == '_' || c == '-') c else '_' }
            .joinToString("")
            .trim { it == '_' }
        // 穿越防护：白名单之后整段全点（"." / ".." / "..."）视为无效文件名。
        return if (cleaned.isEmpty() || cleaned.all { it == '.' }) "download" else cleaned
    }

    /** 从 Content-Disposition / URL 路径推断建议文件名；无扩展时按 [mimeType] 补。 */
    fun suggestedName(
        url: String,
        contentDisposition: String?,
        mimeType: String?,
    ): String {
        // 1) Content-Disposition: attachment; filename*=UTF-8''xxx / filename="xxx"
        var name: String? = null
        if (contentDisposition != null) {
            name = contentDisposition
                .split(";")
                .map { it.trim() }
                .firstNotNullOfOrNull { part ->
                    when {
                        part.startsWith("filename*=utf-8''", ignoreCase = true) -> {
                            URLDecoder.decode(part.substringAfter("''"), "UTF-8")
                        }
                        part.startsWith("filename*=", ignoreCase = true) -> part.substringAfter("=", "").trim('"')
                        part.startsWith("filename=", ignoreCase = true) -> part.substringAfter("=").trim('"')
                        else -> null
                    }
                }
        }
        // 2) URL 路径末段（去查询串）
        if (name.isNullOrEmpty()) {
            name = url.substringBefore('?').substringAfterLast('/').trim()
            name = if (name.isEmpty()) "download" else name
        }
        val safe = sanitizeName(name!!)
        // 3) 缺扩展名时按 MIME 补
        if (!safe.contains('.')) {
            extFor(mimeType)?.let { return "$safe.$it" }
        }
        return safe
    }

    /** 常见 MIME → 扩展名（覆盖 IAB 高频场景；未知返回 null = 不加扩展名）。 */
    fun extFor(mimeType: String?): String? = when (
        mimeType?.lowercase(Locale.ROOT)?.substringBefore(";")?.trim()
    ) {
        "application/pdf" -> "pdf"
        "image/png" -> "png"
        "image/jpeg" -> "jpg"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "text/html" -> "html"
        "text/plain" -> "txt"
        "text/csv" -> "csv"
        "application/json" -> "json"
        "application/xml" -> "xml"
        "application/zip" -> "zip"
        "application/x-zip-compressed" -> "zip"
        "application/gzip" -> "gz"
        "application/x-tar" -> "tar"
        "application/vnd.android.package-archive" -> "apk"
        "application/x-7z-compressed" -> "7z"
        "video/mp4" -> "mp4"
        "audio/mpeg" -> "mp3"
        else -> null
    }

    /** 文件上传的安全边界：只允许 App 私有目录（filesDir / cacheDir）内的文件，越界返回 false。 */
    fun isWithinAppDirs(resolved: String, appDirs: List<String>): Boolean {
        val r = resolved.trimEnd('/')
        return appDirs.any { dir ->
            val d = dir.trimEnd('/')
            r == d || r.startsWith("$d/")
        }
    }

    /** 工具入参路径归一：相对路径按 [baseDir] 解析，绝对路径原样（返回规范化路径）。 */
    fun normalizePath(path: String, baseDir: String): String {
        val raw = path.trim()
        val candidate = if (raw.startsWith("/") || raw.contains(":")) raw else "$baseDir/$raw"
        return candidate
    }
}
