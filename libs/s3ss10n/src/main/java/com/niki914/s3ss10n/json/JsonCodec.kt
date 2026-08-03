package com.niki914.s3ss10n.json

/**
 * JSON 编解码接口，收口模块内的 JSON 处理需求。
 * 
 * 失败语义：decode 系列方法在失败时返回 null 并打印错误日志，禁止抛出异常。
 * 独立性：本接口不绑定任何具体的 JSON 库实现。
 */
interface JsonCodec {
    fun encode(value: Any?): String
    fun <T : Any> decode(json: String, type: Class<T>): T?
    fun decodeMap(json: String): Map<String, Any?>?
    fun decodeList(json: String): List<Any?>?
}
