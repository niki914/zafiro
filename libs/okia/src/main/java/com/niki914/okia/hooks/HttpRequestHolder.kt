package com.niki914.okia.hooks

import com.niki914.okia.transport.HttpRequest

/**
 * beforeRequest 的可改数据载体：Provider 请求。afterRequest 只读、不经过
 * holder（见 Hooks.afterRequest）。
 * write 实现（T5）：request / lastWriter 为 private set 属性；write 改值并
 * 记录签名，多次 write 后者覆盖。
 * 落点 = HttpEngine.stream 输入（RealAgentLoop 发送 holder.request，
 * http 层兜底脱敏 / 改写，§5.9.4）。
 * Design source: pi extensions mutation。
 */
class HttpRequestHolder(
    initialRequest: HttpRequest
) {

    // 当前请求（write 后为改写值）；private set 保持只读暴露
    var request: HttpRequest = initialRequest
        private set

    // 最后写入者签名（write 记录）；未写入为 null
    var lastWriter: String? = null
        private set

    // 改写请求并记录签名
    fun write(request: HttpRequest, signature: String) {
        this.request = request
        lastWriter = signature
    }
}
