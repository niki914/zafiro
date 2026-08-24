package com.niki914.nexus.agentic.repo

import android.content.Context
import com.niki914.logging.Logger
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolRegistry
import com.niki914.nexus.agentic.chat.agentic.shell.ShellCommandSafetyPolicy
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeTakeoverTarget
import com.niki914.nexus.agentic.runtime.settings.model.TAKEOVER_FIELD_NAME
import com.niki914.nexus.agentic.runtime.settings.model.TAKEOVER_FIELD_PATTERNS
import com.niki914.nexus.store.StoreDescriptorRegistry
import com.niki914.nexus.xposed.api.util.ContextProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.niki914.nexus.agentic.runtime.settings.MemoryMutationResult
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeAgentMemoryMode as AgentMemoryMode
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeAgentProfile as AgentProfile
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeAgentValidation as AgentValidation
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeBuiltinToolSetting as BuiltinToolSetting
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeCustomTool as CustomTool
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeCustomToolValidation as CustomToolValidation
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeExecutionRule as ExecutionRule
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeExecutionRuleEnabledMode as ExecutionRuleEnabledMode
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeLlmConfig as LlmConfig
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeMcpServer as McpServer
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeTakeoverRule as TakeoverRule
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeTakeoverRuleValidation as TakeoverRuleValidation

object XRepo {
    private const val LOG_TAG = "niki914_nexus_XRepo"

    val mcp: McpApi = McpApi(this)
    val customTools: CustomToolApi = CustomToolApi(this)
    val builtinTools: BuiltinToolApi = BuiltinToolApi(this)
    val memory: MemoryApi = MemoryApi(this)
    val web: WebSettingsApi = WebSettingsApi(this)
    val executionRules: ExecutionRulesApi = ExecutionRulesApi(this)
    val takeoverRules: TakeoverRulesApi = TakeoverRulesApi(this)
    val agents: AgentApi = AgentApi(this)
    val skills: SkillApi = SkillApi(this)

    private val writeMutex = Mutex()
    private var appContext: Context? = null
    private var installedStoreForTest = false
    internal var store: DomainSettingsStore = XIpcDomainSettingsStore(null)
        private set

    internal fun init(
        context: Context,
        store: DomainSettingsStore = XIpcDomainSettingsStore(null)
    ) {
        if (appContext == null) {
            appContext = context.applicationContext ?: context
            if (!installedStoreForTest) {
                this.store = store
            }
        }
    }

    internal fun installStoreForTest(store: DomainSettingsStore) {
        this.store = store
        installedStoreForTest = true
    }

    internal fun resetForTest() {
        appContext = null
        store = XIpcDomainSettingsStore(null)
        installedStoreForTest = false
    }

    internal suspend fun context(): Context {
        appContext?.let { return it }
        val context = ContextProvider.await()
        init(context)
        return appContext ?: context
    }

    internal suspend fun readJson(storeId: String): String {
        val startedAtMs = System.currentTimeMillis()
        val json = store.readJson(context(), storeId)
        Logger.d(
            LOG_TAG,
            "readJson storeId=$storeId jsonLength=${json.length} " +
                "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
        )
        return json
    }

    internal suspend fun writeJson(storeId: String, json: String): Boolean {
        val startedAtMs = System.currentTimeMillis()
        val result = writeMutex.withLock {
            writeJsonLocked(context(), storeId, json)
        }
        Logger.i(
            LOG_TAG,
            "writeJson storeId=$storeId result=$result jsonLength=${json.length} " +
                "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
        )
        return result
    }

    internal suspend fun updateJson(storeId: String, transform: (String) -> String): Boolean {
        val startedAtMs = System.currentTimeMillis()
        val result = writeMutex.withLock {
            val context = context()
            val latest = store.readJson(context, storeId)
            writeJsonLocked(context, storeId, transform(latest))
        }
        Logger.d(
            LOG_TAG,
            "updateJson storeId=$storeId result=$result " +
                "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
        )
        return result
    }

    internal suspend fun updateJsonOrFalse(
        storeId: String,
        transform: (String) -> String?
    ): Boolean {
        val startedAtMs = System.currentTimeMillis()
        val result = writeMutex.withLock {
            val context = context()
            val latest = store.readJson(context, storeId)
            val updated = transform(latest) ?: return@withLock false
            writeJsonLocked(context, storeId, updated)
        }
        Logger.d(
            LOG_TAG,
            "updateJsonOrFalse storeId=$storeId result=$result " +
                "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
        )
        return result
    }

