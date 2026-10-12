package com.niki914.zafiro.repo

import com.niki914.logging.Logger
import com.niki914.zafiro.settings.model.LlmProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Request
import java.util.concurrent.TimeUnit

private const val LOG_TAG = "niki914_zafiro_ModelCatalogApi"

/** 一份 /models 响应的全部产出：下拉列表 + 各模型的云端事实。 */
data class ModelCatalog(
    val ids: List<String>,
    /** 以响应里的原始 model id 为键。 */
    val facts: Map<String, CloudModelFacts> = emptyMap(),
)

/**
 * 模型目录扫描：GET {endpoint→models} 取 `data[].id` 与限额字段。
 * 失败抛异常，调用方静默吞掉（失败 = 无事发生，只记日志）。
 * 不走代理；不解析能力 / 计费字段；无缓存、无分页。
 */
internal object ModelCatalogApi {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetch(
        modelsUrl: String,
        apiKey: String,
        protocol: LlmProtocol,
    ): ModelCatalog = withContext(Dispatchers.IO) {
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
                Logger.w(LOG_TAG, "models failed url=$modelsUrl code=${response.code}")
                error("models HTTP ${response.code}")
            }
            Logger.i(
                LOG_TAG,
                "models ok url=$modelsUrl bytes=${body.length} shape=${describeCatalogShape(body)}"
            )
            parseCatalog(body)
        }
    }

    /** 一次解析出 ids 和 facts，避免大响应体重复 parseToJsonElement。 */
    internal fun parseCatalog(body: String): ModelCatalog {
        return runCatching {
            val dataEntries = json.parseToJsonElement(body)
                .jsonObject["data"]
                ?.jsonArray
                ?.mapNotNull { it as? JsonObject }
                .orEmpty()
            val ids = dataEntries.mapNotNull { entry ->
                entry["id"]?.jsonPrimitive?.contentOrNull
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
            }.distinct().sorted()
            val facts = dataEntries.mapNotNull { entry ->
                val id = entry["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (id.isEmpty()) return@mapNotNull null
                factsOf(entry)?.let { id to it }
            }.toMap()
            ModelCatalog(ids = ids, facts = facts)
        }.getOrDefault(ModelCatalog(emptyList()))
    }

    /**
     * 把目录响应的形状压成一行：顶层键、条目数、首条的键与内容。
     * 用途是摸清各 Provider 的 /models 究竟返回哪些字段（尤其是上下文窗口）。
     * 只读不抛：诊断失败不能把请求路径带崩，失败只回原因。
     */
    internal fun describeCatalogShape(body: String, previewChars: Int = 800): String {
        return runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            val entries = root["data"]?.jsonArray ?: root["models"]?.jsonArray
            val first = entries?.firstOrNull()?.jsonObject
            "top=${root.keys} count=${entries?.size ?: -1} " +
                    "firstKeys=${first?.keys} first=${first?.toString()?.take(previewChars)}"
        }.getOrElse { "unreadable(${it.message})" }
    }

    /** 只认 `data[].id`，空 id 丢掉，去重排序。 */
    internal fun parseModelIds(body: String): List<String> = parseCatalog(body).ids

    /** 从同一份目录响应里提各模型的窗口与 max output，按原始 model id 键。
     *  两个字段都缺的条目不入表（Provider 没报就留给下一层解析源）。
     *  字段探测顺序（窗口）：context_window → context_length → max_input_tokens → top_provider.context_length；
     *  （max output）：max_output_tokens → max_completion_tokens → max_tokens → top_provider.max_completion_tokens。 */
    internal fun parseCatalogFacts(body: String): Map<String, CloudModelFacts> {
        return runCatching {
            json.parseToJsonElement(body)
                .jsonObject["data"]
                ?.jsonArray
                ?.mapNotNull { element -> element as? JsonObject }
                ?.mapNotNull { entry ->
                    val id = entry["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    if (id.isEmpty()) return@mapNotNull null
                    factsOf(entry)?.let { id to it }
                }
                ?.toMap()
                .orEmpty()
        }.getOrDefault(emptyMap())
    }

    internal fun factsOf(entry: JsonObject): CloudModelFacts? {
        val window = entry.positiveLong("context_window")
            ?: entry.positiveLong("context_length")
            ?: entry.positiveLong("max_input_tokens")
            ?: (entry["top_provider"] as? JsonObject)?.positiveLong("context_length")
        val maxOutput = entry.positiveLong("max_output_tokens")
            ?: entry.positiveLong("max_completion_tokens")
            ?: entry.positiveLong("max_tokens")
            ?: (entry["top_provider"] as? JsonObject)?.positiveLong("max_completion_tokens")
        if (window == null && maxOutput == null) return null
        return CloudModelFacts(windowTokens = window, maxOutputTokens = maxOutput)
    }

    private fun JsonObject.positiveLong(key: String): Long? {
        return (this[key] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0L }
    }
}
