package com.niki914.okia.transport

/**
 * ChatProtocol 构建、HttpEngine 执行的不可变 HTTP 请求。
 * sensitiveHeaderNames 由协议层（ProtocolCompatMapper.buildRequest）从
 * Compat.sensitiveHeaderNames 填入；transport 层只认识 header 名字字符串，
 * 不耦合具体协议。
 * Design source: okia 骨架 HttpRequest。
 */
data class HttpRequest(
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val body: String?,
    val timeouts: HttpTimeouts,
    val sensitiveHeaderNames: Set<String> = emptySet()
) {

    // 敏感 header 值与 URL query 值脱敏；body 不输出内容，只保留有无信息
    override fun toString(): String =
        "HttpRequest(url=${redactUrl(url)}, method=$method, " +
            "headers=${redactHeaders(headers, sensitiveHeaderNames)}, " +
            "body=${if (body == null) "null" else "██"}, timeouts=$timeouts)"
}

/** 精确匹配的敏感 header 名（忽略大小写）。 */
private val EXACT_SENSITIVE_HEADERS = setOf(
    "authorization", "cookie", "proxy-authorization", "set-cookie"
)

/** 敏感 header 名的片段（忽略大小写）。覆盖 api-key / x-api-key / token / secret 等。 */
private val SENSITIVE_HEADER_FRAGMENTS = listOf(
    "api-key", "apikey", "-key", "-token", "-secret", "-signature", "-auth"
)

/**
 * 按 header 名判定敏感（忽略大小写），toString / 日志脱敏用。
 * 精确白名单 + 片段匹配，不依赖具体协议。
 * Design source: okhttp3.internal.Util.isSensitiveHeader。
 */
fun isSensitiveHeader(name: String): Boolean {
    val lower = name.lowercase()
    return lower in EXACT_SENSITIVE_HEADERS ||
        SENSITIVE_HEADER_FRAGMENTS.any { lower.contains(it) }
}

/**
 * headers 的脱敏字符串：敏感值替换为 ██。
 * extraSensitive 为协议层补充的精确名（如 Compat.sensitiveHeaderNames）。
 */
fun redactHeaders(headers: Map<String, String>, extraSensitive: Set<String> = emptySet()): String =
    headers.entries.joinToString(prefix = "{", postfix = "}") { (name, value) ->
        val sensitive = isSensitiveHeader(name) ||
            extraSensitive.any { name.equals(it, ignoreCase = true) }
        "$name=${if (sensitive) "██" else value}"
    }

/**
 * URL 的脱敏字符串：query 参数值全部替换为 ██，保留参数名与无 query 部分。
 * 签名类参数（sig / token / key 等）无需逐一列举，全部值默认脱敏。
 */
fun redactUrl(url: String): String {
    val queryStart = url.indexOf('?')
    if (queryStart < 0 || queryStart == url.lastIndex) return url
    val base = url.substring(0, queryStart + 1)
    val query = url.substring(queryStart + 1)
    val redacted = query.split('&').joinToString("&") { pair ->
        val eq = pair.indexOf('=')
        if (eq < 0) pair else pair.substring(0, eq + 1) + "██"
    }
    return base + redacted
}

/** 毫秒级超时值。 */
data class HttpTimeouts(
    val connectMs: Long,
    val readMs: Long,
    val writeMs: Long
)