    private suspend fun writeJsonLocked(context: Context, storeId: String, json: String): Boolean {
        check(store.writeJsonFromOwner(context, storeId, json)) {
            "Failed to write settings store: $storeId"
        }
        return true
    }

    suspend fun tryPutDefaultSettings(): Boolean {
        val startedAtMs = System.currentTimeMillis()
        val result = writeMutex.withLock {
            val context = context()
            val appState = AppStateSettingsCodec.parse(
                store.readJson(
                    context,
                    StoreDescriptorRegistry.APP_STATE_ID
                )
            )
            if (appState.onboardingCompleted) {
                return@withLock false
            }
            writeJsonLocked(
                context,
                StoreDescriptorRegistry.AGENT_MAIN_CONFIG_ID,
                AgentSettingsCodec.encodeMainConfig(
                    LlmConfig(prompt = LocalSettingsDefaults.DEFAULT_SYSTEM_PROMPT.trimIndent())
                ),
            )
            writeJsonLocked(
                context,
                StoreDescriptorRegistry.AGENT_MAIN_MEMORY_ID,
                MemorySettingsCodec.encodeMemories(
                    LocalSettingsDefaults.defaultMemories,
                    System.currentTimeMillis()
                ),
            )
            writeJsonLocked(
                context,
                StoreDescriptorRegistry.AGENT_REGISTRY_ID,
                AgentSettingsCodec.encodeRegistry(listOf(defaultMainAgentProfile(System.currentTimeMillis()))),
            )
            writeJsonLocked(
                context,
                StoreDescriptorRegistry.TOOLS_CUSTOM_ID,
                ToolSettingsCodec.encodeCustomTools(listOf(DEFAULT_WECHAT_TOOL)),
            )
            writeJsonLocked(
                context,
                StoreDescriptorRegistry.RULES_EXECUTION_ID,
                RuleSettingsCodec.encodeExecutionRules(LocalSettingsDefaults.defaultExecutionRules),
            )
            true
        }
        Logger.i(
            LOG_TAG,
            "tryPutDefaultSettings result=$result " +
                "reason=${if (result) "initialized" else "alreadyOnboarded"} " +
                "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
        )
        return result
    }

    suspend fun onboardingCompleted(): Boolean {
        return AppStateSettingsCodec.parse(readJson(StoreDescriptorRegistry.APP_STATE_ID)).onboardingCompleted
    }

    suspend fun setOnboardingCompleted(value: Boolean) {
        val startedAtMs = System.currentTimeMillis()
        updateJson(StoreDescriptorRegistry.APP_STATE_ID) { json ->
            val current = AppStateSettingsCodec.parse(json)
            AppStateSettingsCodec.encode(current.copy(onboardingCompleted = value))
        }
        Logger.i(
            LOG_TAG,
            "setOnboardingCompleted value=$value " +
                "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
        )
    }

    suspend fun lastOpenedConversationId(): String {
        val startedAtMs = System.currentTimeMillis()
        return AppStateSettingsCodec.parse(readJson(StoreDescriptorRegistry.APP_STATE_ID))
            .lastOpenedConversationId
            .also { id ->
                Logger.d(
                    LOG_TAG,
                    "lastOpenedConversationId value=$id " +
                        "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
                )
            }
    }

    suspend fun setLastOpenedConversationId(value: String) {
        val startedAtMs = System.currentTimeMillis()
        val trimmed = value.trim()
        updateJson(StoreDescriptorRegistry.APP_STATE_ID) { json ->
            val current = AppStateSettingsCodec.parse(json)
            AppStateSettingsCodec.encode(current.copy(lastOpenedConversationId = trimmed))
        }
        Logger.d(
            LOG_TAG,
            "setLastOpenedConversationId value=$trimmed " +
                "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
        )
    }

    suspend fun llm(): LlmConfig {
        return agents.llm(StoreDescriptorRegistry.MAIN_AGENT_ID)
    }

    suspend fun saveLlmAccess(
        provider: String,
        endpoint: String,
        model: String,
        apiKey: String,
    ) {
        val updated = llm().copy(
            provider = provider,
            endpoint = endpoint,
            model = model,
            apiKey = apiKey,
        )
        saveLlm(updated)
    }

    suspend fun saveLlm(config: LlmConfig) {
        agents.saveLlm(StoreDescriptorRegistry.MAIN_AGENT_ID, config)?.let { validation ->
            throw IllegalArgumentException("${validation.field}: ${validation.message}")
        }
    }

