package com.niki914.nexus.agentic.app.ui.nexus.model

import androidx.annotation.StringRes
import com.niki914.nexus.agentic.app.R
import com.niki914.nexus.agentic.repo.XRepo
import com.niki914.nexus.base.ComposeMVIViewModel
import kotlinx.coroutines.CancellationException
import java.net.URI
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeLlmConfig as LlmConfig

enum class ConfigureScene {
    Onboarding,
    Settings,
    SettingsProviderSwitch,
}

data class ConfigureUiState(
    val scene: ConfigureScene = ConfigureScene.Onboarding,
    val providerSpec: ProviderSpec = ProviderSpecs.default,
    val endpointOverrideEnabled: Boolean = false,
    val endpointInput: String = ProviderSpecs.default.officialEndpoint,
    val lastCustomEndpointInput: String = "",
    val modelInput: String = "",
    val apiKeyInput: String = "",
    val apiKeyVisible: Boolean = false,
    @param:StringRes val endpointErrorResId: Int? = null,
    @param:StringRes val modelErrorResId: Int? = null,
    @param:StringRes val apiKeyErrorResId: Int? = null,
    val promptInput: String = "",
    val proxyInput: String = "",
    @param:StringRes val proxyErrorResId: Int? = null,
    val isSaving: Boolean = false,
    val inlineError: ConfigureInlineError? = null,
    val initialSettingsSnapshot: ConfigureSnapshot? = null,
)

data class ConfigureSnapshot(
    val providerId: String,
    val endpoint: String,
    val model: String,
    val apiKey: String,
    val prompt: String,
    val proxy: String,
)

val ConfigureUiState.hasUnsavedChanges: Boolean
    get() = (scene == ConfigureScene.Settings || scene == ConfigureScene.SettingsProviderSwitch) &&
            initialSettingsSnapshot?.let { it != toSettingsSnapshot() } == true

sealed interface ConfigureInlineError {
    data class LoadFailed(val reason: ConfigureErrorReason.LoadSettingsFailed) :
        ConfigureInlineError

    data class SaveFailed(val reason: ConfigureErrorReason.SaveSettingsFailed) :
        ConfigureInlineError
}

sealed interface ConfigureErrorReason {
    data class LoadSettingsFailed(val message: String) : ConfigureErrorReason
    data class SaveSettingsFailed(val message: String) : ConfigureErrorReason
}

sealed interface ConfigureIntent {
    data class Initialize(
        val providerId: String? = null,
        val scene: ConfigureScene = ConfigureScene.Onboarding,
    ) : ConfigureIntent

    data class SetEndpointOverride(val enabled: Boolean) : ConfigureIntent
    data class UpdateEndpoint(val value: String) : ConfigureIntent
    data class UpdateModel(val value: String) : ConfigureIntent
    data class UpdateApiKey(val value: String) : ConfigureIntent
    data class UpdatePrompt(val value: String) : ConfigureIntent
    data class UpdateProxy(val value: String) : ConfigureIntent
    data object ToggleApiKeyVisibility : ConfigureIntent
    data object Save : ConfigureIntent
}

sealed interface ConfigureEffect {
    data object OnboardingSaveSucceeded : ConfigureEffect
    data object SettingsSaveSucceeded : ConfigureEffect
    data class SaveFailed(val reason: ConfigureErrorReason) : ConfigureEffect
    data object FocusModel : ConfigureEffect
    data object FocusApiKey : ConfigureEffect
    data object FocusEndpoint : ConfigureEffect
    data object FocusProxy : ConfigureEffect
}

internal data class ConfigureViewModelDependencies(
    val loadLlmConfig: suspend () -> LlmConfig,
    val saveLlmAccess: suspend (
        provider: String,
        endpoint: String,
        model: String,
        apiKey: String,
    ) -> Unit,
    val saveLlmConfig: suspend (LlmConfig) -> Unit,
) {
    companion object {
        val Default = ConfigureViewModelDependencies(
            loadLlmConfig = { XRepo.llm() },
            saveLlmAccess = XRepo::saveLlmAccess,
            saveLlmConfig = XRepo::saveLlm,
        )
    }
}

