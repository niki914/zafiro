package com.niki914.zafiro.repo

import com.niki914.logging.Logger
import com.niki914.store.StoreDescriptorRegistry
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.long
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.obj
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.parseObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val LOG_TAG = "niki914_zafiro_CloudFactsCache"

/** 单个模型的云端事实；任一字段缺失都允许（各 Provider 的 /models 返回的字段不一样）。 */
data class CloudModelFacts(
    val windowTokens: Long? = null,
    val maxOutputTokens: Long? = null,
)

/**
 * llm.model_facts store 的文档：{"facts": {"<endpoint>|<model>": {...}}}。
 * provider /models 抓到的直接事实，以 endpoint+model 为键、与「保存」解耦：
 * 改了半天没保存不会污染旧配置，多份配置共用同一条事实。
 */
data class ModelFactsDocument(val facts: Map<String, CloudModelFacts> = emptyMap())

/**
 * llm.openrouter_catalog store 的文档：{"fetched_at": 0, "models": {"<归一化名>": {...}}}。
 * App 启动预热的全局表，服务 PK 的第二来源。
 */
data class OpenRouterCatalogDocument(
    val fetchedAtMillis: Long = 0L,
    val models: Map<String, CloudModelFacts> = emptyMap(),
)

internal object ModelFactsSettingsCodec {
    fun parse(json: String): ModelFactsDocument {
        val root = parseObject(json)
        val facts = root.obj(FACTS_KEY)
            ?.mapNotNull { (key, value) -> (value as? JsonObject)?.let { key to factsOf(it) } }
            ?.toMap()
            .orEmpty()
        return ModelFactsDocument(facts)
    }

    fun encode(document: ModelFactsDocument): String {
        return JsonObject(
            mapOf(
                FACTS_KEY to JsonObject(
                    document.facts.mapValues { (_, facts) -> encodeFacts(facts) }
                ),
            )
        ).toString()
    }

    private fun factsOf(entry: JsonObject): CloudModelFacts {
        return CloudModelFacts(
            windowTokens = entry.long(WINDOW_KEY, 0L).takeIf { it > 0L },
            maxOutputTokens = entry.long(MAX_OUTPUT_KEY, 0L).takeIf { it > 0L },
        )
    }

    private fun encodeFacts(facts: CloudModelFacts): JsonObject {
        return JsonObject(
            buildMap {
                facts.windowTokens?.let { put(WINDOW_KEY, JsonPrimitive(it)) }
                facts.maxOutputTokens?.let { put(MAX_OUTPUT_KEY, JsonPrimitive(it)) }
            }
        )
    }

    private const val FACTS_KEY = "facts"
    private const val WINDOW_KEY = "window"
    private const val MAX_OUTPUT_KEY = "max_output"
}

internal object OpenRouterCatalogSettingsCodec {
    fun parse(json: String): OpenRouterCatalogDocument {
        val root = parseObject(json)
        val models = root.obj(MODELS_KEY)
            ?.mapNotNull { (key, value) -> (value as? JsonObject)?.let { key to factsOf(it) } }
            ?.toMap()
            .orEmpty()
        return OpenRouterCatalogDocument(
            fetchedAtMillis = root.long(FETCHED_AT_KEY, 0L),
            models = models,
        )
    }

    fun encode(document: OpenRouterCatalogDocument): String {
        return JsonObject(
            mapOf(
                FETCHED_AT_KEY to JsonPrimitive(document.fetchedAtMillis),
                MODELS_KEY to JsonObject(
                    document.models.mapValues { (_, facts) -> encodeFacts(facts) }
                ),
            )
        ).toString()
    }

    private fun factsOf(entry: JsonObject): CloudModelFacts {
        return CloudModelFacts(
            windowTokens = entry.long(WINDOW_KEY, 0L).takeIf { it > 0L },
            maxOutputTokens = entry.long(MAX_OUTPUT_KEY, 0L).takeIf { it > 0L },
        )
    }