    private val DEFAULT_WECHAT_TOOL = CustomTool(
        name = "launch_wechat",
        description = "启动微信",
        command = "am start -n com.tencent.mm/com.tencent.mm.ui.LauncherUI",
    )

    private fun defaultMainAgentProfile(nowMillis: Long): AgentProfile {
        return AgentProfile(
            id = StoreDescriptorRegistry.MAIN_AGENT_ID,
            name = "Main",
            alias = StoreDescriptorRegistry.MAIN_AGENT_ID,
            enabled = true,
            order = 0,
            memoryMode = AgentMemoryMode.SharedMain,
            createdAt = nowMillis,
            updatedAt = nowMillis,
        )
    }
}

class AgentApi internal constructor(
    private val repo: XRepo,
) {
    suspend fun list(): List<AgentProfile> {
        return AgentSettingsCodec.parseRegistry(repo.readJson(StoreDescriptorRegistry.AGENT_REGISTRY_ID))
    }

    suspend fun get(agentId: String): AgentProfile? {
        val normalizedId = AgentSettingsCodec.normalizeAgentId(agentId) ?: return null
        return list().firstOrNull { it.id == normalizedId }
    }

    suspend fun saveProfile(profile: AgentProfile, overwrite: Boolean = true): AgentValidation? {
        val normalizedId = AgentSettingsCodec.normalizeAgentId(profile.id)
            ?: return AgentValidation("id", "Invalid agent id.")
        val normalizedAlias = AgentSettingsCodec.normalizeAlias(profile.alias)
            ?: return AgentValidation("alias", "Invalid alias.")
        val normalizedName = profile.name.trim()
        if (normalizedName.isBlank()) {
            return AgentValidation("name", "Required field 'name' is missing.")
        }
        if (normalizedId == StoreDescriptorRegistry.MAIN_AGENT_ID && !profile.enabled) {
            return AgentValidation("enabled", "Main agent cannot be disabled.")
        }

        val nowMillis = System.currentTimeMillis()
        val current = list()
        val existing = current.firstOrNull { it.id == normalizedId }
        if (!overwrite && existing != null) {
            return AgentValidation("id", "Already exists in agents.")
        }
        if (current.any { it.id != normalizedId && it.alias == normalizedAlias }) {
            return AgentValidation("alias", "Already exists in agents.")
        }

        val normalized = profile.copy(
            id = normalizedId,
            name = normalizedName,
            alias = normalizedAlias,
            createdAt = profile.createdAt.takeIf { it > 0L } ?: existing?.createdAt ?: nowMillis,
            updatedAt = nowMillis,
        )
        val updated = if (existing == null) {
            current + normalized
        } else {
            current.map { if (it.id == normalizedId) normalized else it }
        }
        repo.writeJson(
            StoreDescriptorRegistry.AGENT_REGISTRY_ID,
            AgentSettingsCodec.encodeRegistry(updated)
        )
        return null
    }

    suspend fun setEnabled(agentId: String, enabled: Boolean): AgentValidation? {
        val normalizedId = AgentSettingsCodec.normalizeAgentId(agentId)
            ?: return AgentValidation("id", "Invalid agent id.")
        if (normalizedId == StoreDescriptorRegistry.MAIN_AGENT_ID && !enabled) {
            return AgentValidation("enabled", "Main agent cannot be disabled.")
        }
        val current = list()
        if (current.none { it.id == normalizedId }) {
            return AgentValidation("id", "Agent does not exist.")
        }
        val nowMillis = System.currentTimeMillis()
        repo.writeJson(
            StoreDescriptorRegistry.AGENT_REGISTRY_ID,
            AgentSettingsCodec.encodeRegistry(
                current.map { profile ->
                    if (profile.id == normalizedId) {
                        profile.copy(enabled = enabled, updatedAt = nowMillis)
                    } else {
                        profile
                    }
                }
            ),
        )
        return null
    }

    suspend fun llm(agentId: String = StoreDescriptorRegistry.MAIN_AGENT_ID): LlmConfig {
        val normalizedId = AgentSettingsCodec.normalizeAgentId(agentId) ?: return LlmConfig()
        if (normalizedId == StoreDescriptorRegistry.MAIN_AGENT_ID) {
            return AgentSettingsCodec.parseMainConfig(repo.readJson(StoreDescriptorRegistry.AGENT_MAIN_CONFIG_ID))
        }
        if (enabledProfile(normalizedId) == null) {
            return LlmConfig()
        }
        val storeId = StoreDescriptorRegistry.agentConfigStoreId(normalizedId) ?: return LlmConfig()
        return AgentSettingsCodec.parseConfig(repo.readJson(storeId))
    }

    suspend fun saveLlm(agentId: String, config: LlmConfig): AgentValidation? {
        val normalizedId = AgentSettingsCodec.normalizeAgentId(agentId)
            ?: return AgentValidation("id", "Invalid agent id.")
        if (normalizedId == StoreDescriptorRegistry.MAIN_AGENT_ID) {
            repo.writeJson(
                StoreDescriptorRegistry.AGENT_MAIN_CONFIG_ID,
                AgentSettingsCodec.encodeMainConfig(config)
            )
            return null
        }
        val profile = get(normalizedId) ?: return AgentValidation("id", "Agent does not exist.")
        if (!profile.enabled) {
            return AgentValidation("enabled", "Agent is disabled.")
        }
        val storeId = StoreDescriptorRegistry.agentConfigStoreId(normalizedId)
            ?: return AgentValidation("id", "Invalid agent id.")
        repo.writeJson(storeId, AgentSettingsCodec.encodeConfig(normalizedId, config))
        return null
    }

    suspend fun memoriesFor(agentId: String): List<String> {
        val normalizedId = AgentSettingsCodec.normalizeAgentId(agentId) ?: return emptyList()
        if (normalizedId == StoreDescriptorRegistry.MAIN_AGENT_ID) {
            return repo.memory.list()
        }
        return when (enabledProfile(normalizedId)?.memoryMode) {
            AgentMemoryMode.SharedMain -> repo.memory.list()
            AgentMemoryMode.Disabled,
            null -> emptyList()
        }
    }

    private suspend fun enabledProfile(agentId: String): AgentProfile? {
        return get(agentId)?.takeIf { it.enabled }
    }
}