class ConfigureViewModel internal constructor(
    private val dependencies: ConfigureViewModelDependencies,
) : ComposeMVIViewModel<ConfigureIntent, ConfigureUiState, ConfigureEffect>() {
    constructor() : this(ConfigureViewModelDependencies.Default)

    override fun initUiState(): ConfigureUiState = ConfigureUiState()

    override suspend fun handleIntent(intent: ConfigureIntent) {
        when (intent) {
            is ConfigureIntent.Initialize -> initialize(intent.scene, intent.providerId)
            is ConfigureIntent.SetEndpointOverride -> setEndpointOverride(intent.enabled)
            is ConfigureIntent.UpdateEndpoint -> updateEndpoint(intent.value)
            is ConfigureIntent.UpdateModel -> updateModel(intent.value)
            is ConfigureIntent.UpdateApiKey -> updateApiKey(intent.value)
            is ConfigureIntent.UpdatePrompt -> updatePrompt(intent.value)
            is ConfigureIntent.UpdateProxy -> updateProxy(intent.value)
            ConfigureIntent.ToggleApiKeyVisibility -> toggleApiKeyVisibility()
            ConfigureIntent.Save -> save()
        }
    }

    private suspend fun initialize(scene: ConfigureScene, initialProviderId: String?) {
        try {
            val llmConfig = dependencies.loadLlmConfig()
            when (scene) {
                ConfigureScene.Onboarding -> initializeOnboarding(llmConfig, initialProviderId)
                ConfigureScene.Settings -> initializeSettings(llmConfig, initialProviderId)
                ConfigureScene.SettingsProviderSwitch -> initializeSettingsProviderSwitch(
                    llmConfig,
                    initialProviderId,
                )
            }
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            val message = throwable.message ?: throwable::class.java.simpleName
            val reason = ConfigureErrorReason.LoadSettingsFailed(message)
            updateState {
                copy(
                    scene = scene,
                    isSaving = false,
                    inlineError = ConfigureInlineError.LoadFailed(reason),
                )
            }
        }
    }

    private fun initializeOnboarding(llmConfig: LlmConfig, initialProviderId: String?) {
        val savedProviderId = llmConfig.provider.takeIf { it.isNotBlank() }
        val resolvedProviderId = initialProviderId
            ?.takeIf { it.isNotBlank() }
            ?: savedProviderId
        val providerSpec = ProviderSpecs.find(resolvedProviderId)
        val shouldReuseSavedValues = if (initialProviderId.isNullOrBlank()) {
            savedProviderId == null || savedProviderId == providerSpec.id
        } else {
            savedProviderId == providerSpec.id
        }
        val savedEndpoint = if (shouldReuseSavedValues) llmConfig.endpoint.trim() else ""
        val savedModel = if (shouldReuseSavedValues) llmConfig.model.trim() else ""
        val savedApiKey = if (shouldReuseSavedValues) llmConfig.apiKey else ""
        val endpointOverrideEnabled = savedEndpoint.isNotBlank() &&
                savedEndpoint != providerSpec.officialEndpoint
        val endpointInput = if (endpointOverrideEnabled) {
            savedEndpoint
        } else {
            providerSpec.officialEndpoint
        }
        val lastCustomEndpointInput = if (endpointOverrideEnabled) {
            savedEndpoint
        } else {
            providerSpec.officialEndpoint
        }
        updateState {
            copy(
                scene = ConfigureScene.Onboarding,
                providerSpec = providerSpec,
                endpointOverrideEnabled = endpointOverrideEnabled,
                endpointInput = endpointInput,
                lastCustomEndpointInput = lastCustomEndpointInput,
                modelInput = savedModel.ifBlank { providerSpec.exampleModelId },
                apiKeyInput = savedApiKey,
                apiKeyVisible = false,
                endpointErrorResId = null,
                modelErrorResId = null,
                apiKeyErrorResId = null,
                promptInput = "",
                proxyInput = "",
                proxyErrorResId = null,
                isSaving = false,
                inlineError = null,
                initialSettingsSnapshot = null,
            )
        }
    }

    private fun initializeSettings(llmConfig: LlmConfig, initialProviderId: String?) {
        val providerSpec = ProviderSpecs.find(
            initialProviderId?.takeIf { it.isNotBlank() }
                ?: llmConfig.provider.takeIf { it.isNotBlank() },
        )
        val endpointInput = llmConfig.endpoint.trim().ifBlank {
            providerSpec.officialEndpoint
        }
        val modelInput = llmConfig.model.trim().ifBlank {
            providerSpec.exampleModelId
        }
        updateState {
            copy(
                scene = ConfigureScene.Settings,
                providerSpec = providerSpec,
                endpointOverrideEnabled = true,
                endpointInput = endpointInput,
                lastCustomEndpointInput = endpointInput,
                modelInput = modelInput,
                apiKeyInput = llmConfig.apiKey,
                apiKeyVisible = false,
                endpointErrorResId = null,
                modelErrorResId = null,
                apiKeyErrorResId = null,
                promptInput = llmConfig.prompt,
                proxyInput = llmConfig.proxy,
                proxyErrorResId = null,
                isSaving = false,
                inlineError = null,
            ).withCurrentSettingsSnapshotAsInitial()
        }
    }

    private fun initializeSettingsProviderSwitch(llmConfig: LlmConfig, initialProviderId: String?) {
        val savedProviderId = llmConfig.provider.takeIf { it.isNotBlank() }
        val providerSpec = ProviderSpecs.find(
            initialProviderId?.takeIf { it.isNotBlank() } ?: savedProviderId,
        )
        val keepApiKey = savedProviderId == providerSpec.id
        updateState {
            copy(
                scene = ConfigureScene.SettingsProviderSwitch,
                providerSpec = providerSpec,
                endpointOverrideEnabled = false,
                endpointInput = providerSpec.officialEndpoint,
                lastCustomEndpointInput = providerSpec.officialEndpoint,
                modelInput = providerSpec.exampleModelId,
                apiKeyInput = if (keepApiKey) llmConfig.apiKey else "",
                apiKeyVisible = false,
                endpointErrorResId = null,
                modelErrorResId = null,
                apiKeyErrorResId = null,
                promptInput = llmConfig.prompt,
                proxyInput = llmConfig.proxy,
                proxyErrorResId = null,
                isSaving = false,
                inlineError = null,
            ).withCurrentSettingsSnapshotAsInitial()
        }
    }

    private fun setEndpointOverride(enabled: Boolean) {
        updateState {
            val nextEndpointInput = if (enabled) {
                lastCustomEndpointInput
            } else {
                providerSpec.officialEndpoint
            }
            val nextLastCustomEndpointInput = if (enabled) {
                lastCustomEndpointInput
            } else {
                endpointInput
            }
            copy(
                endpointOverrideEnabled = enabled,
                endpointInput = nextEndpointInput,
                lastCustomEndpointInput = nextLastCustomEndpointInput,
                endpointErrorResId = null,
                inlineError = null,
            )
        }
    }

    private fun updateEndpoint(value: String) {
        updateState {
            copy(
                endpointInput = value,
                lastCustomEndpointInput = if (endpointOverrideEnabled) {
                    value
                } else {
                    lastCustomEndpointInput
                },
                endpointErrorResId = null,
                inlineError = null,
            )
        }
    }

    private fun updateModel(value: String) {
        updateState {
            copy(
                modelInput = value,
                modelErrorResId = null,
                inlineError = null,
            )
        }
    }

    private fun updateApiKey(value: String) {
        updateState {
            copy(
                apiKeyInput = value,
                apiKeyErrorResId = null,
                inlineError = null,
            )
        }
    }

    private fun updatePrompt(value: String) {
        updateState {
            copy(
                promptInput = value,
                inlineError = null,
            )
        }
    }

    private fun updateProxy(value: String) {
        updateState {
            copy(
                proxyInput = value,
                proxyErrorResId = null,
                inlineError = null,
            )
        }
    }

    private fun toggleApiKeyVisibility() {
        updateState {
            copy(
                apiKeyVisible = !apiKeyVisible,
                inlineError = null,
            )
        }
    }

    private suspend fun save() {
        if (currentState.isSaving) {
            return
        }
        when (currentState.firstInvalidField()) {
            ConfigureFieldTarget.Model -> {
                updateState {
                    copy(
                        modelErrorResId = R.string.ui_settings_configure_error_required,
                        inlineError = null,
                    )
                }
                sendEffect(ConfigureEffect.FocusModel)
                return
            }

            ConfigureFieldTarget.ApiKey -> {
                updateState {
                    copy(
                        apiKeyErrorResId = R.string.ui_settings_configure_error_required,
                        inlineError = null,
                    )
                }
                sendEffect(ConfigureEffect.FocusApiKey)
                return
            }

            ConfigureFieldTarget.Endpoint -> {
                updateState {
                    copy(
                        endpointErrorResId = R.string.ui_settings_configure_error_required,
                        inlineError = null,
                    )
                }
                sendEffect(ConfigureEffect.FocusEndpoint)
                return
            }

            ConfigureFieldTarget.Proxy -> {
                updateState {
                    copy(
                        proxyErrorResId = R.string.ui_settings_configure_error_proxy_invalid,
                        inlineError = null,
                    )
                }
                sendEffect(ConfigureEffect.FocusProxy)
                return
            }

            null -> Unit
        }
        when (currentState.scene) {
            ConfigureScene.Onboarding -> saveOnboarding()
            ConfigureScene.Settings,
            ConfigureScene.SettingsProviderSwitch -> saveSettings()
        }
    }

    private suspend fun saveOnboarding() {
        updateState {
            copy(
                isSaving = true,
                endpointErrorResId = null,
                modelErrorResId = null,
                apiKeyErrorResId = null,
                inlineError = null,
            )
        }
        try {
            dependencies.saveLlmAccess(
                currentState.providerSpec.id,
                currentState.resolvedEndpoint(),
                currentState.modelInput,
                currentState.apiKeyInput,
            )
            updateState {
                copy(
                    isSaving = false,
                    endpointErrorResId = null,
                    modelErrorResId = null,
                    apiKeyErrorResId = null,
                    inlineError = null,
                )
            }
            sendEffect(ConfigureEffect.OnboardingSaveSucceeded)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            val message = throwable.message ?: throwable::class.java.simpleName
            val reason = ConfigureErrorReason.SaveSettingsFailed(message)
            updateState {
                copy(
                    isSaving = false,
                    inlineError = ConfigureInlineError.SaveFailed(reason),
                )
            }
            sendEffect(ConfigureEffect.SaveFailed(reason))
        }
    }

    private suspend fun saveSettings() {
        updateState {
            copy(
                isSaving = true,
                endpointErrorResId = null,
                modelErrorResId = null,
                apiKeyErrorResId = null,
                proxyErrorResId = null,
                inlineError = null,
            )
        }
        try {
            val currentConfig = dependencies.loadLlmConfig()
            dependencies.saveLlmConfig(
                currentConfig.copy(
                    provider = currentState.providerSpec.id,
                    endpoint = currentState.resolvedEndpoint(),
                    model = currentState.modelInput,
                    apiKey = currentState.apiKeyInput,
                    prompt = currentState.promptInput,
                    proxy = currentState.proxyInput.trim(),
                ),
            )
            updateState {
                copy(
                    isSaving = false,
                    endpointErrorResId = null,
                    modelErrorResId = null,
                    apiKeyErrorResId = null,
                    proxyErrorResId = null,
                    inlineError = null,
                ).withCurrentSettingsSnapshotAsInitial()
            }
            sendEffect(ConfigureEffect.SettingsSaveSucceeded)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            val message = throwable.message ?: throwable::class.java.simpleName
            val reason = ConfigureErrorReason.SaveSettingsFailed(message)
            updateState {
                copy(
                    isSaving = false,
                    inlineError = ConfigureInlineError.SaveFailed(reason),
                )
            }
            sendEffect(ConfigureEffect.SaveFailed(reason))
        }
    }
}