    private fun encodeFacts(facts: CloudModelFacts): JsonObject {
        return JsonObject(
            buildMap {
                facts.windowTokens?.let { put(WINDOW_KEY, JsonPrimitive(it)) }
                facts.maxOutputTokens?.let { put(MAX_OUTPUT_KEY, JsonPrimitive(it)) }
            }
        )
    }

    private const val FETCHED_AT_KEY = "fetched_at"
    private const val MODELS_KEY = "models"
    private const val WINDOW_KEY = "window"
    private const val MAX_OUTPUT_KEY = "max_output"
}

/**
 * 云端事实缓存：两个 store（provider 事实 / OpenRouter 目录）的读写与启动预热。
 * 写入走 XRepo 中心层（writeMutex 串行）；两个写入方（启动预热、配置页）各写各的 store。
 */
class CloudFactsApi internal constructor(
    private val repo: XRepo,
) {
    suspend fun providerFacts(endpoint: String?, model: String?): CloudModelFacts? {
        if (endpoint.isNullOrBlank() || model.isNullOrBlank()) return null
        val doc = ModelFactsSettingsCodec.parse(
            repo.readJson(StoreDescriptorRegistry.LLM_MODEL_FACTS_ID)
        )
        return doc.facts[factsKey(endpoint, model)]
    }

    /** 一次成功抓取整批写入（一次 read-modify-write，不逐条写）。 */
    suspend fun saveProviderFacts(endpoint: String, factsByModel: Map<String, CloudModelFacts>) {
        if (endpoint.isBlank() || factsByModel.isEmpty()) return
        repo.updateJson(StoreDescriptorRegistry.LLM_MODEL_FACTS_ID) { json ->
            val doc = ModelFactsSettingsCodec.parse(json)
            val merged = doc.facts.toMutableMap()
            factsByModel.forEach { (model, facts) -> merged[factsKey(endpoint, model)] = facts }
            ModelFactsSettingsCodec.encode(ModelFactsDocument(merged))
        }
    }

    suspend fun openRouter(): OpenRouterCatalogDocument {
        return OpenRouterCatalogSettingsCodec.parse(
            repo.readJson(StoreDescriptorRegistry.OPENROUTER_CATALOG_ID)
        )
    }

    suspend fun saveOpenRouter(document: OpenRouterCatalogDocument) {
        repo.writeJson(
            StoreDescriptorRegistry.OPENROUTER_CATALOG_ID,
            OpenRouterCatalogSettingsCodec.encode(document)
        )
    }

    /**
     * 启动预热：距上次成功不足 [OpenRouterCatalogApi.REFRESH_MIN_INTERVAL_MILLIS] 则跳过；
     * 失败保留旧缓存（失败 = 无事发生，只记日志）。
     */
    suspend fun refreshOpenRouterIfStale(nowMillis: Long = System.currentTimeMillis()) {
        val current = openRouter()
        if (current.fetchedAtMillis > 0L &&
            nowMillis - current.fetchedAtMillis < OpenRouterCatalogApi.REFRESH_MIN_INTERVAL_MILLIS
        ) {
            Logger.d(LOG_TAG, "openrouter warm skipped freshAt=${current.fetchedAtMillis}")
            return
        }
        val fresh = runCatching { OpenRouterCatalogApi.fetch() }
            .onFailure { Logger.w(LOG_TAG, "openrouter warm failed: ${it.message}") }
            .getOrNull() ?: return
        // 启动预热是 fire-and-forget：落盘失败也不能抛（未捕获协程异常会崩 app）
        runCatching { saveOpenRouter(fresh) }
            .onFailure { Logger.w(LOG_TAG, "openrouter cache write failed: ${it.message}") }
        Logger.i(
            LOG_TAG,
            "openrouter warm ok models=${fresh.models.size} elapsedMs=${System.currentTimeMillis() - nowMillis}"
        )
    }

    private fun factsKey(endpoint: String, model: String): String {
        return "${endpoint.trim()}|${model.trim()}"
    }
}