class MemoryApi internal constructor(
    private val repo: XRepo,
) {
    suspend fun list(): List<String> {
        return MemorySettingsCodec.parseMemories(repo.readJson(StoreDescriptorRegistry.AGENT_MAIN_MEMORY_ID))
    }

    suspend fun replaceAll(memories: List<String>) {
        writeMemories(normalizeMemories(memories))
    }

    suspend fun add(value: String) {
        val normalizedValue = value.trim()
        if (normalizedValue.isBlank()) return
        repo.updateJsonOrFalse(StoreDescriptorRegistry.AGENT_MAIN_MEMORY_ID) { json ->
            val current = MemorySettingsCodec.parseMemories(json)
            if (normalizedValue in current) return@updateJsonOrFalse null
            MemorySettingsCodec.encodeMemories(current + normalizedValue, System.currentTimeMillis())
        }
    }

    suspend fun update(index: Int, value: String) {
        val normalizedValue = value.trim()
        repo.updateJsonOrFalse(StoreDescriptorRegistry.AGENT_MAIN_MEMORY_ID) { json ->
            val current = MemorySettingsCodec.parseMemories(json)
            if (index !in current.indices) return@updateJsonOrFalse null
            val updated = if (normalizedValue.isBlank()) {
                current.filterIndexed { i, _ -> i != index }
            } else {
                current.mapIndexed { i, item -> if (i == index) normalizedValue else item }
            }
            MemorySettingsCodec.encodeMemories(updated, System.currentTimeMillis())
        }
    }

    suspend fun delete(index: Int) {
        repo.updateJsonOrFalse(StoreDescriptorRegistry.AGENT_MAIN_MEMORY_ID) { json ->
            val current = MemorySettingsCodec.parseMemories(json)
            if (index !in current.indices) return@updateJsonOrFalse null
            MemorySettingsCodec.encodeMemories(
                current.filterIndexed { i, _ -> i != index },
                System.currentTimeMillis(),
            )
        }
    }

    suspend fun removeByText(oldText: String): MemoryMutationResult {
        var result: MemoryMutationResult = MemoryMutationResult.NotFound
        repo.updateJsonOrFalse(StoreDescriptorRegistry.AGENT_MAIN_MEMORY_ID) { json ->
            val current = MemorySettingsCodec.parseMemories(json)
            val matches = findMatches(current, oldText)
            when {
                matches.isEmpty() -> {
                    result = MemoryMutationResult.NotFound
                    null
                }
                hasMultipleDistinct(matches) -> {
                    result = MemoryMutationResult.Ambiguous
                    null
                }
                else -> {
                    result = MemoryMutationResult.Ok
                    MemorySettingsCodec.encodeMemories(
                        current.filterIndexed { index, _ -> index != matches.first().index },
                        System.currentTimeMillis(),
                    )
                }
            }
        }
        return result
    }

    suspend fun replaceByText(oldText: String, newContent: String): MemoryMutationResult {
        val normalizedContent = newContent.trim()
        if (normalizedContent.isBlank()) return MemoryMutationResult.NotFound
        var result: MemoryMutationResult = MemoryMutationResult.NotFound
        repo.updateJsonOrFalse(StoreDescriptorRegistry.AGENT_MAIN_MEMORY_ID) { json ->
            val current = MemorySettingsCodec.parseMemories(json)
            val matches = findMatches(current, oldText)
            when {
                matches.isEmpty() -> {
                    result = MemoryMutationResult.NotFound
                    null
                }
                hasMultipleDistinct(matches) -> {
                    result = MemoryMutationResult.Ambiguous
                    null
                }
                else -> {
                    result = MemoryMutationResult.Ok
                    val matchedIndex = matches.first().index
                    MemorySettingsCodec.encodeMemories(
                        current.mapIndexed { index, item ->
                            if (index == matchedIndex) normalizedContent else item
                        },
                        System.currentTimeMillis(),
                    )
                }
            }
        }
        return result
    }

    private fun findMatches(
        entries: List<String>,
        oldText: String,
    ): List<IndexedEntry> {
        return entries.mapIndexedNotNull { index, entry ->
            if (oldText in entry) IndexedEntry(index, entry) else null
        }
    }

    private fun hasMultipleDistinct(matches: List<IndexedEntry>): Boolean {
        return matches.size > 1 && matches.map { it.content }.distinct().size > 1
    }

    private data class IndexedEntry(val index: Int, val content: String)

    private suspend fun writeMemories(memories: List<String>) {
        repo.writeJson(
            StoreDescriptorRegistry.AGENT_MAIN_MEMORY_ID,
            MemorySettingsCodec.encodeMemories(memories, System.currentTimeMillis()),
        )
    }

    private fun normalizeMemories(memories: List<String>): List<String> {
        return memories.map(String::trim).filter(String::isNotBlank)
    }
}

