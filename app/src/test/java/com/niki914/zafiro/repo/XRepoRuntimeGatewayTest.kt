package com.niki914.zafiro.repo

import android.content.Context
import android.content.ContextWrapper
import com.niki914.store.StoreDescriptorRegistry
import com.niki914.zafiro.app.util.SilentLoggerRule
import com.niki914.zafiro.settings.model.DEFAULT_CONTEXT_WINDOW_TOKENS
import com.niki914.zafiro.settings.model.DEFAULT_MAX_TOKENS
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class XRepoRuntimeGatewayTest {

    @get:Rule
    val silentLoggerRule = SilentLoggerRule()

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context: Context by lazy {
        val filesDir = temporaryFolder.newFolder()
        object : ContextWrapper(null) {
            override fun getFilesDir(): File = filesDir
            override fun getApplicationContext(): Context = this
            override fun getPackageName(): String = "com.niki914.zafiro"
        }
    }

    @After
    fun tearDown() {
        XRepo.resetForTest()
    }

    @Test
    fun readLlmConfig_includesMemoriesFromMemoryStore() = runTest {
        val store = FakeDomainSettingsStore()
        XRepo.installStoreForTest(store)
        XRepo.init(context)

        XRepo.llmConfigs.upsert(
            SavedLlmConfig(
                id = "cfg-1",
                name = "OpenAI",
                provider = "openai",
                endpoint = "https://api.openai.com",
                apiKey = "secret",
                model = "gpt-4",
                protocol = "openai-chat-completions",
                proxy = "",
            )
        )
        XRepo.llmConfigs.savePrompt("Hello")

        XRepo.memory.replaceAll(listOf("Fact 1", "Fact 2"))

        val gateway = XRepoRuntimeGateway(XRepo)
        val config = gateway.readLlmConfig()

        assertEquals("openai", config.provider)
        assertEquals(listOf("Fact 1", "Fact 2"), config.memories)
    }

    @Test
    fun readLlmConfig_withConfigId_usesTargetOrFallsBackToActive() = runTest {
        val store = FakeDomainSettingsStore()
        XRepo.installStoreForTest(store)
        XRepo.init(context)

        XRepo.llmConfigs.upsert(
            SavedLlmConfig(
                id = "cfg-active",
                name = "ActiveModel",
                provider = "openai",
                endpoint = "https://api.openai.com",
                apiKey = "secret1",
                model = "gpt-active",
                protocol = "openai-chat-completions",
                proxy = "",
            )
        )
        XRepo.llmConfigs.upsert(
            SavedLlmConfig(
                id = "cfg-other",
                name = "OtherModel",
                provider = "anthropic",
                endpoint = "https://api.anthropic.com",
                apiKey = "secret2",
                model = "claude-other",
                protocol = "anthropic",
                proxy = "",
            )
        )
        XRepo.llmConfigs.setActive("cfg-active")

        val gateway = XRepoRuntimeGateway(XRepo)

        // 1. 指定有效 configId -> 使用该配置
        val targetConfig = gateway.readLlmConfig(configId = "cfg-other")
        assertEquals("claude-other", targetConfig.model)
        assertEquals("anthropic", targetConfig.provider)

        // 2. 指定不存在的 configId -> 回落全局 active
        val fallbackConfig = gateway.readLlmConfig(configId = "cfg-deleted")
        assertEquals("gpt-active", fallbackConfig.model)
        assertEquals("openai", fallbackConfig.provider)

        // 3. 空白 / null configId -> 默认使用全局 active
        val defaultConfig = gateway.readLlmConfig(configId = "")
        assertEquals("gpt-active", defaultConfig.model)
    }

    @Test
    fun skillMethods_proxyEnabledListAndDetailFromXRepoSkills() = runTest {
        XRepo.installStoreForTest(FakeDomainSettingsStore())
        XRepo.init(context)
        writeSkill("skill-a", skillContent(name = "Skill A", description = "Enabled skill"))
        writeSkill("skill-b", skillContent(name = "Skill B", description = "Disabled skill"))
        XRepo.skills.setEnabled("skill-b", false)

        val gateway = XRepoRuntimeGateway(XRepo)
        val enabledSkills = gateway.listEnabledSkills()
        val loadedSkill = gateway.loadSkill("skill-a")

        assertEquals(listOf("skill-a"), enabledSkills.map { it.id })
        assertEquals("Skill A", enabledSkills.single().name)
        assertEquals("Enabled skill", loadedSkill?.description)
        assertEquals("skill-a", loadedSkill?.id)
        assertEquals(
            skillContent(name = "Skill A", description = "Enabled skill"),
            loadedSkill?.content
        )
        assertNull(gateway.loadSkill("missing"))
    }

    @Test
    fun readLlmConfig_resolvesTokenLimitsThroughFourLevelChain() = runTest {
        val store = FakeDomainSettingsStore()
        XRepo.installStoreForTest(store)
        XRepo.init(context)

        val gateway = XRepoRuntimeGateway(XRepo)

        suspend fun upsertActive(maxTokensText: String, contextWindowText: String) {
            XRepo.llmConfigs.upsert(
                SavedLlmConfig(
                    id = "cfg-1",
                    name = "DeepSeek",
                    provider = "deepseek",
                    endpoint = "https://api.deepseek.com/responses",
                    apiKey = "secret",
                    model = "deepseek-chat",
                    protocol = "openai-responses",
                    proxy = "",
                    maxTokensText = maxTokensText,
                    contextWindowText = contextWindowText,
                )
            )
        }

        // 1. 用户填了 → 用户赢（宽松文本，含后缀），且完全不读两个云端缓存 store
        upsertActive(maxTokensText = "32k", contextWindowText = "200k")
        store.readIds.clear()
        with(gateway.readLlmConfig()) {
            assertEquals(32_000, maxTokens)
            assertEquals(200_000L, contextWindowTokens)
        }
        assertFalse(
            "用户填满时不应读云端缓存",
            store.readIds.any {
                it == StoreDescriptorRegistry.LLM_MODEL_FACTS_ID ||
                        it == StoreDescriptorRegistry.OPENROUTER_CATALOG_ID
            }
        )

        // 准备两层云端事实：provider 以 endpoint+model 为键；OpenRouter 按归一化名
        XRepo.cloudFacts.saveProviderFacts(
            endpoint = "https://api.deepseek.com/responses",
            factsByModel = mapOf(
                "deepseek-chat" to CloudModelFacts(
                    windowTokens = 1_048_576L,
                    maxOutputTokens = 393_216L,
                )
            ),
        )
        XRepo.cloudFacts.saveOpenRouter(
            OpenRouterCatalogDocument(
                fetchedAtMillis = 1L,
                models = mapOf(
                    "deepseekdeepseekchat" to CloudModelFacts(
                        windowTokens = 128_000L,
                        maxOutputTokens = 8_192L,
                    )
                ),
            )
        )

        // 2. 留空 → provider /models 事实赢
        upsertActive(maxTokensText = "", contextWindowText = "")
        with(gateway.readLlmConfig()) {
            assertEquals(393_216, maxTokens)
            assertEquals(1_048_576L, contextWindowTokens)
        }

        // 3. 换一个无 provider 事实的 endpoint → OpenRouter 裸名匹配赢
        upsertActiveOnOtherEndpoint(model = "deepseek-chat")
        with(gateway.readLlmConfig()) {
            assertEquals(8_192, maxTokens)
            assertEquals(128_000L, contextWindowTokens)
        }

        // 4. 两层都匹配不上 → 兕底默认
        upsertActiveOnOtherEndpoint(model = "unknown-model")
        with(gateway.readLlmConfig()) {
            assertEquals(DEFAULT_MAX_TOKENS, maxTokens)
            assertEquals(DEFAULT_CONTEXT_WINDOW_TOKENS, contextWindowTokens)
        }

        // 5. 旧格式的 "0" 与脏文本也当未设置 → 落到云端（provider 缓存仍在），不是直接跳默认
        upsertActive(maxTokensText = "0", contextWindowText = "garbage")
        with(gateway.readLlmConfig()) {
            assertEquals(393_216, maxTokens)
            assertEquals(1_048_576L, contextWindowTokens)
        }
    }

    /** 同一配置 id，但 endpoint/model 不在缓存里：用于探解析链的下层。 */
    private suspend fun upsertActiveOnOtherEndpoint(model: String) {
        XRepo.llmConfigs.upsert(
            SavedLlmConfig(
                id = "cfg-1",
                name = "DeepSeek",
                provider = "deepseek",
                endpoint = "https://other.example.com",
                apiKey = "secret",
                model = model,
                protocol = "openai-responses",
                proxy = "",
            )
        )
    }

    private fun writeSkill(id: String, content: String): File {
        val file = File(context.filesDir, "skills/$id/SKILL.md")
        file.parentFile?.mkdirs()
        file.writeText(content)
        return file
    }

    private fun skillContent(
        name: String,
        description: String,
    ): String {
        return """
            ---
            name: $name
            description: $description
            ---

            Body for $name
        """.trimIndent()
    }
}
