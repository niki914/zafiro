package com.niki914.zafiro.repo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.niki914.zafiro.settings.model.LlmProtocol
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 模型目录扫描（id-only）：GET {endpoint→models} 取 `data[].id`。
 * 失败抛异常，调用方静默吞掉（失败 = 无事发生，只记日志）。
 * 不走代理；不解析能力 / 计费字段；无缓存、无分页。
 */
internal object ModelCatalogApi {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetch(
        modelsUrl: String,
        apiKey: String,
        protocol: LlmProtocol,
    ): List<String> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(modelsUrl)
            .get()
            .header("Accept", "application/json")
            .apply {
                val key = apiKey.trim()
                if (key.isNotEmpty()) {
                    if (protocol == LlmProtocol.AnthropicMessages) {
                        header("x-api-key", key)
                        header("anthropic-version", "2023-06-01")
                    } else {
                        header("Authorization", "Bearer $key")
                    }
                }
            }
            .build()
        // 短超时：配置页偶发请求，不阻塞；client 共享 base（连接池复用）
        val client = SharedHttp.client.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("models HTTP ${response.code}")
            }
            parseModelIds(body)
        }
    }

    /** 只认 `data[].id`，空 id 丢掉，去重排序。
     *  纯函数不用 xTry（它内部记日志，纯 JVM 单测无 Log）：runCatching 兜底空列表。 */
    internal fun parseModelIds(body: String): List<String> {
        return runCatching {
            json.parseToJsonElement(body)
                .jsonObject["data"]
                ?.jsonArray
                ?.mapNotNull { element ->
                    element.jsonObject["id"]?.jsonPrimitive?.contentOrNull
                        ?.trim()
                        ?.takeIf(String::isNotBlank)
                }
                ?.distinct()
                ?.sorted()
                .orEmpty()
        }.getOrDefault(emptyList())
    }
}
