// 保护：配置名称品牌预填、查重拦截与唯一名称生成。
package com.niki914.zafiro.app.ui.model

import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.util.SilentLoggerRule
import com.niki914.zafiro.repo.CloudModelFacts
import com.niki914.zafiro.repo.LlmConfigsDocument
import com.niki914.zafiro.repo.ModelCatalog
import com.niki914.zafiro.repo.SavedLlmConfig
import com.niki914.zafiro.settings.model.LlmProtocol
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConfigureNameValidationTest {

    @get:Rule
    val silentLoggerRule = SilentLoggerRule()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class RecordingDeps {
        var document = LlmConfigsDocument()
        val upserted = mutableListOf<SavedLlmConfig>()
        val deletedIds = mutableListOf<String>()
        val activatedIds = mutableListOf<String>()

        fun toDependencies(): ConfigureViewModelDependencies =
            ConfigureViewModelDependencies(
                loadDocument = { document },
                upsertConfig = { config ->
                    upserted += config
                    document = document.copy(
                        configs = document.configs.filterNot { it.id == config.id } + config,
                        activeId = when {
                            document.configs.none { it.id == config.id } -> config.id
                            document.configs.none { it.id == document.activeId } -> config.id
                            else -> document.activeId
                        },
                    )
                    null
                },
                deleteConfig = { id ->
                    deletedIds += id
                    val remaining = document.configs.filterNot { it.id == id }
                    document = document.copy(
                        configs = remaining,
                        activeId = when {
                            remaining.isEmpty() -> null
                            document.activeId == id -> remaining.first().id
                            else -> document.activeId
                        },
                    )
                },
                setActiveConfig = { id ->
                    activatedIds += id
                    document = document.copy(activeId = id)
                },
                fetchModelCatalog = { _, _, _ -> ModelCatalog(emptyList()) },
                saveModelFacts = { _, _ -> },
            )
    }

    private fun savedLlmConfig(
        id: String,
        model: String = "test-model",
    ): SavedLlmConfig = SavedLlmConfig(
        id = id,
        name = id,
        provider = "deepseek",
        endpoint = "https://api.deepseek.com/responses",
        apiKey = "secret",
        model = model,
        protocol = LlmProtocol.OpenAiResponses.wireId,
        proxy = "",
    )

    @Test
    fun initialize_prefillsNameWithProviderBrandName() = runTest {
        val deps = RecordingDeps()
        val viewModel = ConfigureViewModel(deps.toDependencies())

        viewModel.sendIntent(
            ConfigureIntent.Initialize(
                ConfigureScene.SettingsNew,
                providerId = "deepseek"
            )
        )
        advanceUntilIdle()
        assertEquals("DeepSeek", viewModel.uiStateFlow.value.configNameInput)

        viewModel.sendIntent(
            ConfigureIntent.Initialize(
                ConfigureScene.Onboarding,
                providerId = "deepseek"
            )
        )
        advanceUntilIdle()
        assertEquals("DeepSeek", viewModel.uiStateFlow.value.configNameInput)
    }

    @Test
    fun save_withDuplicateName_rejectsWithNameError() = runTest {
        val deps = RecordingDeps()
        deps.document = LlmConfigsDocument(
            activeId = "cfg-a",
            configs = listOf(savedLlmConfig("cfg-a").copy(name = "Taken")),
        )
        val viewModel = ConfigureViewModel(deps.toDependencies())
        viewModel.sendIntent(ConfigureIntent.Initialize(ConfigureScene.SettingsNew))
        advanceUntilIdle()
        val effectDeferred = async { viewModel.uiEffect.first() }

        viewModel.sendIntent(ConfigureIntent.UpdateName("Taken"))
        viewModel.sendIntent(ConfigureIntent.Save)
        advanceUntilIdle()

        assertTrue(deps.upserted.isEmpty())
        assertEquals(
            R.string.ui_settings_configure_error_name_duplicate,
            viewModel.uiStateFlow.value.nameErrorResId,
        )
        assertEquals(ConfigureEffect.FocusName, effectDeferred.await())
    }

    @Test
    fun initializeNew_withExistingName_picksUniqueName() = runTest {
        val deps = RecordingDeps()
        deps.document = LlmConfigsDocument(
            activeId = "cfg-a",
            configs = listOf(savedLlmConfig("cfg-a").copy(name = "DeepSeek")),
        )
        val viewModel = ConfigureViewModel(deps.toDependencies())
        viewModel.sendIntent(
            ConfigureIntent.Initialize(
                ConfigureScene.SettingsNew,
                providerId = "deepseek",
            )
        )
        advanceUntilIdle()
        assertEquals("DeepSeek 2", viewModel.uiStateFlow.value.configNameInput)
    }

    @Test
    fun save_editingConfigWithOwnName_allowed() = runTest {
        val deps = RecordingDeps()
        deps.document = LlmConfigsDocument(
            activeId = "cfg-a",
            configs = listOf(savedLlmConfig("cfg-a").copy(name = "Same")),
        )
        val viewModel = ConfigureViewModel(deps.toDependencies())
        viewModel.sendIntent(
            ConfigureIntent.Initialize(
                ConfigureScene.SettingsEdit,
                configId = "cfg-a"
            )
        )
        advanceUntilIdle()

        viewModel.sendIntent(ConfigureIntent.Save)
        advanceUntilIdle()

        assertEquals(1, deps.upserted.size)
        assertNull(viewModel.uiStateFlow.value.nameErrorResId)
    }
}
