package com.niki914.zafiro.repo

import android.content.Context
import com.niki914.logging.Logger
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRegistry
import com.niki914.zafiro.chat.agentic.python.PyRuntime
import com.niki914.zafiro.chat.agentic.python.PyToolHarness
import com.niki914.zafiro.chat.agentic.shell.ShellCommandSafetyPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import com.niki914.zafiro.settings.model.RuntimeTakeoverTarget
import com.niki914.zafiro.settings.model.TAKEOVER_FIELD_NAME
import com.niki914.zafiro.settings.model.TAKEOVER_FIELD_PATTERNS
import com.niki914.store.StoreDescriptorRegistry
import com.niki914.xposed.api.util.ContextProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.niki914.zafiro.settings.MemoryMutationResult
import com.niki914.zafiro.settings.model.RuntimeAgentMemoryMode as AgentMemoryMode
import com.niki914.zafiro.settings.model.RuntimeAgentProfile as AgentProfile
import com.niki914.zafiro.settings.model.RuntimeAgentValidation as AgentValidation
import com.niki914.zafiro.settings.model.RuntimeBuiltinToolSetting as BuiltinToolSetting
import com.niki914.zafiro.settings.model.RuntimePyTool as PyTool
import com.niki914.zafiro.settings.model.RuntimeToolValidation as ToolValidation
import com.niki914.zafiro.settings.model.RuntimeExecutionRule as ExecutionRule
import com.niki914.zafiro.settings.model.RuntimeExecutionRuleEnabledMode as ExecutionRuleEnabledMode
import com.niki914.zafiro.settings.model.RuntimeLlmConfig as LlmConfig
import com.niki914.zafiro.settings.model.RuntimeMcpServer as McpServer
import com.niki914.zafiro.settings.model.RuntimeTakeoverRule as TakeoverRule
import com.niki914.zafiro.settings.model.RuntimeTakeoverRuleValidation as TakeoverRuleValidation

object XRepo {
    private const val LOG_TAG = "niki914_nexus_XRepo"

    val mcp: McpApi = McpApi(this)
    val pyTools: PyToolApi = PyToolApi(this)
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
                StoreDescriptorRegistry.TOOLS_PY_ID,
                ToolSettingsCodec.encodePyTools(listOf(DEFAULT_WEB_SEARCH_TOOL)),
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

    // Seed py tool: web search via DuckDuckGo HTML endpoint. Code/schema are the
    // reflection cache written by the same pipeline pytools write uses.
    private val CODE_WEB_SEARCH = """
        import json
        import urllib.parse

        import requests
        from bs4 import BeautifulSoup


        def main(query: str, max_results: int = 8):
            '''Search the web with DuckDuckGo (HTML endpoint). Returns a list of {title, url, snippet}.'''
            resp = requests.post(
                "https://html.duckduckgo.com/html/",
                data={"q": query},
                headers={"User-Agent": "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"},
                timeout=15,
            )
            resp.raise_for_status()
            soup = BeautifulSoup(resp.text, "html.parser")
            results = []
            for item in soup.select("div.result.results_links")[: max_results]:
                link = item.select_one("a.result__a")
                if link is None:
                    continue
                title = link.get_text(strip=True)
                url = _clean_url(link.get("href", ""))
                snippet_el = item.select_one("td.result__snippet") or item.select_one(".result__snippet")
                snippet = snippet_el.get_text(strip=True) if snippet_el else ""
                if title and url:
                    results.append({"title": title, "url": url, "snippet": snippet})
            print(json.dumps(results, ensure_ascii=False))


        def _clean_url(href: str) -> str:
            if href.startswith("//duckduckgo.com/l/") or href.startswith("https://duckduckgo.com/l/"):
                parsed = urllib.parse.urlparse(href if href.startswith("http") else "https:" + href)
                target = urllib.parse.parse_qs(parsed.query).get("uddg", [""])[0]
                return urllib.parse.unquote(target)
            return href
        """.trimIndent()

    private val SCHEMA_WEB_SEARCH =
        """{"type":"object","properties":{"query":{"type":"string"},"max_results":{"type":"integer","description":"default: 8"}},"required":["query"]}"""

