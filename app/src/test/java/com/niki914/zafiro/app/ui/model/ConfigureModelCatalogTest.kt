// 保护：模型目录拉取状态机、云端缓存覆盖与可用模型防竞态回填。
package com.niki914.zafiro.app.ui.model

import com.niki914.zafiro.app.util.SilentLoggerRule
import com.niki914.zafiro.repo.AvailableModelItem
import com.niki914.zafiro.repo.AvailableModelSource
import com.niki914.zafiro.repo.CloudModelFacts
import com.niki914.zafiro.repo.LlmConfigsDocument
import com.niki914.zafiro.repo.ModelCatalog
import com.niki914.zafiro.repo.SavedLlmConfig
import com.niki914.zafiro.settings.model.LlmProtocol
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConfigureModelCatalogTest {

    @get:Rule
    val silentLoggerRule = SilentLoggerRule()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class RecordingDeps {
        var document = LlmConfigsDocument()
        val upserted = mutableListOf<SavedLlmConfig>()
        val deletedIds = mutableListOf<String>()
        val activatedIds = mutableListOf<String>()
        var catalogResult: ModelCatalog = ModelCatalog(emptyList())
        var catalogError: Throwable? = null
        val savedFacts = mutableListOf<Pair<String, Map<String, CloudModelFacts>>>()

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
                fetchModelCatalog = { _, _, _ ->
                    catalogError?.let { throw it }
                    catalogResult
                },
                saveModelFacts = { endpoint, factsByModel ->
                    savedFacts += endpoint to factsByModel
                },
            )
    }

    @Test
    fun catalogFetch_successOverwritesIncludingEmpty() = runTest {
        val deps = RecordingDeps()
        deps.catalogResult = ModelCatalog(listOf("m-1"))
        val viewModel = ConfigureViewModel(deps.toDependencies())
        viewModel.sendIntent(ConfigureIntent.Initialize(ConfigureScene.SettingsNew))
        advanceUntilIdle()

        viewModel.sendIntent(ConfigureIntent.UpdateApiKey("sk-1"))
        advanceUntilIdle()
        assertEquals(listOf("m-1"), viewModel.uiStateFlow.value.modelCatalog)

        // 成功空结果也覆盖：按钮藏起，不残留旧名单
        deps.catalogResult = ModelCatalog(emptyList())
        viewModel.sendIntent(ConfigureIntent.UpdateApiKey("sk-2"))
        advanceUntilIdle()
        assertTrue(viewModel.uiStateFlow.value.modelCatalog.isEmpty())
    }

    @Test
    fun catalogFetch_failureKeepsOldCache() = runTest {
        val deps = RecordingDeps()
        deps.catalogResult = ModelCatalog(listOf("m-1"))
        val viewModel = ConfigureViewModel(deps.toDependencies())
        viewModel.sendIntent(ConfigureIntent.Initialize(ConfigureScene.SettingsNew))
        advanceUntilIdle()

        viewModel.sendIntent(ConfigureIntent.UpdateApiKey("sk-1"))
        advanceUntilIdle()
        assertEquals(listOf("m-1"), viewModel.uiStateFlow.value.modelCatalog)

        // 失败保留旧缓存：不断网恢复前按钮仍可用
        deps.catalogError = RuntimeException("boom")
        viewModel.sendIntent(ConfigureIntent.UpdateApiKey("sk-2"))
        advanceUntilIdle()
        assertEquals(listOf("m-1"), viewModel.uiStateFlow.value.modelCatalog)
    }

    @Test
    fun catalogFetch_successSavesFactsOnlyWhenPresent() = runTest {
        val deps = RecordingDeps()
        deps.catalogResult = ModelCatalog(
            ids = listOf("m-1"),
            facts = mapOf("m-1" to CloudModelFacts(windowTokens = 1_048_576L)),
        )
        val viewModel = ConfigureViewModel(deps.toDependencies())
        viewModel.sendIntent(ConfigureIntent.Initialize(ConfigureScene.SettingsNew))
        advanceUntilIdle()

        viewModel.sendIntent(ConfigureIntent.UpdateApiKey("sk-1"))
        advanceUntilIdle()

        val (endpoint, facts) = deps.savedFacts.single()
        assertEquals("https://api.deepseek.com/responses", endpoint)
        assertEquals(1_048_576L, facts["m-1"]?.windowTokens)

        // 全缺字段（如 Google）：facts 为空，不写，旧缓存保留
        deps.savedFacts.clear()
        deps.catalogResult = ModelCatalog(ids = listOf("m-2"))
        viewModel.sendIntent(ConfigureIntent.UpdateApiKey("sk-2"))
        advanceUntilIdle()
        assertTrue(deps.savedFacts.isEmpty())
    }

    @Test
    fun addAvailableModel_updatesListAndFillsInputWithoutOverwriteRisk() = runTest {
        val deps = RecordingDeps()
        deps.catalogResult = ModelCatalog(listOf("cloud-model-1"))
        val viewModel = ConfigureViewModel(deps.toDependencies())
        viewModel.sendIntent(ConfigureIntent.Initialize(ConfigureScene.SettingsNew))
        advanceUntilIdle()

        // 用户主动添加模型
        viewModel.sendIntent(ConfigureIntent.ShowAddModelDialog)
        advanceUntilIdle()
        assertTrue(viewModel.uiStateFlow.value.showAddModelDialog)

        viewModel.sendIntent(ConfigureIntent.AddAvailableModel("my-custom-model"))
        advanceUntilIdle()

        assertEquals(
            listOf(AvailableModelItem("my-custom-model", AvailableModelSource.Custom)),
            viewModel.uiStateFlow.value.availableModels,
        )
        assertEquals("my-custom-model", viewModel.uiStateFlow.value.modelInput)
        assertFalse(viewModel.uiStateFlow.value.showAddModelDialog)

        // 随后异步拉取完成，不会覆盖用户的 modelInput，且两批模型合并保留
        viewModel.sendIntent(ConfigureIntent.UpdateApiKey("sk-1"))
        advanceUntilIdle()

        assertEquals("my-custom-model", viewModel.uiStateFlow.value.modelInput)
        assertEquals(listOf("cloud-model-1"), viewModel.uiStateFlow.value.modelCatalog)
        assertEquals(
            listOf(
                AvailableModelItem("my-custom-model", AvailableModelSource.Custom),
                AvailableModelItem("cloud-model-1", AvailableModelSource.Remote),
            ),
            viewModel.uiStateFlow.value.availableModels,
        )
    }

    @Test
    fun refreshModelCatalog_purgesOfflineRemoteModelsAndPreservesCustom() = runTest {
        val deps = RecordingDeps()
        deps.catalogResult = ModelCatalog(listOf("remote-old-1", "remote-stay"))
        val viewModel = ConfigureViewModel(deps.toDependencies())
        viewModel.sendIntent(ConfigureIntent.Initialize(ConfigureScene.SettingsNew))
        advanceUntilIdle()

        // 用户手动添加一个自定义模型
        viewModel.sendIntent(ConfigureIntent.AddAvailableModel("user-custom"))
        advanceUntilIdle()

        // 首次异步扫描拉取远端
        viewModel.sendIntent(ConfigureIntent.UpdateApiKey("sk-1"))
        advanceUntilIdle()

        assertEquals(
            listOf("user-custom", "remote-old-1", "remote-stay"),
            viewModel.uiStateFlow.value.availableModels.map { it.id },
        )

        // 模拟远端下线 remote-old-1，新增 remote-new-2
        deps.catalogResult = ModelCatalog(listOf("remote-stay", "remote-new-2"))
        viewModel.sendIntent(ConfigureIntent.RefreshModelCatalog)
        advanceUntilIdle()

        // 断言：user-custom(Custom) 稳稳保留，已下线的 remote-old-1 自然被淘汰剔除！
        assertEquals(
            listOf(
                AvailableModelItem("user-custom", AvailableModelSource.Custom),
                AvailableModelItem("remote-stay", AvailableModelSource.Remote),
                AvailableModelItem("remote-new-2", AvailableModelSource.Remote),
            ),
            viewModel.uiStateFlow.value.availableModels,
        )

        // 若用户手动输入已有的 remote-stay，去重并保持其 Remote 身份不变
        viewModel.sendIntent(ConfigureIntent.AddAvailableModel("remote-stay"))
        advanceUntilIdle()
        assertEquals(
            AvailableModelSource.Remote,
            viewModel.uiStateFlow.value.availableModels.first { it.id == "remote-stay" }.source,
        )
    }

    @Test
    fun refreshModelCatalog_stateMachineTransitions() = runTest {
        val deps = RecordingDeps()
        deps.catalogResult = ModelCatalog(listOf("model-1"))
        val viewModel = ConfigureViewModel(deps.toDependencies())
        viewModel.sendIntent(ConfigureIntent.Initialize(ConfigureScene.SettingsNew))
        advanceUntilIdle()

        viewModel.sendIntent(ConfigureIntent.UpdateApiKey("sk-1"))
        advanceUntilIdle()

        // 触发显式刷新 -> 成功
        viewModel.sendIntent(ConfigureIntent.RefreshModelCatalog)
        advanceUntilIdle()
        assertEquals(ModelCatalogFetchStatus.Success, viewModel.uiStateFlow.value.catalogFetchStatus)
        assertEquals(listOf("model-1"), viewModel.uiStateFlow.value.modelCatalog)

        // 接口失败 -> 状态迁移为 Failure
        deps.catalogError = RuntimeException("network error")
        viewModel.sendIntent(ConfigureIntent.RefreshModelCatalog)
        advanceUntilIdle()
        assertEquals(ModelCatalogFetchStatus.Failure, viewModel.uiStateFlow.value.catalogFetchStatus)
    }
}
