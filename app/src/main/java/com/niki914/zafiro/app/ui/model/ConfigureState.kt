package com.niki914.zafiro.app.ui.model

import androidx.annotation.StringRes
import com.niki914.logging.Logger
import com.niki914.uikit.base.ComposeMVIViewModel
import com.niki914.zafiro.app.R
import com.niki914.zafiro.repo.LlmConfigsDocument
import com.niki914.zafiro.repo.SavedLlmConfig
import com.niki914.zafiro.repo.XRepo
import com.niki914.zafiro.settings.model.LlmProtocol
import kotlinx.coroutines.CancellationException
import java.net.URI

enum class ConfigureScene {
    /** 首次引导：保存即创建第一份配置并置 active。 */
    Onboarding,

    /** 设置页 Add 入口进入：品牌已选定，保存即新建并置 active。 */
    SettingsNew,

    /** 编辑列表中已有的某份配置；保存不改变 active 归属。 */
    SettingsEdit,
}

data class SavedConfigSummary(
    val id: String,
    val name: String,
    val modelId: String,
    val isActive: Boolean,
)

data class ConfigureUiState(
    val scene: ConfigureScene = ConfigureScene.Onboarding,
    val providerSpec: ProviderSpec = ProviderSpecs.default,
    /** null = 新建草稿（尚未持久化）。 */
    val editingConfigId: String? = null,
    val configNameInput: String = "",
    val endpointOverrideEnabled: Boolean = false,
    val endpointInput: String = ProviderSpecs.default.officialEndpoint,
    val modelInput: String = "",
    val apiKeyInput: String = "",
    val apiKeyVisible: Boolean = false,
    /** LlmProtocol.wireId。 */
    val protocolWireId: String = LlmProtocol.Default.wireId,
    @param:StringRes val nameErrorResId: Int? = null,
    @param:StringRes val endpointErrorResId: Int? = null,
    @param:StringRes val modelErrorResId: Int? = null,
    @param:StringRes val apiKeyErrorResId: Int? = null,
    /** 全局 system prompt（页面级，不随 saved config 切换）。 */
    val promptInput: String = "",
    val proxyInput: String = "",
    @param:StringRes val proxyErrorResId: Int? = null,
    val isSaving: Boolean = false,
    val inlineError: ConfigureInlineError? = null,
    val initialSettingsSnapshot: ConfigureSnapshot? = null,
    val savedConfigs: List<SavedConfigSummary> = emptyList(),
    val activeConfigId: String? = null,
)

data class ConfigureSnapshot(
    val configName: String,
    val providerId: String,
    val endpoint: String,
    val model: String,
    val apiKey: String,
    val protocolWireId: String,
    val proxy: String,
    val prompt: String,
)

val ConfigureUiState.hasUnsavedChanges: Boolean
    get() = scene != ConfigureScene.Onboarding &&
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
        val scene: ConfigureScene,
        val providerId: String? = null,
        val configId: String? = null,
    ) : ConfigureIntent

    data class UpdateName(val value: String) : ConfigureIntent
    data class SetEndpointOverride(val enabled: Boolean) : ConfigureIntent
    data class UpdateEndpoint(val value: String) : ConfigureIntent
    data class UpdateModel(val value: String) : ConfigureIntent
    data class UpdateApiKey(val value: String) : ConfigureIntent
    data class SelectProtocol(val wireId: String) : ConfigureIntent
    data class UpdatePrompt(val value: String) : ConfigureIntent

    /** 仅持久化全局 prompt（列表页防抖自动保存）。 */
    data object SavePrompt : ConfigureIntent
    data class UpdateProxy(val value: String) : ConfigureIntent
    data object ToggleApiKeyVisibility : ConfigureIntent
    data class ActivateConfig(val configId: String) : ConfigureIntent
    data class DeleteConfig(val configId: String) : ConfigureIntent
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

    /** 配置删除成功，详情页应退出。 */
    data object ConfigDeleted : ConfigureEffect
}

internal data class ConfigureViewModelDependencies(
    val loadDocument: suspend () -> LlmConfigsDocument,
    val upsertConfig: suspend (SavedLlmConfig) -> String?,
    val deleteConfig: suspend (String) -> Unit,
    val setActiveConfig: suspend (String) -> Unit,
    val saveGlobalPrompt: suspend (String) -> Unit,
) {
    companion object {
        val Default = ConfigureViewModelDependencies(
            loadDocument = { XRepo.llmConfigs.document() },
            upsertConfig = { XRepo.llmConfigs.upsert(it) },
            deleteConfig = { XRepo.llmConfigs.delete(it) },
            setActiveConfig = { XRepo.llmConfigs.setActive(it) },
            saveGlobalPrompt = { XRepo.llmConfigs.savePrompt(it) },
        )
    }
}

