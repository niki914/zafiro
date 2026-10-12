package com.niki914.zafiro.repo

import com.niki914.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit

private const val LOG_TAG = "niki914_zafiro_OpenRouterCatalogApi"

/**
 * OpenRouter 公共目录（免鉴权）：App 启动预热抓一次，配置页与运行时都只查本地缓存。
 * 响应约 750KB；只解析 id + 限额字段，能力/计费字段不碰。
 */
internal object OpenRouterCatalogApi {
    private const val CATALOG_URL = "https://openrouter.ai/api/v1/models"

    /** 最小刷新间隔：反复冷启动不重复拉整份目录。 */
    internal const val REFRESH_MIN_INTERVAL_MILLIS = 30L * 60_000L

    suspend fun fetch(): OpenRouterCatalogDocument = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(CATALOG_URL)
            .get()
            .header("Accept", "application/json")
            .build()
        val client = SharedHttp.client.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                Logger.w(LOG_TAG, "openrouter failed code=${response.code}")
                error("openrouter HTTP ${response.code}")
            }
            // `:free`/`:batch`/`:extended` 变体折叠到同一键：同一键只保留首次出现的条目
            val models = mutableMapOf<String, CloudModelFacts>()
            ModelCatalogApi.parseCatalogFacts(body).forEach { (id, facts) ->
                models.putIfAbsent(catalogKey(id), facts)
            }
            Logger.i(LOG_TAG, "openrouter ok bytes=${body.length} models=${models.size}")
            OpenRouterCatalogDocument(
                fetchedAtMillis = System.currentTimeMillis(),
                models = models,
            )
        }
    }

    /**
     * 归一化：小写、去掉所有非字母数字。点/连字符差异（claude-sonnet-4.5 ↔ claude-sonnet-4-5）、
     * 组织前缀、`models/` 前缀都被折叠成同一个比较基准。
     */
    internal fun normalize(modelId: String): String {
        return modelId.lowercase().filter(Char::isLetterOrDigit)
    }

    /** 目录键：先去掉 `:变体` 后缀再归一化。 */
    internal fun catalogKey(modelId: String): String {
        return normalize(modelId.substringBefore(':'))
    }

    /** 在 OpenRouter 目录里找用户/Provider 侧的 model id。 */
    internal fun match(
        models: Map<String, CloudModelFacts>,
        modelId: String,
    ): CloudModelFacts? = matchEntry(models, modelId)?.value

    /** 同上，但连命中的目录键一起给出来（日志诊断用）。两级匹配：
     * 1. 全名归一化精确命中（google/gemini-2.5-pro ↔ google/gemini-2.5-pro）
     * 2. 裸名后缀命中（models/gemini-2.5-pro ↔ google/gemini-2.5-pro；
     *    kimi-k2 ↔ moonshotai/kimi-k2 之类组织名不同的同名模型） */
    internal fun matchEntry(
        models: Map<String, CloudModelFacts>,
        modelId: String,
    ): Map.Entry<String, CloudModelFacts>? {
        val full = normalize(modelId.substringBefore(':'))
        if (full.isEmpty()) return null
        models.entries.firstOrNull { it.key == full }?.let { return it }
        // # ponytail: 裸名后缀匹配可能命中同后缀的远端模型；兜底层数据源可接受，
        // 需要更严就对目录键做分隔符感知的段匹配。
        val bare = normalize(modelId.substringBefore(':').substringAfterLast('/'))
        if (bare.isEmpty()) return null
        return models.entries.firstOrNull { (key, _) -> key.endsWith(bare) }
    }
}