private fun ConfigureUiState.resolvedEndpoint(): String {
    return if (scene == ConfigureScene.Settings || scene == ConfigureScene.SettingsProviderSwitch || endpointOverrideEnabled) {
        endpointInput.trim().ifBlank { providerSpec.officialEndpoint }
    } else {
        providerSpec.officialEndpoint
    }
}

private fun ConfigureUiState.toSettingsSnapshot(): ConfigureSnapshot {
    return ConfigureSnapshot(
        providerId = providerSpec.id,
        endpoint = resolvedEndpoint(),
        model = modelInput.trim(),
        apiKey = apiKeyInput,
        prompt = promptInput,
        proxy = proxyInput.trim(),
    )
}

private fun ConfigureUiState.withCurrentSettingsSnapshotAsInitial(): ConfigureUiState {
    return copy(initialSettingsSnapshot = toSettingsSnapshot())
}

private enum class ConfigureFieldTarget {
    Endpoint,
    Model,
    ApiKey,
    Proxy,
}

private fun ConfigureUiState.firstInvalidField(): ConfigureFieldTarget? {
    return when {
        modelInput.trim().isBlank() -> ConfigureFieldTarget.Model
        apiKeyInput.trim().isBlank() -> ConfigureFieldTarget.ApiKey
        endpointOverrideEnabled && endpointInput.trim().isBlank() -> ConfigureFieldTarget.Endpoint
        scene == ConfigureScene.Settings && !isValidProxyUri(proxyInput) -> ConfigureFieldTarget.Proxy
        else -> null
    }
}

private fun isValidProxyUri(value: String): Boolean {
    val trimmedValue = value.trim()
    if (trimmedValue.isBlank()) {
        return true
    }
    return runCatching {
        val uri = URI(trimmedValue)
        !uri.scheme.isNullOrBlank() && !uri.host.isNullOrBlank()
    }.getOrDefault(false)
}
