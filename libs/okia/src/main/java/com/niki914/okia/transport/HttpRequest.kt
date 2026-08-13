package com.niki914.okia.transport

/**
 * ChatProtocol 构建、HttpEngine 执行的不可变 HTTP 请求。
 * Design source: okia 骨架 HttpRequest。
 */
data class HttpRequest(
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val body: String?,
    val timeouts: HttpTimeouts
) {

    // 敏感 header 值替换为 ██；body 不输出内容，只保留有无信息
    override fun toString(): String =
        "HttpRequest(url=$url, method=$method, headers=${redactHeaders(headers)}, " +
            "body=${if (body == null) "null" else "██"}, timeouts=$timeouts)"
}

/**
 * 按 header 名判定敏感（忽略大小写），toString / 日志脱敏用。
 * Design source: okhttp3.internal.Util.isSensitiveHeader。
 */
fun isSensitiveHeader(name: String): Boolean =
    name.equals("Authorization", ignoreCase = true) ||
        name.equals("Cookie", ignoreCase = true) ||
        name.equals("Proxy-Authorization", ignoreCase = true) ||
        name.equals("Set-Cookie", ignoreCase = true)

/** headers 的脱敏字符串：敏感值替换为 ██。 */
fun redactHeaders(headers: Map<String, String>): String =
    headers.entries.joinToString(prefix = "{", postfix = "}") { (name, value) ->
        "$name=${if (isSensitiveHeader(name)) "██" else value}"
    }

/** 毫秒级超时值。 */
data class HttpTimeouts(
    val connectMs: Long,
    val readMs: Long,
    val writeMs: Long
)
