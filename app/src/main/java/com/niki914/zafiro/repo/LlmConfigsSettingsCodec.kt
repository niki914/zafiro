package com.niki914.zafiro.repo

import com.niki914.zafiro.repo.SettingsJsonCodecUtils.array
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.boolean
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.long
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.obj
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.orEmptyObjects
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.parseObject
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.string
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.stringArray
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.stringNonBlankOrNull
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.stringValues
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 一份 Saved Configuration（完整可切换的 LLM 接入配置）。
 * 包含可选的独立 prompt（留空则回退至 [LlmConfigsDocument.prompt] 全局提示词）；
 * proxy 属于网络接入层，随配置走。
 */
data class SavedLlmConfig(
    val id: String,
    val name: String,
    val provider: String,
    val endpoint: String,
    val apiKey: String,
    val model: String,
    /** LlmProtocol.wireId；空串 = 未设置，运行时回落默认协议。 */
    val protocol: String,
    /** 视觉模型开关（图片输入支持），默认关。 */
    val supportsImages: Boolean = false,
    /** 独立 system prompt；空串 = 未设置，运行时回落全局提示词。 */
    val prompt: String = "",
    val proxy: String,
    /** ThinkingLevel.wireValue；空串 = 不发送思考字段（Provider 默认行为）。 */
    val thinkingLevel: String = "",
    /** 单次输出上限（max_output_tokens / max_tokens）；落盘原始文本（支持 1M/200k）。
     *  空串/旧格式的 0/缺键 = 未设置，由 gateway 落云端或默认值。 */
    val maxTokensText: String = "",
    /** 上下文窗口（Composer 环的分母）；落盘原始文本。空串/旧格式的 0/缺键 = 未设置。 */
    val contextWindowText: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

/**
 * llm.saved_configs store 的文档结构：
 * {"active_id": "...", "prompt": "全局 system prompt", "configs": [...],
 *  "revolver_enabled": true, "revolver_config_ids": ["cfg-…", …],
 *  "revolver_counts": {"cfg-…": 2, …}, "revolver_delays": {"cfg-…": 30, …}}
 *
 * 轮值（revolver）开启且无显式 configId 绑定时，运行时按 revolver_config_ids
 * 顺序轮转选配置，把多份 API key 的速率限制摊平；空列表 = 回落 active 配置。
 *
 * r21 新增（user 请求 3b/3c）：
 * - [revolverCounts]：每个候选模型每周期「发出请求次数」的权重。全为空时 = 均匀
 *   round-robin（每个 1 次）；非空时按权重展开，权重 0 = 该模型本轮跳过。
 * - [revolverDelays]：每个模型「收到上一个回答后，向同一模型发下一个请求前」的冷却
 *   秒数（速率限制器）。与 run control 的 Idle timeout（mid-stream 活性）正交，互不干扰。
 */
data class LlmConfigsDocument(
    val activeId: String? = null,
    val prompt: String = "",
    val configs: List<SavedLlmConfig> = emptyList(),
    val revolverEnabled: Boolean = false,
    val revolverConfigIds: List<String> = emptyList(),
    /** r21 3b：configId → 每周期请求数（权重）。缺键 = 该模型未设权重（默认 1）。 */
    val revolverCounts: Map<String, Int> = emptyMap(),
    /** r21 3c：configId → 冷却秒数。缺键 / 0 = 无冷却。 */
    val revolverDelays: Map<String, Long> = emptyMap(),
) {
    fun activeConfig(): SavedLlmConfig? {
        return configs.firstOrNull { it.id == activeId }
    }

    /** 轮值列表中仍存活的配置 id（保持声明顺序，去重）。 */
    fun revolverConfigs(): List<SavedLlmConfig> {
        val existing = configs.associateBy(SavedLlmConfig::id)
        return revolverConfigIds.distinct()
            .mapNotNull { id -> existing[id] }
    }

    /**
     * r21 3b：按权重把轮值展开成一个周期内的「模型序列」（每元素 = 一次请求应发到的配置）。
     * - [revolverCounts] 为空 / 全候选都缺权重 → 每个候选 1 次（均匀 round-robin，r20 行为）。
     * - 有权重 → 候选 i 重复 [counts] 次（按 [revolverConfigIds] 的声明顺序逐个展开）；
     *   权重 0 / 负 → 该候选本轮不出现。
     * 只保留在 [revolverConfigs] 里仍存活的配置，故展开结果与候选存活集一致。
     * 返回空列表当且仅当无任何存活候选（调用方据此回落 active 配置）。
     */
    fun revolverCycle(): List<SavedLlmConfig> {
        val alive = revolverConfigs()
        if (alive.isEmpty()) return emptyList()
        val withWeight = alive.any { revolverCounts[it.id] != null }
        return if (!withWeight) alive
        else alive.flatMap { c ->
            val w = revolverCounts[c.id] ?: 0
            List(maxOf(0, w)) { c }
        }
    }

    /** r21 3c：某配置的冷却秒数（缺键 / 未设 = 0，即无冷却）。 */
    fun revolverDelaySeconds(configId: String): Long = revolverDelays[configId] ?: 0L
}

internal object LlmConfigsSettingsCodec {
    fun parse(json: String): LlmConfigsDocument {
        val root = parseObject(json)
        return LlmConfigsDocument(
            activeId = root.stringNonBlankOrNull(ACTIVE_ID_KEY),
            prompt = root.string(PROMPT_KEY),
            configs = root.array(CONFIGS_KEY).orEmptyObjects().mapNotNull(::parseConfig),
            revolverEnabled = (root[REVOLVER_ENABLED_KEY] as? JsonPrimitive)?.booleanOrNull ?: false,
            revolverConfigIds = root.array(REVOLVER_CONFIG_IDS_KEY).stringValues(),
            revolverCounts = parseIntMap(root, REVOLVER_COUNTS_KEY),
            revolverDelays = parseLongMap(root, REVOLVER_DELAYS_KEY),
        )
    }

    fun encode(document: LlmConfigsDocument): String {
        return JsonObject(
            mapOf(
                ACTIVE_ID_KEY to JsonPrimitive(document.activeId.orEmpty()),
                PROMPT_KEY to JsonPrimitive(document.prompt),
                CONFIGS_KEY to JsonArray(
                    document.configs.map(::encodeConfig)
                ),
                REVOLVER_ENABLED_KEY to JsonPrimitive(document.revolverEnabled),
                REVOLVER_CONFIG_IDS_KEY to stringArray(document.revolverConfigIds),
                REVOLVER_COUNTS_KEY to document.revolverCounts.toIntJsonObject(),
                REVOLVER_DELAYS_KEY to document.revolverDelays.toLongJsonObject(),
            )
        ).toString()
    }

    /** r21 3b：读 configId → Int 权重映射（缺键 / 非数字值静默跳过）。 */
    private fun parseIntMap(root: JsonObject, key: String): Map<String, Int> {
        val obj = root.obj(key) ?: return emptyMap()
        return obj.entries
            .mapNotNull { (k, v) -> (v as? JsonPrimitive)?.intOrNull?.let { k to it } }
            .toMap()
    }

    /** r21 3c：读 configId → Long 冷却秒数映射（缺键 / 非数字值静默跳过）。 */
    private fun parseLongMap(root: JsonObject, key: String): Map<String, Long> {
        val obj = root.obj(key) ?: return emptyMap()
        return obj.entries
            .mapNotNull { (k, v) -> (v as? JsonPrimitive)?.longOrNull?.let { k to it } }
            .toMap()
    }

    /** 写 configId → Int 权重映射（r21 3b）。 */
    private fun Map<String, Int>.toIntJsonObject(): JsonObject =
        JsonObject(entries.map { (k, v) -> k to JsonPrimitive(v) }.toMap())

    /** 写 configId → Long 冷却秒数映射（r21 3c）。 */
    private fun Map<String, Long>.toLongJsonObject(): JsonObject =
        JsonObject(entries.map { (k, v) -> k to JsonPrimitive(v) }.toMap())

    private fun parseConfig(obj: JsonObject): SavedLlmConfig? {
        val id = obj.stringNonBlankOrNull(ID_KEY) ?: return null
        return SavedLlmConfig(
            id = id,
            name = obj.string(NAME_KEY).trim(),
            provider = obj.string(PROVIDER_KEY),
            endpoint = obj.string(ENDPOINT_KEY),
            apiKey = obj.string(API_KEY_KEY),
            model = obj.string(MODEL_KEY),
            protocol = obj.string(PROTOCOL_KEY),
            supportsImages = obj.boolean(SUPPORTS_IMAGES_KEY, default = false),
            prompt = obj.string(PROMPT_KEY),
            proxy = obj.string(PROXY_KEY),
            thinkingLevel = obj.string(THINKING_LEVEL_KEY),
            // 数字值经 obj.string() 读成字面文本（128000 → "128000"）。
            // 空串与 0 都视为未设置。
            maxTokensText = obj.string(MAX_TOKENS_KEY).trim().takeUnless { it == "0" }.orEmpty(),
            contextWindowText = obj.string(CONTEXT_WINDOW_TOKENS_KEY).trim()
                .takeUnless { it == "0" }.orEmpty(),
            createdAt = obj.long(CREATED_AT_KEY, 0L),
            updatedAt = obj.long(UPDATED_AT_KEY, 0L),
        )
    }

    private fun encodeConfig(config: SavedLlmConfig): JsonObject {
        return JsonObject(
            mapOf(
                ID_KEY to JsonPrimitive(config.id),
                NAME_KEY to JsonPrimitive(config.name),
                PROVIDER_KEY to JsonPrimitive(config.provider),
                ENDPOINT_KEY to JsonPrimitive(config.endpoint),
                API_KEY_KEY to JsonPrimitive(config.apiKey),
                MODEL_KEY to JsonPrimitive(config.model),
                PROTOCOL_KEY to JsonPrimitive(config.protocol),
                SUPPORTS_IMAGES_KEY to JsonPrimitive(config.supportsImages),
                PROMPT_KEY to JsonPrimitive(config.prompt),
                PROXY_KEY to JsonPrimitive(config.proxy),
                THINKING_LEVEL_KEY to JsonPrimitive(config.thinkingLevel),
                MAX_TOKENS_KEY to JsonPrimitive(config.maxTokensText),
                CONTEXT_WINDOW_TOKENS_KEY to JsonPrimitive(config.contextWindowText),
                CREATED_AT_KEY to JsonPrimitive(config.createdAt),
                UPDATED_AT_KEY to JsonPrimitive(config.updatedAt),
            )
        )
    }

    private const val ACTIVE_ID_KEY = "active_id"
    private const val PROMPT_KEY = "prompt"
    private const val CONFIGS_KEY = "configs"
    private const val REVOLVER_ENABLED_KEY = "revolver_enabled"
    private const val REVOLVER_CONFIG_IDS_KEY = "revolver_config_ids"
    private const val REVOLVER_COUNTS_KEY = "revolver_counts"
    private const val REVOLVER_DELAYS_KEY = "revolver_delays"
    private const val ID_KEY = "id"
    private const val NAME_KEY = "name"
    private const val PROVIDER_KEY = "provider"
    private const val ENDPOINT_KEY = "endpoint"
    private const val API_KEY_KEY = "api_key"
    private const val MODEL_KEY = "model"
    private const val PROTOCOL_KEY = "protocol"
    private const val SUPPORTS_IMAGES_KEY = "supports_images"
    private const val PROXY_KEY = "proxy"
    private const val THINKING_LEVEL_KEY = "thinking_level"
    private const val MAX_TOKENS_KEY = "max_tokens"
    private const val CONTEXT_WINDOW_TOKENS_KEY = "context_window_tokens"
    private const val CREATED_AT_KEY = "created_at"
    private const val UPDATED_AT_KEY = "updated_at"
}
