package com.niki914.zafiro.repo

import android.content.Context
import android.content.ContextWrapper
import com.niki914.zafiro.app.util.SilentLoggerRule
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