class ConfigureViewModel internal constructor(
    private val dependencies: ConfigureViewModelDependencies,
) : ComposeMVIViewModel<ConfigureIntent, ConfigureUiState, ConfigureEffect>() {
    constructor() : this(ConfigureViewModelDependencies.Default)

    override fun initUiState(): ConfigureUiState = ConfigureUiState()

    private companion object {
        private const val LOG_TAG = "niki914_nexus_ConfigureViewModel"
    }

    override suspend fun handleIntent(intent: ConfigureIntent) {
        when (intent) {
            is ConfigureIntent.Initialize ->
                initialize(intent.scene, intent.providerId, intent.configId)

            is ConfigureIntent.UpdateName -> updateState {
                copy(configNameInput = intent.value, nameErrorResId = null, inlineError = null)
            }

            is ConfigureIntent.SetEndpointOverride -> setEndpointOverride(intent.enabled)

            is ConfigureIntent.UpdateEndpoint -> updateState {
                copy(endpointInput = intent.value, endpointErrorResId = null, inlineError = null)
            }

            is ConfigureIntent.UpdateModel -> updateState {
                copy(modelInput = intent.value, modelErrorResId = null, inlineError = null)
            }

            is ConfigureIntent.UpdateApiKey -> updateState {
                copy(apiKeyInput = intent.value, apiKeyErrorResId = null, inlineError = null)
            }

            is ConfigureIntent.SelectProtocol -> updateState {
                copy(protocolWireId = intent.wireId)
            }

            is ConfigureIntent.UpdatePrompt -> updateState {
                copy(promptInput = intent.value)
            }

            is ConfigureIntent.UpdateProxy -> updateState {
                copy(proxyInput = intent.value, proxyErrorResId = null, inlineError = null)
            }

            ConfigureIntent.ToggleApiKeyVisibility -> updateState {
                copy(apiKeyVisible = !apiKeyVisible)
            }

            is ConfigureIntent.ActivateConfig -> activateConfig(intent.configId)
            is ConfigureIntent.DeleteConfig -> deleteConfig(intent.configId)
            ConfigureIntent.Save -> save()
            ConfigureIntent.SavePrompt -> savePromptOnly()
        }
    }

    private suspend fun initialize(
        scene: ConfigureScene,
        initialProviderId: String?,
        configId: String?,
    ) {
        try {
            val document = dependencies.loadDocument()
            Logger.d(LOG_TAG, "initialize scene=$scene configs=${document.configs.size}")
            when (scene) {
                ConfigureScene.Onboarding ->
                    initializeOnboarding(document, initialProviderId)

                ConfigureScene.SettingsNew ->
                    initializeNew(document, initialProviderId)

                ConfigureScene.SettingsEdit ->
                    // configId 缺省时编辑当前生效配置；不存在则回落新建
                    initializeEdit(document, configId ?: document.activeId)
            }
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            val message = throwable.message ?: throwable::class.java.simpleName
            Logger.w(LOG_TAG, "initialize failed scene=$scene reason=$message")
            val reason = ConfigureErrorReason.LoadSettingsFailed(message)
            updateState {
                copy(
                    scene = scene,
                    isSaving = false,
                    inlineError = ConfigureInlineError.LoadFailed(reason)
                )
            }
        }
    }

    private fun summariesOf(document: LlmConfigsDocument): List<SavedConfigSummary> {
        return document.configs
            .sortedBy(SavedLlmConfig::createdAt)
            .map { config ->
                SavedConfigSummary(
                    id = config.id,
                    name = config.name.ifBlank { config.model },
                    modelId = config.model,
                    isActive = config.id == document.activeId,
                )
            }
    }

    private fun initializeOnboarding(document: LlmConfigsDocument, initialProviderId: String?) {
        val resolvedProviderId = initialProviderId?.takeIf(String::isNotBlank)
            ?: ProviderSpecs.default.id
        val providerSpec = ProviderSpecs.find(resolvedProviderId)
        updateState {
            val next = copy(
                scene = ConfigureScene.Onboarding,
                providerSpec = providerSpec,
                editingConfigId = null,
                configNameInput = providerSpec.brandName,
                endpointOverrideEnabled = false,
                endpointInput = providerSpec.officialEndpoint,
                modelInput = providerSpec.exampleModelId,
                apiKeyInput = "",
                apiKeyVisible = false,
                protocolWireId = providerSpec.defaultProtocol,
                nameErrorResId = null,
                endpointErrorResId = null,
                modelErrorResId = null,
                apiKeyErrorResId = null,
                promptInput = document.prompt,
                proxyInput = "",
                proxyErrorResId = null,
                isSaving = false,
                inlineError = null,
                initialSettingsSnapshot = null,
                savedConfigs = summariesOf(document),
                activeConfigId = document.activeId,
            )
            next
        }
    }

    private fun initializeNew(document: LlmConfigsDocument, initialProviderId: String?) {
        val providerSpec = ProviderSpecs.find(initialProviderId)
        updateState {
            val next = copy(
                scene = ConfigureScene.SettingsNew,
                providerSpec = providerSpec,
                editingConfigId = null,
                // 默认名 = 品牌名，用户可改
                configNameInput = providerSpec.brandName,
                endpointOverrideEnabled = false,
                endpointInput = providerSpec.officialEndpoint,
                modelInput = providerSpec.exampleModelId,
                apiKeyInput = "",
                apiKeyVisible = false,
                protocolWireId = providerSpec.defaultProtocol,
                nameErrorResId = null,
                endpointErrorResId = null,
                modelErrorResId = null,
                apiKeyErrorResId = null,
                promptInput = document.prompt,
                proxyInput = "",
                proxyErrorResId = null,
                isSaving = false,
                inlineError = null,
                savedConfigs = summariesOf(document),
                activeConfigId = document.activeId,
            )
            // 快照必须取自初始化后的状态，否则未修改也会被判为 dirty
            next.copy(initialSettingsSnapshot = next.toSettingsSnapshot())
        }
    }

    private fun initializeEdit(document: LlmConfigsDocument, configId: String?) {
        val target = configId?.let { id ->
            document.configs.firstOrNull { it.id == id.trim() }
        }
        if (target == null) {
            // 目标配置不存在（已删除等）：按新建处理，品牌回落默认
            initializeNew(document, null)
            return
        }
        val providerSpec = ProviderSpecs.find(target.provider)
        updateState {
            val next = copy(
                scene = ConfigureScene.SettingsEdit,
                providerSpec = providerSpec,
                editingConfigId = target.id,
                configNameInput = target.name,
                endpointOverrideEnabled = true,
                endpointInput = target.endpoint.trim().ifBlank { providerSpec.officialEndpoint },
                modelInput = target.model,
                apiKeyInput = target.apiKey,
                apiKeyVisible = false,
                protocolWireId = LlmProtocol.fromWire(target.protocol).wireId,
                nameErrorResId = null,
                endpointErrorResId = null,
                modelErrorResId = null,
                apiKeyErrorResId = null,
                promptInput = document.prompt,
                proxyInput = target.proxy,
                proxyErrorResId = null,
                isSaving = false,
                inlineError = null,
                savedConfigs = summariesOf(document),
                activeConfigId = document.activeId,
            )
            // 快照必须取自初始化后的状态，否则未修改也会被判为 dirty
            next.copy(initialSettingsSnapshot = next.toSettingsSnapshot())
        }
    }

    private fun setEndpointOverride(enabled: Boolean) {
        updateState {
            val nextEndpointInput = if (enabled) {
                endpointInput.takeIf(String::isNotBlank) ?: providerSpec.officialEndpoint
            } else {
                providerSpec.officialEndpoint
            }
            copy(
                endpointOverrideEnabled = enabled,
                endpointInput = nextEndpointInput,
                endpointErrorResId = null,
                inlineError = null,
            )
        }
    }

    private suspend fun activateConfig(configId: String) {
        dependencies.setActiveConfig(configId)
        updateState {
            copy(
                activeConfigId = configId,
                savedConfigs = savedConfigs.map { summary ->
                    summary.copy(isActive = summary.id == configId)
                },
            )
        }
    }

    private suspend fun savePromptOnly() {
        runCatching { dependencies.saveGlobalPrompt(currentState.promptInput) }
            .onFailure { throwable ->
                if (throwable is CancellationException) throw throwable
                Logger.w(LOG_TAG, "save prompt failed reason=${throwable.message}")
            }
    }

    private suspend fun deleteConfig(configId: String) {
        dependencies.deleteConfig(configId)
        val document = dependencies.loadDocument()
        updateState {
            copy(
                savedConfigs = summariesOf(document),
                activeConfigId = document.activeId,
                // 被删的是正在编辑的那份 → 表单切回新建态语义但场景不变，
                // 用户下一次 Save 即视为新建
                editingConfigId = editingConfigId?.takeIf { it != configId },
                initialSettingsSnapshot = initialSettingsSnapshot?.takeIf {
                    editingConfigId != configId
                },
            )
        }
        sendEffect(ConfigureEffect.ConfigDeleted)
    }

    private suspend fun save() {
        if (currentState.isSaving) {
            return
        }
        val trimmedName = currentState.configNameInput.trim()
        val duplicateExists = dependencies.loadDocument().configs.any {
            it.id != currentState.editingConfigId && it.name.trim() == trimmedName
        }
        if (duplicateExists) {
            updateState {
                copy(nameErrorResId = R.string.ui_settings_configure_error_name_duplicate)
            }
            return
        }
        when (val invalidField = currentState.firstInvalidField()) {
            ConfigureFieldTarget.Model -> {
                updateState { copy(modelErrorResId = R.string.ui_settings_configure_error_required) }
                sendEffect(ConfigureEffect.FocusModel)
                return
            }

            ConfigureFieldTarget.ApiKey -> {
                updateState { copy(apiKeyErrorResId = R.string.ui_settings_configure_error_required) }
                sendEffect(ConfigureEffect.FocusApiKey)
                return
            }

            ConfigureFieldTarget.Endpoint -> {
                updateState {
                    copy(endpointErrorResId = R.string.ui_settings_configure_error_required)
                }
                sendEffect(ConfigureEffect.FocusEndpoint)
                return
            }

            ConfigureFieldTarget.Proxy -> {
                updateState {
                    copy(proxyErrorResId = R.string.ui_settings_configure_error_proxy_invalid)
                }
                sendEffect(ConfigureEffect.FocusProxy)
                return
            }

            null -> Unit
        }
        saveConfig()
    }

    private suspend fun saveConfig() {
        updateState {
            copy(isSaving = true, inlineError = null)
        }
        try {
            val current = currentState
            val targetConfigId = current.editingConfigId ?: newConfigId()
            dependencies.upsertConfig(
                current.toSavedLlmConfig().copy(id = targetConfigId)
            )
            val refreshed = dependencies.loadDocument()
            updateState {
                val next = copy(
                    isSaving = false,
                    inlineError = null,
                    // 新建保存自动置 active（repo 层行为）；编辑保存不动归属
                    activeConfigId = if (current.editingConfigId == null) {
                        targetConfigId
                    } else {
                        refreshed.activeId
                    },
                    savedConfigs = summariesOf(refreshed),
                    editingConfigId = targetConfigId,
                )
                next.copy(initialSettingsSnapshot = next.toSettingsSnapshot())
            }
            when (currentState.scene) {
                ConfigureScene.Onboarding -> sendEffect(ConfigureEffect.OnboardingSaveSucceeded)
                ConfigureScene.SettingsNew,
                ConfigureScene.SettingsEdit,
                    -> sendEffect(ConfigureEffect.SettingsSaveSucceeded)
            }
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            val message = throwable.message ?: throwable::class.java.simpleName
            Logger.w(LOG_TAG, "save failed reason=$message")
            val reason = ConfigureErrorReason.SaveSettingsFailed(message)
            updateState {
                copy(isSaving = false, inlineError = ConfigureInlineError.SaveFailed(reason))
            }
            sendEffect(ConfigureEffect.SaveFailed(reason))
        }
    }

    private fun newConfigId(): String {
        return "cfg-" + java.util.UUID.randomUUID().toString().replace("-", "").take(12)
    }
}

