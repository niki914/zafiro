package com.niki914.nexus.agentic.app.ui.nexus.model

import com.niki914.nexus.agentic.app.R
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
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeLlmConfig as LlmConfig

@OptIn(ExperimentalCoroutinesApi::class)
class ConfigureViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun save_withMissingApiKey_staysOnPageAndRequestsFieldFocus() = runTest {
        var saveCalled = false
        val viewModel = ConfigureViewModel(
            ConfigureViewModelDependencies.Default.copy(
                loadLlmConfig = { LlmConfig() },
                saveLlmAccess = { _, _, _, _ -> saveCalled = true },
            )
        )
        val effectDeferred = async { viewModel.uiEffect.first() }

        viewModel.sendIntent(ConfigureIntent.Initialize("deepseek"))
        advanceUntilIdle()
        viewModel.sendIntent(ConfigureIntent.Save)
        advanceUntilIdle()

        val state = viewModel.uiStateFlow.value
        assertFalse(saveCalled)
        assertFalse(state.isSaving)
        assertNull(state.inlineError)
        assertEquals(R.string.ui_settings_configure_error_required, state.apiKeyErrorResId)
        assertEquals(ConfigureEffect.FocusApiKey, effectDeferred.await())
    }

    @Test
    fun save_withCompleteFields_persistsSettings() = runTest {
        var savedConfig: LlmConfig? = null
        val viewModel = ConfigureViewModel(
            ConfigureViewModelDependencies.Default.copy(
                loadLlmConfig = { LlmConfig() },
                saveLlmAccess = { provider, endpoint, model, apiKey ->
                    savedConfig = LlmConfig(
                        provider = provider,
                        endpoint = endpoint,
                        model = model,
                        apiKey = apiKey,
                    )
                },
            )
        )

        viewModel.sendIntent(ConfigureIntent.Initialize("deepseek"))
        advanceUntilIdle()
        viewModel.sendIntent(ConfigureIntent.UpdateModel("deepseek-chat"))
        viewModel.sendIntent(ConfigureIntent.UpdateApiKey("sk-demo"))
        viewModel.sendIntent(ConfigureIntent.Save)
        advanceUntilIdle()

        assertEquals("deepseek", savedConfig?.provider)
        assertEquals("deepseek-chat", savedConfig?.model)
        assertEquals("sk-demo", savedConfig?.apiKey)
        assertNull(viewModel.uiStateFlow.value.inlineError)
    }

    @Test
    fun initialize_onboarding_preservesOfficialEndpointPolicy() = runTest {
        val officialEndpoint = ProviderSpecs.find("openai").officialEndpoint
        val viewModel = ConfigureViewModel(
            ConfigureViewModelDependencies.Default.copy(
                loadLlmConfig = {
                    LlmConfig(
                        provider = "openai",
                        endpoint = officialEndpoint,
                        model = "gpt-4o",
                        apiKey = "sk-demo",
                        prompt = "settings prompt",
                        proxy = "http://127.0.0.1:7890",
                    )
                },
            )
        )

        viewModel.sendIntent(
            ConfigureIntent.Initialize(
                providerId = "openai",
                scene = ConfigureScene.Onboarding,
            )
        )
        advanceUntilIdle()

        val state = viewModel.uiStateFlow.value
        assertEquals(ConfigureScene.Onboarding, state.scene)
        assertEquals(officialEndpoint, state.endpointInput)
        assertFalse(state.endpointOverrideEnabled)
        assertEquals("gpt-4o", state.modelInput)
        assertEquals("", state.promptInput)
        assertEquals("", state.proxyInput)
    }

    @Test
    fun initialize_onboarding_usesProviderExampleModelWhenSavedModelBlank() = runTest {
        val viewModel = ConfigureViewModel(
            ConfigureViewModelDependencies.Default.copy(
                loadLlmConfig = {
                    LlmConfig(
                        provider = "openai",
                        endpoint = ProviderSpecs.find("openai").officialEndpoint,
                        model = "   ",
                        apiKey = "sk-demo",
                    )
                },
            )
        )

        viewModel.sendIntent(
            ConfigureIntent.Initialize(
                providerId = "openai",
                scene = ConfigureScene.Onboarding,
            )
        )
        advanceUntilIdle()

        assertEquals("gpt-5.4", viewModel.uiStateFlow.value.modelInput)
    }

    @Test
    fun initialize_settings_usesSavedEndpoint() = runTest {
        val viewModel = ConfigureViewModel(
            ConfigureViewModelDependencies.Default.copy(
                loadLlmConfig = {
                    LlmConfig(
                        provider = "openai",
                        endpoint = "https://user.example.com/v1",
                        model = "gpt-4o-mini",
                        apiKey = "sk-demo",
                        prompt = "settings assistant prompt",
                        proxy = "http://127.0.0.1:7890",
                    )
                },
            )
        )

        viewModel.sendIntent(ConfigureIntent.Initialize(scene = ConfigureScene.Settings))
        advanceUntilIdle()

        val state = viewModel.uiStateFlow.value
        assertEquals(ConfigureScene.Settings, state.scene)
        assertEquals("openai", state.providerSpec.id)
        assertEquals("https://user.example.com/v1", state.endpointInput)
        assertTrue(state.endpointOverrideEnabled)
        assertEquals("gpt-4o-mini", state.modelInput)
        assertEquals("settings assistant prompt", state.promptInput)
        assertEquals("http://127.0.0.1:7890", state.proxyInput)
    }

    @Test
    fun initialize_settings_usesProviderExampleModelWhenSavedModelBlank() = runTest {
        val viewModel = ConfigureViewModel(
            ConfigureViewModelDependencies.Default.copy(
                loadLlmConfig = {
                    LlmConfig(
                        provider = "anthropic",
                        endpoint = "https://api.anthropic.com/v1/messages",
                        model = "",
                        apiKey = "sk-demo",
                    )
                },
            )
        )

        viewModel.sendIntent(ConfigureIntent.Initialize(scene = ConfigureScene.Settings))
        advanceUntilIdle()

        assertEquals("claude-sonnet-4-6", viewModel.uiStateFlow.value.modelInput)
    }

    @Test
    fun save_settings_persistsFullConfigAndKeepsUneditedFields() = runTest {
        val existingConfig = LlmConfig(
            provider = "openai",
            endpoint = "https://old.example.com/v1",
            model = "old-model",
            apiKey = "old-key",
            prompt = "old prompt",
            proxy = "http://127.0.0.1:7890",
            memoryPrompt = "keep memory",
            takeoverKeywords = listOf("keep", "keywords"),
        )
        var saveAccessCalled = false
        var savedConfig: LlmConfig? = null
        val viewModel = ConfigureViewModel(
            ConfigureViewModelDependencies.Default.copy(
                loadLlmConfig = { existingConfig },
                saveLlmAccess = { _, _, _, _ -> saveAccessCalled = true },
                saveLlmConfig = { config -> savedConfig = config },
            )
        )
        val effectDeferred = async { viewModel.uiEffect.first() }

        viewModel.sendIntent(ConfigureIntent.Initialize(scene = ConfigureScene.Settings))
        advanceUntilIdle()
        viewModel.sendIntent(ConfigureIntent.UpdateEndpoint("https://new.example.com/v1"))
        viewModel.sendIntent(ConfigureIntent.UpdateModel("gpt-4.1"))
        viewModel.sendIntent(ConfigureIntent.UpdateApiKey("sk-new"))
        viewModel.sendIntent(ConfigureIntent.UpdatePrompt("new prompt"))
        viewModel.sendIntent(ConfigureIntent.UpdateProxy(" socks5://127.0.0.1:1080 "))
        viewModel.sendIntent(ConfigureIntent.Save)
        advanceUntilIdle()

        assertFalse(saveAccessCalled)
        assertEquals(ConfigureEffect.SettingsSaveSucceeded, effectDeferred.await())
        assertEquals("openai", savedConfig?.provider)
        assertEquals("https://new.example.com/v1", savedConfig?.endpoint)
        assertEquals("gpt-4.1", savedConfig?.model)
        assertEquals("sk-new", savedConfig?.apiKey)
        assertEquals("new prompt", savedConfig?.prompt)
        assertEquals("socks5://127.0.0.1:1080", savedConfig?.proxy)
        assertEquals("keep memory", savedConfig?.memoryPrompt)
        assertEquals(listOf("keep", "keywords"), savedConfig?.takeoverKeywords)
        assertNull(viewModel.uiStateFlow.value.inlineError)
    }

    @Test
    fun save_settings_invalidProxyFocusesProxy() = runTest {
        var savedConfig: LlmConfig? = null
        val viewModel = ConfigureViewModel(
            ConfigureViewModelDependencies.Default.copy(
                loadLlmConfig = {
                    LlmConfig(
                        provider = "openai",
                        endpoint = "https://user.example.com/v1",
                        model = "gpt-4o-mini",
                        apiKey = "sk-demo",
                    )
                },
                saveLlmConfig = { config -> savedConfig = config },
            )
        )
        val effectDeferred = async { viewModel.uiEffect.first() }

        viewModel.sendIntent(ConfigureIntent.Initialize(scene = ConfigureScene.Settings))
        advanceUntilIdle()
        viewModel.sendIntent(ConfigureIntent.UpdateProxy("not a uri"))
        viewModel.sendIntent(ConfigureIntent.Save)
        advanceUntilIdle()

        val state = viewModel.uiStateFlow.value
        assertNull(savedConfig)
        assertFalse(state.isSaving)
        assertNull(state.inlineError)
        assertEquals(R.string.ui_settings_configure_error_proxy_invalid, state.proxyErrorResId)
        assertEquals(ConfigureEffect.FocusProxy, effectDeferred.await())
    }

    @Test
    fun endpointOverrideToggle_usesDefaultWhenDisabledAndRestoresCustomWhenEnabled() = runTest {
        var savedConfig: LlmConfig? = null
        val viewModel = ConfigureViewModel(
            ConfigureViewModelDependencies.Default.copy(
                loadLlmConfig = { LlmConfig() },
                saveLlmAccess = { provider, endpoint, model, apiKey ->
                    savedConfig = LlmConfig(
                        provider = provider,
                        endpoint = endpoint,
                        model = model,
                        apiKey = apiKey,
                    )
                },
            )
        )
        val officialEndpoint = ProviderSpecs.find("openai").officialEndpoint

        viewModel.sendIntent(ConfigureIntent.Initialize("openai"))
        advanceUntilIdle()
        viewModel.sendIntent(ConfigureIntent.SetEndpointOverride(true))
        advanceUntilIdle()
        assertEquals(officialEndpoint, viewModel.uiStateFlow.value.endpointInput)

        viewModel.sendIntent(ConfigureIntent.UpdateEndpoint("abc"))
        viewModel.sendIntent(ConfigureIntent.UpdateModel("gpt-5.4"))
        viewModel.sendIntent(ConfigureIntent.UpdateApiKey("sk-demo"))
        advanceUntilIdle()
        viewModel.sendIntent(ConfigureIntent.SetEndpointOverride(false))
        advanceUntilIdle()

        assertEquals(officialEndpoint, viewModel.uiStateFlow.value.endpointInput)
        viewModel.sendIntent(ConfigureIntent.Save)
        advanceUntilIdle()
        assertEquals(officialEndpoint, savedConfig?.endpoint)

        viewModel.sendIntent(ConfigureIntent.SetEndpointOverride(true))
        advanceUntilIdle()

        assertEquals("abc", viewModel.uiStateFlow.value.endpointInput)
    }

    @Test
    fun hasUnsavedChanges_isFalseForUneditedSettingsAndRestoredPrompt() = runTest {
        val viewModel = ConfigureViewModel(
            ConfigureViewModelDependencies.Default.copy(
                loadLlmConfig = {
                    LlmConfig(
                        provider = "openai",
                        endpoint = "https://user.example.com/v1",
                        model = "gpt-4o-mini",
                        apiKey = "sk-demo",
                        prompt = "original prompt",
                        proxy = "http://127.0.0.1:7890",
                    )
                },
            )
        )

        viewModel.sendIntent(ConfigureIntent.Initialize(scene = ConfigureScene.Settings))
        advanceUntilIdle()
        assertFalse(viewModel.uiStateFlow.value.hasUnsavedChanges)

        viewModel.sendIntent(ConfigureIntent.UpdatePrompt("changed prompt"))
        advanceUntilIdle()
        assertTrue(viewModel.uiStateFlow.value.hasUnsavedChanges)

        viewModel.sendIntent(ConfigureIntent.UpdatePrompt("original prompt"))
        advanceUntilIdle()
        assertFalse(viewModel.uiStateFlow.value.hasUnsavedChanges)
    }

    @Test
    fun hasUnsavedChanges_isAlwaysFalseForOnboardingScene() = runTest {
        val viewModel = ConfigureViewModel(
            ConfigureViewModelDependencies.Default.copy(
                loadLlmConfig = {
                    LlmConfig(
                        provider = "openai",
                        endpoint = ProviderSpecs.find("openai").officialEndpoint,
                        model = "gpt-4o",
                        apiKey = "sk-demo",
                    )
                },
            )
        )

        viewModel.sendIntent(
            ConfigureIntent.Initialize(
                providerId = "openai",
                scene = ConfigureScene.Onboarding,
            )
        )
        advanceUntilIdle()
        viewModel.sendIntent(ConfigureIntent.UpdateModel("gpt-4.1"))
        viewModel.sendIntent(ConfigureIntent.UpdateApiKey("sk-new"))
        advanceUntilIdle()

        assertFalse(viewModel.uiStateFlow.value.hasUnsavedChanges)
    }
}
