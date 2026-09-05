package com.niki914.zafiro.app.ui.model

import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.util.SilentLoggerRule
import com.niki914.zafiro.repo.LlmConfigsDocument
import com.niki914.zafiro.repo.SavedLlmConfig
import com.niki914.zafiro.settings.model.LlmProtocol
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConfigureViewModelTest {

    @get:Rule
    val silentLoggerRule = SilentLoggerRule()

    @get:Rule
    val mainDispatcherRule =
        MainDispatcherRule()

    /** 记录式依赖：upsert/delete/setActive/savePrompt 全部落到内存 document。 */
    private class RecordingDeps {
        var document = LlmConfigsDocument()
        val upserted = mutableListOf<SavedLlmConfig>()
        val deletedIds = mutableListOf<String>()
        val activatedIds = mutableListOf<String>()
        val savedPrompts = mutableListOf<String>()

        fun toDependencies(): ConfigureViewModelDependencies =
            ConfigureViewModelDependencies(
                loadDocument = { document },
                upsertConfig = { config ->
                    upserted += config
                    document = document.copy(
                        configs = document.configs.filterNot { it.id == config.id } + config,
                        activeId = when {
                            // 新建即 active；编辑保存不改变归属（失效时修复）
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
                saveGlobalPrompt = { prompt ->
                    savedPrompts += prompt
                    document = document.copy(prompt = prompt)
                },
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
    fun initializeSettingsEdit_loadsActiveConfigAndGlobalPrompt() = runTest {
        val deps = RecordingDeps()
        deps.document = LlmConfigsDocument(
            activeId = "cfg-a",
            prompt = "global",
            configs = listOf(savedLlmConfig("cfg-a", model = "deepseek-v4-pro")),
        )
        val viewModel = ConfigureViewModel(deps.toDependencies())

        viewModel.sendIntent(ConfigureIntent.Initialize(ConfigureScene.SettingsEdit))
        advanceUntilIdle()

        val state = viewModel.uiStateFlow.value
        assertEquals(ConfigureScene.SettingsEdit, state.scene)
        assertEquals("cfg-a", state.editingConfigId)
        assertEquals("global", state.promptInput)
        assertEquals("deepseek-v4-pro", state.modelInput)
        assertEquals(LlmProtocol.OpenAiResponses.wireId, state.protocolWireId)
        assertTrue(state.savedConfigs.first().isActive)
    }

    @Test
    fun initializeSettingsEdit_withoutExistingConfig_fallsBackToNewDraft() = runTest {
        val deps = RecordingDeps()
        val viewModel = ConfigureViewModel(deps.toDependencies())

        viewModel.sendIntent(
            ConfigureIntent.Initialize(
                ConfigureScene.SettingsEdit,
                configId = "ghost"
            )
        )
        advanceUntilIdle()

        assertNull(viewModel.uiStateFlow.value.editingConfigId)
        assertEquals(ConfigureScene.SettingsNew, viewModel.uiStateFlow.value.scene)
    }

    @Test
    fun saveOnNewConfig_activatesIt_promptSavedViaSeparateIntent() = runTest {
        val deps = RecordingDeps()
        val viewModel = ConfigureViewModel(deps.toDependencies())
        viewModel.sendIntent(ConfigureIntent.Initialize(ConfigureScene.SettingsNew))
        advanceUntilIdle()
        val effectDeferred = async { viewModel.uiEffect.first() }

        viewModel.sendIntent(ConfigureIntent.UpdateName("主力"))
        viewModel.sendIntent(ConfigureIntent.UpdatePrompt("你是一个测试助手。"))
        viewModel.sendIntent(ConfigureIntent.UpdateModel("gpt-5"))
        viewModel.sendIntent(ConfigureIntent.UpdateApiKey("sk"))
        viewModel.sendIntent(ConfigureIntent.Save)
        advanceUntilIdle()

        assertEquals(1, deps.upserted.size)
        assertTrue(deps.upserted.first().id.isNotBlank())
        assertTrue(deps.savedPrompts.isEmpty())
        viewModel.sendIntent(ConfigureIntent.SavePrompt)
        advanceUntilIdle()
        assertEquals("你是一个测试助手。", deps.savedPrompts.single())
        assertTrue(deps.activatedIds.isEmpty())
        assertEquals(deps.upserted.first().id, viewModel.uiStateFlow.value.activeConfigId)
        assertEquals(ConfigureEffect.SettingsSaveSucceeded, effectDeferred.await())
    }

    @Test
    fun saveEditedNonActiveConfig_doesNotChangeActiveBelonging() = runTest {
        val deps = RecordingDeps()
        deps.document = LlmConfigsDocument(
            activeId = "cfg-a",
            configs = listOf(
                savedLlmConfig("cfg-a"),
                savedLlmConfig("cfg-b"),
            ),
        )
        val viewModel = ConfigureViewModel(deps.toDependencies())
        // cfg-b 非 active：初始化即建立"编辑非生效配置"场景
        viewModel.sendIntent(
            ConfigureIntent.Initialize(
                ConfigureScene.SettingsEdit,
                configId = "cfg-b"
            )
        )
        advanceUntilIdle()

        viewModel.sendIntent(ConfigureIntent.Save)
        advanceUntilIdle()

        // VM 契约：编辑保存只 upsert，归属判定交给 repo 层
        assertEquals(1, deps.upserted.size)
        assertTrue(deps.activatedIds.isEmpty())
    }

    @Test
    fun save_withBlankModel_sendsFocusEffectWithoutWriting() = runTest {
        val deps = RecordingDeps()
        val viewModel = ConfigureViewModel(deps.toDependencies())
        viewModel.sendIntent(ConfigureIntent.Initialize(ConfigureScene.SettingsNew))
        advanceUntilIdle()
        val effectDeferred = async { viewModel.uiEffect.first() }

        viewModel.sendIntent(ConfigureIntent.UpdateModel(""))
        viewModel.sendIntent(ConfigureIntent.Save)
        advanceUntilIdle()

        assertTrue(deps.upserted.isEmpty())
        assertEquals(ConfigureEffect.FocusModel, effectDeferred.await())
    }


    @Test
    fun initializeSettingsEdit_withoutChanges_isNotDirty() = runTest {
        val deps = RecordingDeps()
        deps.document = LlmConfigsDocument(
            activeId = "cfg-a",
            prompt = "global",
            configs = listOf(savedLlmConfig("cfg-a")),
        )
        val viewModel = ConfigureViewModel(deps.toDependencies())
        viewModel.sendIntent(ConfigureIntent.Initialize(ConfigureScene.SettingsEdit))
        advanceUntilIdle()

        assertFalse(viewModel.uiStateFlow.value.hasUnsavedChanges)
    }

    @Test
    fun editingField_marksDirtyAndRevertingClearsIt() = runTest {
        val deps = RecordingDeps()
        deps.document = LlmConfigsDocument(
            activeId = "cfg-a",
            configs = listOf(savedLlmConfig("cfg-a")),
        )
        val viewModel = ConfigureViewModel(deps.toDependencies())
        viewModel.sendIntent(ConfigureIntent.Initialize(ConfigureScene.SettingsEdit))
        advanceUntilIdle()

        viewModel.sendIntent(ConfigureIntent.UpdateModel("changed"))
        advanceUntilIdle()
        assertTrue(viewModel.uiStateFlow.value.hasUnsavedChanges)

        viewModel.sendIntent(ConfigureIntent.UpdateModel("test-model"))
        advanceUntilIdle()
        assertFalse(viewModel.uiStateFlow.value.hasUnsavedChanges)
    }

    @Test
    fun editingPrompt_marksDirty() = runTest {
        val deps = RecordingDeps()
        deps.document = LlmConfigsDocument(
            activeId = "cfg-a",
            configs = listOf(savedLlmConfig("cfg-a")),
        )
        val viewModel = ConfigureViewModel(deps.toDependencies())
        viewModel.sendIntent(ConfigureIntent.Initialize(ConfigureScene.SettingsEdit))
        advanceUntilIdle()

        viewModel.sendIntent(ConfigureIntent.UpdatePrompt("new prompt"))
        advanceUntilIdle()
        assertTrue(viewModel.uiStateFlow.value.hasUnsavedChanges)
    }

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

        viewModel.sendIntent(ConfigureIntent.UpdateName("Taken"))
        viewModel.sendIntent(ConfigureIntent.Save)
        advanceUntilIdle()

        assertTrue(deps.upserted.isEmpty())
        assertEquals(
            R.string.ui_settings_configure_error_name_duplicate,
            viewModel.uiStateFlow.value.nameErrorResId,
        )
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