private fun ConfigureUiState.toSavedLlmConfig(): SavedLlmConfig {
    return SavedLlmConfig(
        id = editingConfigId.orEmpty(),
        name = configNameInput,
        provider = providerSpec.id,
        endpoint = resolvedEndpoint(),
        apiKey = apiKeyInput,
        model = modelInput,
        protocol = protocolWireId,
        proxy = proxyInput,
        createdAt = 0L,
        updatedAt = 0L,
    )
}

private fun ConfigureUiState.resolvedEndpoint(): String {
    return endpointInput.trim().ifBlank { providerSpec.officialEndpoint }
}

private fun ConfigureUiState.toSettingsSnapshot(): ConfigureSnapshot {
    return ConfigureSnapshot(
        configName = configNameInput.trim(),
        providerId = providerSpec.id,
        endpoint = resolvedEndpoint(),
        model = modelInput.trim(),
        apiKey = apiKeyInput,
        protocolWireId = protocolWireId,
        proxy = proxyInput.trim(),
        prompt = promptInput,
    )
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
        isValidProxy(proxyInput).not() -> ConfigureFieldTarget.Proxy
        else -> null
    }
}

private fun isValidProxy(value: String): Boolean {
    val trimmedValue = value.trim()
    if (trimmedValue.isBlank()) {
        return true
    }
    return runCatching {
        val uri = URI(trimmedValue)
        !uri.scheme.isNullOrBlank() && !uri.host.isNullOrBlank()
    }.getOrDefault(false)
}
