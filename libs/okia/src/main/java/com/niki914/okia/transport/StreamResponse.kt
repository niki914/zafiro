package com.niki914.okia.transport

import kotlinx.coroutines.flow.Flow

/**
 * 流式交换的结果，sealed 两态：
 * Ok = 2xx 成功，响应头 + body 行流（SSE 解析入口）；
 * Error = 非 2xx，body 全文文本（JSON 错误 / 风控 HTML 等）。
 * 传输失败（连接 / 超时）不在此表达：HttpEngine.stream 是 suspend，
 * 网络错误抛异常（符合 Kotlin 取消语义，与 codex transport 层同构）。
 * Design source: codex TransportError::Http；okia 骨架 StreamResponse
 * （sealed 化，T3 裁决：三态可空字段收敛为类型强制分支）。
 */
sealed interface StreamResponse {

    /** 2xx：响应头先于 body 行到达，status 用于重试决策；lines 为原始 SSE 行流 */
    data class Ok(
        val statusCode: Int,
        val headers: Map<String, String>,
        val lines: Flow<SseLine>
    ) : StreamResponse {
        // 响应 headers 脱敏；lines 是冷流，只输出类型，不消费
        override fun toString(): String =
            "StreamResponse.Ok(statusCode=$statusCode, headers=${redactHeaders(headers)}, " +
                "lines=Flow<SseLine>)"
    }

    /** 非 2xx：错误 body 全文文本（HttpEngine 预读，非流式） */
    data class Error(
        val statusCode: Int,
        val headers: Map<String, String>,
        val body: String
    ) : StreamResponse {
        // body 可能含敏感信息（错误详情 / HTML），脱敏不输出内容
        override fun toString(): String =
            "StreamResponse.Error(statusCode=$statusCode, headers=${redactHeaders(headers)}, " +
                "body=██)"
    }
}