class ExecutionRulesApi internal constructor(
    private val repo: XRepo,
) {
    suspend fun list(): List<ExecutionRule> {
        val json = repo.readJson(StoreDescriptorRegistry.RULES_EXECUTION_ID)
        if (json == """{"rules":[]}""") {
            return LocalSettingsDefaults.defaultExecutionRules
        }
        return RuleSettingsCodec.parseExecutionRules(json)
    }

    suspend fun get(id: String): ExecutionRule? {
        return list().firstOrNull { it.id == id }
    }

    suspend fun save(rule: ExecutionRule) {
        repo.updateJson(StoreDescriptorRegistry.RULES_EXECUTION_ID) { json ->
            val rules = RuleSettingsCodec.parseExecutionRules(json)
            val updated = if (rules.any { it.id == rule.id }) {
                rules.map { if (it.id == rule.id) rule else it }
            } else {
                rules + rule
            }
            encodeExecutionRulesForWrite(updated)
        }
    }

    suspend fun replace(previousId: String?, rule: ExecutionRule) {
        repo.updateJson(StoreDescriptorRegistry.RULES_EXECUTION_ID) { json ->
            val rules = RuleSettingsCodec.parseExecutionRules(json)
            val withoutPrevious = if (previousId != null && previousId != rule.id) {
                rules.filterNot { it.id == previousId }
            } else {
                rules
            }
            val updated = if (withoutPrevious.any { it.id == rule.id }) {
                withoutPrevious.map { if (it.id == rule.id) rule else it }
            } else {
                withoutPrevious + rule
            }
            encodeExecutionRulesForWrite(updated)
        }
    }

    suspend fun delete(id: String) {
        repo.updateJson(StoreDescriptorRegistry.RULES_EXECUTION_ID) { json ->
            encodeExecutionRulesForWrite(
                RuleSettingsCodec.parseExecutionRules(json).filterNot { it.id == id },
            )
        }
    }

    suspend fun setEnabledMode(id: String, enabledMode: ExecutionRuleEnabledMode) {
        repo.updateJson(StoreDescriptorRegistry.RULES_EXECUTION_ID) { json ->
            encodeExecutionRulesForWrite(
                RuleSettingsCodec.parseExecutionRules(json).map { rule ->
                    if (rule.id == id) rule.copy(enabledMode = enabledMode) else rule
                },
            )
        }
    }

    private fun encodeExecutionRulesForWrite(rules: List<ExecutionRule>): String {
        return if (rules.isEmpty()) {
            EXPLICIT_EMPTY_RULES_JSON
        } else {
            RuleSettingsCodec.encodeExecutionRules(rules)
        }
    }

    private companion object {
        private const val EXPLICIT_EMPTY_RULES_JSON = """{"rules":[],"_explicit":true}"""
    }
}

