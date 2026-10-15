package com.niki914.zafiro.repo

import com.niki914.logging.Logger
import com.niki914.zafiro.settings.MemoryMutationResult
import com.niki914.zafiro.settings.RuntimeSettingsGateway
import com.niki914.zafiro.settings.model.DEFAULT_CONTEXT_WINDOW_TOKENS
import com.niki914.zafiro.settings.model.DEFAULT_MAX_TOKENS
import com.niki914.zafiro.settings.model.RuntimeBuiltinToolSetting
import com.niki914.zafiro.settings.model.RuntimeCustomPyTool
import com.niki914.zafiro.settings.model.RuntimeExecutionRule
import com.niki914.zafiro.settings.model.RuntimeLlmConfig
import com.niki914.zafiro.settings.model.RuntimeLoadedSkill
import com.niki914.zafiro.settings.model.RuntimeMcpServer
import com.niki914.zafiro.settings.model.RuntimeSkillMetadata
import com.niki914.zafiro.settings.model.RuntimeToolValidation

class XRepoRuntimeGateway(
    private val repo: XRepo = XRepo,
) : RuntimeSettingsGateway {
    private companion object {
        const val LOG_TAG = "niki914_zafiro_XRepoRuntimeGateway"
        const val SOURCE_USER = "user"
        const val SOURCE_PROVIDER = "provider"
        const val SOURCE_OPENROUTER = "openrouter"
        const val SOURCE_DEFAULT = "default"
    }

    override suspend fun readLlmConfig(agentId: String, configId: String?): RuntimeLlmConfig {
        val doc = repo.llmConfigs.document()
        val target = configId?.takeIf { it.isNotBlank() }?.let { id ->
            doc.configs.firstOrNull { it.id == id } ?: run {
                Logger.w(LOG_TAG, "Config id=$id not found, falling back to active config")
                null
            }
        }
        val active = target ?: doc.activeConfig()
        val memories = repo.agents.memoriesFor(agentId)

        // 四级解析链（全查本地，零网络）：用户 > provider /models > OpenRouter > 兜底。
        // 逐级按需：用户填了就完全不碰两个缓存 store，缺哪层才读哪层。
        suspend fun providerFacts(): CloudModelFacts? {
            return repo.cloudFacts.providerFacts(active?.endpoint, active?.model)
        }

        suspend fun openRouterFacts(): CloudModelFacts? {
            return OpenRouterCatalogApi.match(
                repo.cloudFacts.openRouter().models,
                active?.model.orEmpty()
            )
        }

        val maxTokensLimit = resolveTokenCount(
            userText = active?.maxTokensText,
            providerValue = { providerFacts()?.maxOutputTokens },
            openRouterValue = { openRouterFacts()?.maxOutputTokens },
            fallback = DEFAULT_MAX_TOKENS.toLong(),
        )
        val contextWindowLimit = resolveTokenCount(
            userText = active?.contextWindowText,
            providerValue = { providerFacts()?.windowTokens },
            openRouterValue = { openRouterFacts()?.windowTokens },
            fallback = DEFAULT_CONTEXT_WINDOW_TOKENS,
        )
        val maxTokens = maxTokensLimit.first.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        // 取值来源必须打进日志：UI 不展示来源，而云端值常常与默认值接近（1M vs 1048576），
        // 只看数字无法判断哪一层赢。
        Logger.i(
            LOG_TAG,
            "token limits model=${active?.model} " +
                    "maxTokens=$maxTokens(${maxTokensLimit.second}) " +
                    "contextWindow=${contextWindowLimit.first}(${contextWindowLimit.second})"
        )
        return RuntimeLlmConfig(
            provider = active?.provider.orEmpty(),
            endpoint = active?.endpoint.orEmpty(),
            apiKey = active?.apiKey.orEmpty(),
            model = active?.model.orEmpty(),
            protocol = active?.protocol.orEmpty(),
            supportsImages = active?.supportsImages ?: false,
            proxy = active?.proxy.orEmpty(),
            thinkingLevel = active?.thinkingLevel.orEmpty(),
            prompt = doc.prompt,
            memories = memories,
            idleTimeoutSeconds = repo.llmIdleTimeoutSeconds().takeIf { it > 0L },
            retryMaxAttempts = repo.llmRetryMaxAttempts(),
            repetitionSensitivity = repo.repetitionSensitivity(),
            compactionThresholdPercent = repo.contextCompactionThresholdPercent(),
            // 空串/旧格式的 0 = 未设置：用户 > 云端 > 默认
            maxTokens = maxTokens,
            contextWindowTokens = contextWindowLimit.first,
        )
    }

    /** 用户 > provider /models > OpenRouter > fallback；空串/0/负值一律视为未设置。
     *  返回「值 to 取值来源」，来源只用于日志。 */
    private suspend fun resolveTokenCount(
        userText: String?,
        providerValue: suspend () -> Long?,
        openRouterValue: suspend () -> Long?,
        fallback: Long,
    ): Pair<Long, String> {
        userText?.let(TokenCountText::parse)?.takeIf { it > 0L }
            ?.let { return it to SOURCE_USER }
        providerValue()?.takeIf { it > 0L }?.let { return it to SOURCE_PROVIDER }
        openRouterValue()?.takeIf { it > 0L }?.let { return it to SOURCE_OPENROUTER }
        return fallback to SOURCE_DEFAULT
    }

    override suspend fun listMcpServers(): List<RuntimeMcpServer> = repo.mcp.list()

    override suspend fun listEnabledSkills(): List<RuntimeSkillMetadata> {
        return repo.skills.listEnabled()
    }

    override suspend fun loadSkill(id: String): RuntimeLoadedSkill? {
        return repo.skills.getDetail(id)
    }

    override suspend fun addMemory(value: String) {
        repo.memory.add(value)
    }

    override suspend fun removeMemory(oldText: String): MemoryMutationResult {
        return repo.memory.removeByText(oldText)
    }

    override suspend fun replaceMemory(oldText: String, content: String): MemoryMutationResult {
        return repo.memory.replaceByText(oldText, content)
    }

    override suspend fun listCustomPyTools(): List<RuntimeCustomPyTool> = repo.customPyTools.list()

    override suspend fun saveCustomPyTool(
        tool: RuntimeCustomPyTool,
        overwrite: Boolean,
    ): RuntimeToolValidation? {
        return repo.customPyTools.save(tool, overwrite)
    }

    override suspend fun deleteCustomPyTool(name: String) {
        repo.customPyTools.delete(name)
    }

    override suspend fun setCustomPyToolEnabled(name: String, enabled: Boolean) {
        repo.customPyTools.setEnabled(name, enabled)
    }

    override suspend fun listBuiltinToolSettings(): List<RuntimeBuiltinToolSetting> {
        return repo.builtinTools.list()
    }

    override suspend fun setBuiltinToolEnabled(
        name: String,
        enabled: Boolean,
    ): RuntimeToolValidation? {
        return repo.builtinTools.setEnabled(name, enabled)
    }

    override suspend fun setBuiltinToolGroupEnabled(
        groupId: String,
        enabled: Boolean,
    ): RuntimeToolValidation? {
        return repo.builtinTools.setGroupEnabled(groupId, enabled)
    }

    override suspend fun listExecutionRules(): List<RuntimeExecutionRule> {
        return repo.executionRules.list()
    }
}