    private val DEFAULT_WEB_SEARCH_TOOL = PyTool(
        name = "py_web_search",
        description = "Search the web with DuckDuckGo. Returns a list of {title, url, snippet}.",
        schemaJson = SCHEMA_WEB_SEARCH,
        code = CODE_WEB_SEARCH,
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

class PyToolApi internal constructor(
    private val repo: XRepo,
    private val safetyPolicy: ShellCommandSafetyPolicy = ShellCommandSafetyPolicy(
        listExecutionRules = { repo.executionRules.list() },
    ),
    private val builtinToolRegistry: BuiltinToolRegistry = BuiltinToolRegistry.default(),
) {
    suspend fun list(): List<PyTool> {
        return ToolSettingsCodec.parsePyTools(repo.readJson(StoreDescriptorRegistry.TOOLS_PY_ID))
    }

    suspend fun get(name: String): PyTool? {
        return list().firstOrNull { it.name == name }
    }

    suspend fun save(tool: PyTool, overwrite: Boolean = true): ToolValidation? {
        validate(tool, overwrite)?.let { return it }
        val normalized = tool.normalized()
        repo.updateJson(StoreDescriptorRegistry.TOOLS_PY_ID) { json ->
            val tools = ToolSettingsCodec.parsePyTools(json)
            val updated = if (tools.any { it.name == normalized.name }) {
                tools.map { if (it.name == normalized.name) normalized else it }
            } else {
                tools + normalized
            }
            ToolSettingsCodec.encodePyTools(updated)
        }
        return null
    }

    suspend fun delete(name: String) {
        repo.updateJson(StoreDescriptorRegistry.TOOLS_PY_ID) { json ->
            ToolSettingsCodec.encodePyTools(
                ToolSettingsCodec.parsePyTools(json).filterNot { it.name == name })
        }
    }

    suspend fun setEnabled(name: String, enabled: Boolean) {
        repo.updateJson(StoreDescriptorRegistry.TOOLS_PY_ID) { json ->
            ToolSettingsCodec.encodePyTools(
                ToolSettingsCodec.parsePyTools(json).map { tool ->
                    if (tool.name == name) tool.copy(enabled = enabled) else tool
                },
            )
        }
    }

    /**
     * UI 保存入口：与 pytools write 同管线，先对 code 做签名反射，
     * 用结果回填 description/schemaJson 缓存，再走 validate/save。
     * 反射失败（语法错误、缺 main、注解缺失等）返回 field="code" 的 validation。
     */
    suspend fun saveIntrospected(tool: PyTool): ToolValidation? {
        val introspection = introspectMain(tool.code)
        introspection.error?.let { error -> return ToolValidation("code", error) }
        return save(
            tool.copy(
                description = introspection.description.orEmpty(),
                schemaJson = introspection.schemaJson.orEmpty(),
            ),
        )
    }

    private suspend fun introspectMain(code: String): PyIntrospection {
        val output = try {
            PyRuntime.exec(PyToolHarness.buildIntrospection(code), INTROSPECTION_TIMEOUT_MS)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            return PyIntrospection(error = t.message ?: "Python signature check failed.")
        }
        val json = try {
            Json.parseToJsonElement(output.trim()).jsonObject
        } catch (_: Exception) {
            return PyIntrospection(error = "Unexpected signature check output: ${output.take(200)}")
        }
        json["error"]?.jsonPrimitive?.contentOrNull?.let { type ->
            val line = json["line"]?.jsonPrimitive?.longOrNull
            val message = json["message"]?.jsonPrimitive?.contentOrNull ?: "Invalid tool code."
            return PyIntrospection(error = if (line != null) "$message (line $line)" else message)
        }
        return PyIntrospection(
            description = json["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            schemaJson = json["schema"]?.jsonObject?.toString().orEmpty(),
        )
    }

    suspend fun validate(tool: PyTool, overwrite: Boolean = true): ToolValidation? {
        val normalized = tool.normalized()
        if (!NAME_PATTERN.matches(normalized.name)) {
            return ToolValidation(
                field = "name",
                message = "Name must match py_[a-z][a-z0-9_] (the py_ prefix is added automatically).",
            )
        }
        if (normalized.name.removePrefix(PY_PREFIX) in
            builtinToolRegistry.all().map { it.name }.toSet()
        ) {
            return ToolValidation("name", "Reserved builtin tool name.")
        }
        if (normalized.code.isBlank()) {
            return ToolValidation("code", "Required field 'code' is missing.")
        }
        if (normalized.timeoutMs !in 1_000L..PyTool.MAX_PY_TOOL_TIMEOUT_MS) {
            return ToolValidation("timeout_ms", "Must be between 1000 and 120000.")
        }
        val decision = safetyPolicy.evaluate(normalized.code)
        if (!decision.allowed) {
            return ToolValidation("code", decision.reason)
        }
        if (!overwrite && list().any { it.name == normalized.name }) {
            return ToolValidation("name", "Already exists in py_tools.")
        }
        return null
    }

    private fun PyTool.normalized(): PyTool {
        return copy(
            name = name.trim(),
            code = code.trim(),
            description = description.trim(),
        )
    }

    private data class PyIntrospection(
        val description: String? = null,
        val schemaJson: String? = null,
        val error: String? = null,
    )

    companion object {
        private const val PY_PREFIX = "py_"
        private const val INTROSPECTION_TIMEOUT_MS = 30_000L
        private val NAME_PATTERN = Regex("^py_[a-z][a-z0-9_]{0,63}$")
    }
}

class BuiltinToolApi internal constructor(
    private val repo: XRepo,
    private val registry: BuiltinToolRegistry = BuiltinToolRegistry.default(),
) {
    suspend fun list(): List<BuiltinToolSetting> {
        val flags = ToolSettingsCodec.parseBuiltinEnabled(
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

    suspend fun setEnabled(name: String, enabled: Boolean): ToolValidation? {
        if (registry.find(name) == null) {
            return ToolValidation("name", "Builtin tool is not registered.")
        }
        repo.updateJson(StoreDescriptorRegistry.TOOLS_BUILTIN_ID) { json ->
            val flags = parseKnownFlags(json).toMutableMap()
            flags[name] = enabled
            ToolSettingsCodec.encodeBuiltinEnabled(flags)
        }
        return null
    }

    // 写穿：组内成员在同一闭包内一次原子写，失败无部分提交。新成员无 flag → 回退 defaultEnabled。
    suspend fun setGroupEnabled(groupId: String, enabled: Boolean): ToolValidation? {
        val group = BuiltinToolGroups.find(groupId)
            ?: return ToolValidation("groupId", "Unknown builtin tool group.")
        repo.updateJson(StoreDescriptorRegistry.TOOLS_BUILTIN_ID) { json ->
            val flags = parseKnownFlags(json).toMutableMap()
            group.members.forEach { member -> flags[member] = enabled }
            ToolSettingsCodec.encodeBuiltinEnabled(flags)
        }
        return null
    }

    // 读端忽略未知工具名，写端丢弃孤儿 flag（下架/改名卫生），文件不累积旧键。
    private fun parseKnownFlags(json: String): Map<String, Boolean> {
        return ToolSettingsCodec.parseBuiltinEnabled(json)
            .filterKeys { name -> registry.find(name) != null }
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