class TakeoverRulesApi internal constructor(
    private val repo: XRepo,
) {
    private companion object {
        const val LOG_TAG = "niki914_nexus_TakeoverRules"
    }

    suspend fun list(): List<TakeoverRule> {
        val startedAtMs = System.currentTimeMillis()
        return RuleSettingsCodec.parseTakeoverRules(
            repo.readJson(StoreDescriptorRegistry.RULES_TAKEOVER_ID)
        ).also { rules ->
            Logger.d(
                LOG_TAG,
                "list count=${rules.size} " +
                    "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
            )
        }
    }

    suspend fun get(id: String): TakeoverRule? {
        return list().firstOrNull { it.id == id }
    }

    suspend fun getDefaultTarget(): RuntimeTakeoverTarget {
        val startedAtMs = System.currentTimeMillis()
        return RuleSettingsCodec.parseTakeoverSettings(
            repo.readJson(StoreDescriptorRegistry.RULES_TAKEOVER_ID)
        ).defaultTarget.also { target ->
            Logger.d(
                LOG_TAG,
                "default target=$target elapsedMs=${System.currentTimeMillis() - startedAtMs}"
            )
        }
    }

    suspend fun setDefaultTarget(target: RuntimeTakeoverTarget) {
        repo.updateJson(StoreDescriptorRegistry.RULES_TAKEOVER_ID) { json ->
            val settings = RuleSettingsCodec.parseTakeoverSettings(json)
            RuleSettingsCodec.encodeTakeoverSettings(settings.copy(defaultTarget = target))
        }
    }

    suspend fun replace(previousId: String?, rule: TakeoverRule) {
        repo.updateJson(StoreDescriptorRegistry.RULES_TAKEOVER_ID) { json ->
            val settings = RuleSettingsCodec.parseTakeoverSettings(json)
            val rules = settings.rules
            val withoutPrevious = if (previousId != null && previousId != rule.id) {
                rules.filterNot { it.id == previousId }
            } else {
                rules
            }
            val updated = if (withoutPrevious.any { it.id == rule.id }) {
                withoutPrevious.map { if (it.id == rule.id) rule else it }
            } else {
                withoutPrevious + rule
            }
            RuleSettingsCodec.encodeTakeoverSettings(settings.copy(rules = updated))
        }
    }

    suspend fun delete(id: String) {
        repo.updateJson(StoreDescriptorRegistry.RULES_TAKEOVER_ID) { json ->
            val settings = RuleSettingsCodec.parseTakeoverSettings(json)
            RuleSettingsCodec.encodeTakeoverSettings(
                settings.copy(rules = settings.rules.filterNot { it.id == id })
            )
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        repo.updateJson(StoreDescriptorRegistry.RULES_TAKEOVER_ID) { json ->
            val settings = RuleSettingsCodec.parseTakeoverSettings(json)
            RuleSettingsCodec.encodeTakeoverSettings(
                settings.copy(
                    rules = settings.rules.map { rule ->
                        if (rule.id == id) rule.copy(enabled = enabled) else rule
                    }
                )
            )
        }
    }

    fun validate(rule: TakeoverRule): List<TakeoverRuleValidation> {
        val errors = mutableListOf<TakeoverRuleValidation>()
        if (rule.name.trim().isBlank()) {
            errors += TakeoverRuleValidation(
                field = TAKEOVER_FIELD_NAME,
                message = "Required field 'name' is missing.",
            )
        }
        if (rule.patterns.map(String::trim).filter(String::isNotBlank).isEmpty()) {
            errors += TakeoverRuleValidation(
                field = TAKEOVER_FIELD_PATTERNS,
                message = "At least one takeover pattern is required.",
            )
        }
        return errors
    }
}

class McpApi internal constructor(
    private val repo: XRepo,
) {
    suspend fun list(): List<McpServer> {
        return McpSettingsCodec.parseServers(repo.readJson(StoreDescriptorRegistry.TOOLS_MCP_SERVERS_ID))
    }

    suspend fun get(name: String): McpServer? {
        return list().firstOrNull { it.name == name }
    }

    suspend fun save(server: McpServer) {
        repo.updateJson(StoreDescriptorRegistry.TOOLS_MCP_SERVERS_ID) { json ->
            val servers = McpSettingsCodec.parseServers(json)
            val updated = if (servers.any { it.name == server.name }) {
                servers.map { if (it.name == server.name) server else it }
            } else {
                servers + server
            }
            McpSettingsCodec.encodeServers(updated)
        }
    }

    suspend fun replace(previousName: String?, server: McpServer) {
        repo.updateJson(StoreDescriptorRegistry.TOOLS_MCP_SERVERS_ID) { json ->
            val servers = McpSettingsCodec.parseServers(json)
            val withoutPrevious = if (previousName != null && previousName != server.name) {
                servers.filterNot { it.name == previousName }
            } else {
                servers
            }
            val updated = if (withoutPrevious.any { it.name == server.name }) {
                withoutPrevious.map { if (it.name == server.name) server else it }
            } else {
                withoutPrevious + server
            }
            McpSettingsCodec.encodeServers(updated)
        }
    }

    suspend fun delete(name: String) {
        repo.updateJson(StoreDescriptorRegistry.TOOLS_MCP_SERVERS_ID) { json ->
            McpSettingsCodec.encodeServers(
                McpSettingsCodec.parseServers(json).filterNot { it.name == name })
        }
    }

    suspend fun setEnabled(name: String, enabled: Boolean) {
        repo.updateJson(StoreDescriptorRegistry.TOOLS_MCP_SERVERS_ID) { json ->
            McpSettingsCodec.encodeServers(
                McpSettingsCodec.parseServers(json).map { server ->
                    if (server.name == name) server.copy(enabled = enabled) else server
                },
            )
        }
    }
}

class CustomToolApi internal constructor(
    private val repo: XRepo,
    private val safetyPolicy: ShellCommandSafetyPolicy = ShellCommandSafetyPolicy(
        listExecutionRules = { repo.executionRules.list() },
    ),
    private val builtinToolRegistry: BuiltinToolRegistry = BuiltinToolRegistry.default(),
) {
    suspend fun list(): List<CustomTool> {
        return ToolSettingsCodec.parseCustomTools(repo.readJson(StoreDescriptorRegistry.TOOLS_CUSTOM_ID))
    }

    suspend fun get(name: String): CustomTool? {
        return list().firstOrNull { it.name == name }
    }

    suspend fun save(tool: CustomTool, overwrite: Boolean = true): CustomToolValidation? {
        validate(tool, overwrite)?.let { return it }
        val normalized = tool.normalized()
        repo.updateJson(StoreDescriptorRegistry.TOOLS_CUSTOM_ID) { json ->
            val tools = ToolSettingsCodec.parseCustomTools(json)
            val updated = if (tools.any { it.name == normalized.name }) {
                tools.map { if (it.name == normalized.name) normalized else it }
            } else {
                tools + normalized
            }
            ToolSettingsCodec.encodeCustomTools(updated)
        }
        return null
    }

    suspend fun replace(previousName: String?, tool: CustomTool): CustomToolValidation? {
        validate(tool, overwrite = true)?.let { return it }
        val normalized = tool.normalized()
        val existingTools = list()
        val withoutPrevious = if (previousName != null) {
            existingTools.filterNot { it.name == previousName }
        } else {
            existingTools
        }
        if (withoutPrevious.any { it.name == normalized.name }) {
            return CustomToolValidation("name", "Already exists in custom_tools.")
        }
        repo.updateJson(StoreDescriptorRegistry.TOOLS_CUSTOM_ID) { json ->
            val tools = ToolSettingsCodec.parseCustomTools(json)
            val withoutPrevious = previousName
                ?.let { name -> tools.filterNot { it.name == name } }
                ?: tools
            val updated = withoutPrevious + normalized
            ToolSettingsCodec.encodeCustomTools(updated)
        }
        return null
    }

    suspend fun replaceAll(tools: List<CustomTool>): CustomToolValidation? {
        val normalizedTools = tools.map { it.normalized() }
        val duplicateName = normalizedTools
            .groupingBy { it.name }
            .eachCount()
            .entries
            .firstOrNull { it.value > 1 }
            ?.key
        if (duplicateName != null) {
            return CustomToolValidation("name", "Duplicate name in custom_tools.")
        }
        normalizedTools.forEach { tool ->
            validate(tool, overwrite = true)?.let { return it }
        }
        repo.writeJson(
            StoreDescriptorRegistry.TOOLS_CUSTOM_ID,
            ToolSettingsCodec.encodeCustomTools(normalizedTools)
        )
        return null
    }

    suspend fun delete(name: String) {
        repo.updateJson(StoreDescriptorRegistry.TOOLS_CUSTOM_ID) { json ->
            ToolSettingsCodec.encodeCustomTools(
                ToolSettingsCodec.parseCustomTools(json).filterNot { it.name == name })
        }
    }

    suspend fun setEnabled(name: String, enabled: Boolean) {
        repo.updateJson(StoreDescriptorRegistry.TOOLS_CUSTOM_ID) { json ->
            ToolSettingsCodec.encodeCustomTools(
                ToolSettingsCodec.parseCustomTools(json).map { tool ->
                    if (tool.name == name) tool.copy(enabled = enabled) else tool
                },
            )
        }
    }

    suspend fun validate(tool: CustomTool, overwrite: Boolean = true): CustomToolValidation? {
        val normalized = tool.normalized()
        if (normalized.name.isBlank()) {
            return CustomToolValidation("name", "Required field 'name' is missing.")
        }
        if (!NAME_PATTERN.matches(normalized.name)) {
            return CustomToolValidation(
                field = "name",
                message = "Name must start with a letter or underscore and contain only letters, digits, or underscores.",
            )
        }
        if (normalized.description.isBlank()) {
            return CustomToolValidation("description", "Required field 'description' is missing.")
        }
        if (normalized.command.isBlank()) {
            return CustomToolValidation("command", "Required field 'command' is missing.")
        }
        if (normalized.name in builtinToolRegistry.all().map { it.name }.toSet()) {
            return CustomToolValidation("name", "Reserved builtin tool name.")
        }
        val decision = safetyPolicy.evaluate(normalized.command)
        if (!decision.allowed) {
            return CustomToolValidation("command", decision.reason)
        }
        if (!overwrite && list().any { it.name == normalized.name }) {
            return CustomToolValidation("name", "Already exists in custom_tools.")
        }
        return null
    }

    private fun CustomTool.normalized(): CustomTool {
        return copy(
            name = name.trim(),
            description = description.trim(),
            command = command.trim(),
        )
    }

    companion object {
        private val NAME_PATTERN = Regex("^[a-zA-Z_][a-zA-Z0-9_]{1,63}$")
    }
}

class BuiltinToolApi internal constructor(
    private val repo: XRepo,
    private val registry: BuiltinToolRegistry = BuiltinToolRegistry.default(),
) {
    suspend fun list(): List<BuiltinToolSetting> {
        val flags = ToolSettingsCodec.parseBuiltinEnabledForAgents(
            repo.readJson(StoreDescriptorRegistry.TOOLS_BUILTIN_ID)
        )
        return registry.all()
            .sortedBy { it.name }
            .map { tool ->
                BuiltinToolSetting(
                    name = tool.name,
                    description = tool.description,
                    enabled = flags.enabledFlagFor(tool.name, tool.defaultEnabled),
                )
            }
    }

    suspend fun enabled(): List<BuiltinToolSetting> {
        return list().filter { it.enabled }
    }

    suspend fun setEnabled(name: String, enabled: Boolean): CustomToolValidation? {
        if (registry.find(name) == null) {
            return CustomToolValidation("name", "Builtin tool is not registered.")
        }
        repo.updateJson(StoreDescriptorRegistry.TOOLS_BUILTIN_ID) { json ->
            val flags = ToolSettingsCodec.parseBuiltinEnabledForAgents(json).toMutableMap()
            flags[name] = enabled
            ToolSettingsCodec.encodeBuiltinEnabledForAgents(flags)
        }
        return null
    }

    private fun Map<String, Boolean>.enabledFlagFor(
        toolName: String,
        defaultEnabled: Boolean
    ): Boolean {
        return this[toolName] ?: defaultEnabled
    }

    private companion object {
        private const val TERMINAL_TOOL_NAME = "terminal"
    }
}
