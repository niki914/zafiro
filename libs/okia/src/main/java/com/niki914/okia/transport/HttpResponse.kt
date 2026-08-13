package com.niki914.okia.transport

/**
 * 结构化单次响应。status 与 body 可空，两者在传输失败时可能缺失；
 * headers 默认为空，调用方无需判空。status 不拍平成消息串。
 * Design source: pi provider-retry、codex ApiError::Transport，
 * kai PRD §4.7；okia 骨架对照基线。
 */
data class HttpResponse(
    val statusCode: Int?,
    val headers: Map<String, String>,
    val body: ByteArray?
) {

    // 响应 headers 脱敏；body 不输出内容，只保留大小信息
    override fun toString(): String =
        "HttpResponse(statusCode=$statusCode, headers=${redactHeaders(headers)}, " +
            "body=${if (body == null) "null" else "byte[${body.size}]"})"
}
