// githooks:ignore-file file-token-limit
package com.niki914.zafiro.app.ui.model

import android.content.Context
import androidx.annotation.StringRes
import androidx.lifecycle.viewModelScope
import com.niki914.logging.Logger
import com.niki914.okia.message.ThinkingLevel
import com.niki914.uikit.base.ComposeMVIViewModel
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.settings.form.buildFormSpec
import com.niki914.zafiro.repo.CloudModelFacts
import com.niki914.zafiro.repo.LlmConfigsDocument
import com.niki914.zafiro.repo.ModelCatalog
import com.niki914.zafiro.repo.ModelCatalogApi
import com.niki914.zafiro.repo.SavedLlmConfig
import com.niki914.zafiro.repo.TokenCountText
import com.niki914.zafiro.repo.XRepo
import com.niki914.zafiro.api.util.Debouncer
import com.niki914.zafiro.api.util.debouncer
import com.niki914.zafiro.api.util.runCatchingCancellable
import com.niki914.zafiro.api.util.tryIgnore
import com.niki914.zafiro.settings.model.LlmProtocol
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import java.net.URI
import java.util.UUID

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
    val providerBrandName: String = "",
    val modelId: String,
    val isActive: Boolean,
)

internal fun summariesOf(document: LlmConfigsDocument): List<SavedConfigSummary> {
    return document.configs
        .sortedBy(SavedLlmConfig::createdAt)
        .map { config ->
            val providerSpec = ProviderSpecs.find(config.provider)
            SavedConfigSummary(
                id = config.id,
                name = config.name.ifBlank { providerSpec.brandName },
                providerBrandName = providerSpec.brandName,
                modelId = config.model,
                isActive = config.id == document.activeId,
            )
        }
}

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
    /** ThinkingLevel.wireValue；空串 = 不发送思考字段（Provider 默认行为）。 */
    val thinkingLevelWire: String = "",
    /** 视觉模型开关（图片输入支持），默认开。 */
    val supportsImages: Boolean = true,
    @param:StringRes val nameErrorResId: Int? = null,
    @param:StringRes val endpointErrorResId: Int? = null,
    @param:StringRes val modelErrorResId: Int? = null,
    @param:StringRes val apiKeyErrorResId: Int? = null,
    val promptInput: String = "",
    val proxyInput: String = "",
    @param:StringRes val proxyErrorResId: Int? = null,
    /** 单次输出上限输入框；未设置时回显默认值，清空 = 不设置（落默认值）。 */
    val maxTokensInput: String = "",
    @param:StringRes val maxTokensErrorResId: Int? = null,
    /** 上下文窗口输入框（环的分母）；留空 = 不设置，由 gateway 兜底（默认 1M）。 */
    val contextWindowInput: String = "",
    @param:StringRes val contextWindowErrorResId: Int? = null,
    val isSaving: Boolean = false,
    val inlineError: ConfigureInlineError? = null,
    val initialSettingsSnapshot: ConfigureSnapshot? = null,
    val savedConfigs: List<SavedConfigSummary> = emptyList(),
    val activeConfigId: String? = null,
    /** 非 null 时应弹出端点不匹配确认弹窗。 */
    val pendingEndpointMismatch: EndpointMismatch? = null,
    /** 模型目录扫描缓存：为空 = 不显示扫描按钮；非空 = 显示按钮。 */
    val modelCatalog: List<String> = emptyList(),
    /** 非 null 时应弹出模型选择单。 */
    val showModelCatalogSheet: Boolean = false,
)

data class ConfigureSnapshot(
    val configName: String,
    val providerId: String,
    val endpoint: String,
    val model: String,
    val apiKey: String,
    val protocolWireId: String,
    val thinkingLevelWire: String,
    val supportsImages: Boolean,
    val prompt: String,
    val proxy: String,
    val maxTokens: String,
    val contextWindow: String,
)

val ConfigureFormSpec = buildFormSpec<ConfigureSnapshot, String> {
    field("name", R.string.ui_settings_configure_name_label) { it.configName }
    field("prompt", R.string.ui_settings_configure_prompt_label) { it.prompt }
    field("proxy", R.string.ui_settings_configure_proxy_label) { it.proxy }
    field("endpoint", R.string.ui_onboard_configure_endpoint_label) { it.endpoint }
    field("model", R.string.ui_onboard_configure_model_label) { it.model }
    field("apiKey", R.string.ui_onboard_configure_api_key_label) { it.apiKey }
    field("protocol", R.string.ui_settings_configure_protocol_label) { it.protocolWireId }
    field("thinking", R.string.ui_settings_configure_thinking_label) { it.thinkingLevelWire }
    field("vision", R.string.ui_settings_configure_vision_label) { it.supportsImages }
    field("maxTokens", R.string.ui_settings_configure_max_tokens_label) { it.maxTokens }
    field("contextWindow", R.string.ui_settings_configure_context_window_label) { it.contextWindow }
}

val ConfigureUiState.hasUnsavedChanges: Boolean
    get() = scene != ConfigureScene.Onboarding &&
            ConfigureFormSpec.hasChanges(initialSettingsSnapshot, toSettingsSnapshot())

fun ConfigureUiState.unsavedFieldLabelRes(): List<Int> {
    return ConfigureFormSpec.dirtyFieldLabelRes(initialSettingsSnapshot, toSettingsSnapshot())
}

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
    data class UpdateThinkingLevel(val wireValue: String) : ConfigureIntent
    data class UpdateSupportsImages(val enabled: Boolean) : ConfigureIntent
    data class UpdatePrompt(val value: String) : ConfigureIntent
    data class UpdateProxy(val value: String) : ConfigureIntent
    data class UpdateMaxTokens(val value: String) : ConfigureIntent
    data class UpdateContextWindow(val value: String) : ConfigureIntent
    data object ToggleApiKeyVisibility : ConfigureIntent
    data class ActivateConfig(val configId: String) : ConfigureIntent
    data class DeleteConfig(val configId: String) : ConfigureIntent
    data object Save : ConfigureIntent
    data object ShowModelCatalogSheet : ConfigureIntent
    data object HideModelCatalogSheet : ConfigureIntent
    data class SelectCatalogModel(val modelId: String) : ConfigureIntent

    /** 确认端点不匹配弹窗：点击更新。 */
    data object ConfirmEndpointMismatch : ConfigureIntent

    /** 确认端点不匹配弹窗：点击保留/直接保存。 */
    data object CancelEndpointMismatch : ConfigureIntent
}

sealed interface ConfigureEffect {
    data object OnboardingSaveSucceeded : ConfigureEffect
    data object SettingsSaveSucceeded : ConfigureEffect
    data class SaveFailed(val reason: ConfigureErrorReason) : ConfigureEffect
    data object FocusName : ConfigureEffect
    data object FocusModel : ConfigureEffect
    data object FocusApiKey : ConfigureEffect
    data object FocusEndpoint : ConfigureEffect
    data object FocusProxy : ConfigureEffect

    /** 配置删除成功，详情页应退出。 */
    data object ConfigDeleted : ConfigureEffect
}

/** 端点与协议不匹配时的弹窗参数。
 *  [Origin] 区分触发来源，决定弹窗文案与确认后的行为。 */
data class EndpointMismatch(
    val protocol: String,
    val expectedEndpoint: String,
    val currentEndpoint: String,
    val origin: Origin,
) {
    enum class Origin { SwitchProtocol, Save }
}

internal data class ConfigureViewModelDependencies(
    val loadDocument: suspend () -> LlmConfigsDocument,
    val upsertConfig: suspend (SavedLlmConfig) -> String?,
    val deleteConfig: suspend (String) -> Unit,
    val setActiveConfig: suspend (String) -> Unit,
    val fetchModelCatalog: suspend (modelsUrl: String, apiKey: String, protocol: LlmProtocol) -> ModelCatalog,
    val saveModelFacts: suspend (endpoint: String, factsByModel: Map<String, CloudModelFacts>) -> Unit,
    val revisions: Flow<Long> = emptyFlow(),
) {
    companion object {
        val Default = ConfigureViewModelDependencies(
            loadDocument = { XRepo.llmConfigs.document() },
            upsertConfig = { XRepo.llmConfigs.upsert(it) },
            deleteConfig = { XRepo.llmConfigs.delete(it) },
            setActiveConfig = { XRepo.llmConfigs.setActive(it) },
            fetchModelCatalog = { modelsUrl, apiKey, protocol ->
                ModelCatalogApi.fetch(modelsUrl, apiKey, protocol)
            },
            saveModelFacts = { endpoint, factsByModel ->
                XRepo.cloudFacts.saveProviderFacts(endpoint, factsByModel)
            },
            revisions = XRepo.llmConfigs.revision,
        )
    }
}

class ConfigureViewModel internal constructor(
    private val dependencies: ConfigureViewModelDependencies,
) : ComposeMVIViewModel<ConfigureIntent, ConfigureUiState, ConfigureEffect>() {
    constructor() : this(ConfigureViewModelDependencies.Default)

    init {
        viewModelScope.launch {
            dependencies.revisions.collect {
                refreshSavedConfigs()
            }
        }
    }

    private suspend fun refreshSavedConfigs() {
        tryIgnore {
            val document = dependencies.loadDocument()
            updateState {
                copy(
                    savedConfigs = summariesOf(document),
                    activeConfigId = document.activeId,
                )
            }
        }
    }

    override fun initUiState(): ConfigureUiState = ConfigureUiState()

    private companion object {
        private const val LOG_TAG = "niki914_zafiro_ConfigureViewModel"

        /** 输入防抖：停手 50ms 才发； trailing + 取消在途 + 三元组去重。 */
        private const val CATALOG_DEBOUNCE_MS = 50L
    }

    /** 已初始化过的入参；重进本页（返回上一页再回来）不重置已填内容。 */
    private var initializedKey: Triple<ConfigureScene, String?, String?>? = null

    /** 在途的目录拉取（防抖 + 可取消）。 */
    private val catalogDebouncer: Debouncer = viewModelScope.debouncer(CATALOG_DEBOUNCE_MS)

    /** 上次已拉取的三元组（endpoint/apiKey/protocol），去重用。 */
    private var lastCatalogKey: CatalogKey? = null

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
            }.also { scheduleCatalogFetch() }

            is ConfigureIntent.UpdateModel -> updateState {
                copy(modelInput = intent.value, modelErrorResId = null, inlineError = null)
            }

            is ConfigureIntent.UpdateApiKey -> updateState {
                copy(apiKeyInput = intent.value, apiKeyErrorResId = null, inlineError = null)
            }.also { scheduleCatalogFetch() }

            is ConfigureIntent.SelectProtocol -> handleProtocolSwitch(intent.wireId)

            is ConfigureIntent.UpdateThinkingLevel -> updateState {
                copy(thinkingLevelWire = intent.wireValue)
            }

            is ConfigureIntent.UpdateSupportsImages -> updateState {
                copy(supportsImages = intent.enabled)
            }

            is ConfigureIntent.UpdatePrompt -> updateState {
                copy(promptInput = intent.value, inlineError = null)
            }

            is ConfigureIntent.UpdateProxy -> updateState {
                copy(proxyInput = intent.value, proxyErrorResId = null, inlineError = null)
            }

            is ConfigureIntent.UpdateMaxTokens -> updateState {
                copy(maxTokensInput = intent.value, maxTokensErrorResId = null, inlineError = null)
            }

            is ConfigureIntent.UpdateContextWindow -> updateState {
                copy(
                    contextWindowInput = intent.value,
                    contextWindowErrorResId = null,
                    inlineError = null,
                )
            }

            ConfigureIntent.ToggleApiKeyVisibility -> updateState {
                copy(apiKeyVisible = !apiKeyVisible)
            }

            is ConfigureIntent.ActivateConfig -> activateConfig(intent.configId)
            is ConfigureIntent.DeleteConfig -> deleteConfig(intent.configId)
            ConfigureIntent.Save -> handleSave()
            ConfigureIntent.ConfirmEndpointMismatch -> confirmEndpointMismatch()
            ConfigureIntent.CancelEndpointMismatch -> cancelEndpointMismatch()
            ConfigureIntent.ShowModelCatalogSheet -> updateState {
                copy(showModelCatalogSheet = true)
            }

            ConfigureIntent.HideModelCatalogSheet -> updateState {
                copy(showModelCatalogSheet = false)
            }

            is ConfigureIntent.SelectCatalogModel -> updateState {
                copy(
                    modelInput = intent.modelId,
                    modelErrorResId = null,
                    inlineError = null,
                    showModelCatalogSheet = false,
                )
            }
        }
    }

    private suspend fun initialize(
        scene: ConfigureScene,
        initialProviderId: String?,
        configId: String?,
    ) {
        // 页面离开组合后返回会重新触发 Initialize；同一个 VM（= 同一条导航条目）
        // 只认第一次，否则 onboarding 填好的草稿会被抹掉、保存时还会撞上重名校验。
        val key = Triple(scene, initialProviderId, configId)
        if (key == initializedKey) return
        // 拉取三元组跨初始化重置：不同配置页实例互不污染
        lastCatalogKey = null
        catalogDebouncer.cancel()
        runCatchingCancellable {
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
            // 成功才登记：读取失败时重进本页仍可重试
            initializedKey = key
            // 进页拉一次（Key 为空时内部短路，不发请求）
            scheduleCatalogFetch(immediate = true)
        }.onFailure { throwable ->
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
                // 个别 Provider 可提供稳定别名作为默认值；仍可在输入框中手动修改
                modelInput = providerSpec.initialModelId.orEmpty(),
                apiKeyInput = "",
                apiKeyVisible = false,
                protocolWireId = providerSpec.defaultProtocol,
                thinkingLevelWire = ThinkingLevel.Default.wireValue,
                nameErrorResId = null,
                endpointErrorResId = null,
                modelErrorResId = null,
                apiKeyErrorResId = null,
                promptInput = "",
                proxyInput = "",
                proxyErrorResId = null,
                // 不预填：留空让云端/默认值生效（预填会钉死用户值，云端永远不生效）
                maxTokensInput = "",
                maxTokensErrorResId = null,
                contextWindowInput = "",
                contextWindowErrorResId = null,
                isSaving = false,
                inlineError = null,
                initialSettingsSnapshot = null,
                savedConfigs = summariesOf(document),
                activeConfigId = document.activeId,
                // 新会话：目录缓存不跨页复用（同 VM 复用时旧名单不清会误回填）
                modelCatalog = emptyList(),
                showModelCatalogSheet = false,
            )
            next
        }
    }

    private fun defaultUniqueConfigName(brandName: String, existing: List<SavedLlmConfig>): String {
        val existingNames = existing.map { it.name.trim() }.toSet()
        if (brandName !in existingNames) return brandName
        var counter = 2
        while ("$brandName $counter" in existingNames) {
            counter++
        }
        return "$brandName $counter"
    }

    private fun initializeNew(document: LlmConfigsDocument, initialProviderId: String?) {
        val providerSpec = ProviderSpecs.find(initialProviderId)
        val defaultName = defaultUniqueConfigName(providerSpec.brandName, document.configs)
        updateState {
            val next = copy(
                scene = ConfigureScene.SettingsNew,
                providerSpec = providerSpec,
                editingConfigId = null,
                // 默认名 = 品牌名（若重名则带编号），用户可改
                configNameInput = defaultName,
                endpointOverrideEnabled = false,
                endpointInput = providerSpec.officialEndpoint,
                // 个别 Provider 可提供稳定别名作为默认值；仍可在输入框中手动修改
                modelInput = providerSpec.initialModelId.orEmpty(),
                apiKeyInput = "",
                apiKeyVisible = false,
                protocolWireId = providerSpec.defaultProtocol,
                thinkingLevelWire = ThinkingLevel.Default.wireValue,
                nameErrorResId = null,
                endpointErrorResId = null,
                modelErrorResId = null,
                apiKeyErrorResId = null,
                promptInput = "",
                proxyInput = "",
                proxyErrorResId = null,
                // 不预填：留空让云端/默认值生效（预填会钉死用户值，云端永远不生效）
                maxTokensInput = "",
                maxTokensErrorResId = null,
                contextWindowInput = "",
                contextWindowErrorResId = null,
                isSaving = false,
                inlineError = null,
                savedConfigs = summariesOf(document),
                activeConfigId = document.activeId,
                // 新会话：目录缓存不跨页复用（同 VM 复用时旧名单不清会误回填）
                modelCatalog = emptyList(),
                showModelCatalogSheet = false,
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
                thinkingLevelWire = target.thinkingLevel,
                supportsImages = target.supportsImages,
                nameErrorResId = null,
                endpointErrorResId = null,
                modelErrorResId = null,
                apiKeyErrorResId = null,
                promptInput = target.prompt,
                proxyInput = target.proxy,
                proxyErrorResId = null,
                // 落盘原始文本：空串 = 未设置（旧格式的 0 已在 codec 层归一为空），填过才回显用户值
                maxTokensInput = target.maxTokensText,
                maxTokensErrorResId = null,
                contextWindowInput = target.contextWindowText,
                contextWindowErrorResId = null,
                isSaving = false,
                inlineError = null,
                savedConfigs = summariesOf(document),
                activeConfigId = document.activeId,
                // 新会话：目录缓存不跨页复用（同 VM 复用时旧名单不清会误回填）
                modelCatalog = emptyList(),
                showModelCatalogSheet = false,
            )
            // 快照必须取自初始化后的状态，否则未修改也会被判为 dirty
            next.copy(initialSettingsSnapshot = next.toSettingsSnapshot())
        }
    }

    /** 模型目录自动拉取：50ms trailing + 取消在途 + 三元组去重 + 空值短路。
     *  缓存语义：成功覆盖（含空结果，空 = 藏按钮）；失败与空值短路保留旧缓存，只记日志；
     *  仅当 modelInput 为空时用首个结果回填（手填内容永不覆盖）。 */
    private fun scheduleCatalogFetch(immediate: Boolean = false) {
        // 注意：handleIntent 已跑在 viewModelScope 的 intent 串行通道里；
        // 这里走 Debouncer 防抖，取消语义经 debouncer 传递
        val delayMs = if (immediate) 0L else CATALOG_DEBOUNCE_MS
        catalogDebouncer.debounce(delayMs) {
            val state = currentState
            val key = CatalogKey(
                endpoint = state.endpointInput.trim(),
                apiKey = state.apiKeyInput.trim(),
                protocolWireId = state.protocolWireId,
            )
            if (key == lastCatalogKey) return@debounce
            // 空值短路：Key 或 endpoint 为空直接不发，保留旧缓存
            if (key.endpoint.isBlank() || key.apiKey.isBlank()) return@debounce
            val protocol = LlmProtocol.fromWire(key.protocolWireId)
            val modelsUrl = EndpointInference.modelsUrl(key.endpoint, protocol)
                ?: return@debounce
            lastCatalogKey = key
            runCatchingCancellable {
                val catalog = dependencies.fetchModelCatalog(modelsUrl, key.apiKey, protocol)
                // 云端事实随同一次响应落缓存：以 endpoint+model 为键，与「保存」解耦。
                // 全缺字段（如 Google）时 facts 为空，不写，旧缓存保留。
                if (catalog.facts.isNotEmpty()) {
                    runCatchingCancellable {
                        dependencies.saveModelFacts(key.endpoint, catalog.facts)
                    }.onFailure { throwable ->
                        Logger.w(LOG_TAG, "save model facts failed: ${throwable.message}")
                    }
                }
                val ids = catalog.ids
                // 成功覆盖：空结果也写入（藏按钮），失败走 catch 保留旧缓存
                updateState {
                    // 没走 Provider 默认 model ID（编辑区为空）时，拉到的首个直接回填，免开选择单
                    if (modelInput.isBlank() && ids.isNotEmpty()) {
                        copy(
                            modelCatalog = ids,
                            modelInput = ids.first(),
                            modelErrorResId = null,
                            inlineError = null,
                            showModelCatalogSheet = false,
                        )
                    } else {
                        copy(modelCatalog = ids)
                    }
                }
            }.onFailure { throwable ->
                Logger.w(LOG_TAG, "catalog fetch failed: ${throwable.message}")
            }
        }
    }

    private data class CatalogKey(
        val endpoint: String,
        val apiKey: String,
        val protocolWireId: String,
    )

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
        scheduleCatalogFetch()
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

    private fun handleProtocolSwitch(newProtocolWireId: String) {
        if (newProtocolWireId == currentState.protocolWireId) {
            return
        }
        val newProtocol = LlmProtocol.fromWire(newProtocolWireId)
        val currentEndpoint = currentState.endpointInput.trim()
        // 空白端点不校验不弹窗：保存时由必填校验报错，用户自己解决
        if (currentEndpoint.isBlank() ||
            EndpointInference.endpointMatchesProtocol(currentEndpoint, newProtocol)
        ) {
            switchProtocolQuietly(newProtocolWireId)
            return
        }
        // 预置官方端点：静默更新后缀，不弹窗；自定义端点：确认后只换后缀
        if (EndpointInference.isPresetEndpoint(currentEndpoint)) {
            updateState {
                copy(
                    endpointInput = EndpointInference.replaceSuffix(currentEndpoint, newProtocol),
                    protocolWireId = newProtocolWireId,
                )
            }
            scheduleCatalogFetch()
            return
        }
        updateState {
            copy(
                pendingEndpointMismatch = EndpointMismatch(
                    protocol = newProtocolWireId,
                    expectedEndpoint = EndpointInference.replaceSuffix(
                        currentEndpoint,
                        newProtocol
                    ),
                    currentEndpoint = currentEndpoint,
                    origin = EndpointMismatch.Origin.SwitchProtocol,
                )
            )
        }
    }

    private suspend fun handleSave() {
        // 先字段校验，再端点确认
        if (!validate()) return
        val currentProtocol = LlmProtocol.fromWire(currentState.protocolWireId)
        val currentEndpoint = currentState.endpointInput.trim()
        if (EndpointInference.endpointMatchesProtocol(currentEndpoint, currentProtocol)) {
            saveConfig()
            return
        }
        // 预置官方端点：静默更新后缀，不弹窗；自定义端点：确认后只换后缀
        if (EndpointInference.isPresetEndpoint(currentEndpoint)) {
            updateState {
                copy(
                    endpointInput = EndpointInference.replaceSuffix(
                        currentEndpoint,
                        currentProtocol
                    )
                )
            }
            saveConfig()
            return
        }
        updateState {
            copy(
                pendingEndpointMismatch = EndpointMismatch(
                    protocol = currentState.protocolWireId,
                    expectedEndpoint = EndpointInference.replaceSuffix(
                        currentEndpoint,
                        currentProtocol
                    ),
                    currentEndpoint = currentEndpoint,
                    origin = EndpointMismatch.Origin.Save,
                )
            )
        }
    }

    private suspend fun confirmEndpointMismatch() {
        val pending = currentState.pendingEndpointMismatch ?: return
        updateState {
            copy(
                endpointInput = pending.expectedEndpoint,
                endpointErrorResId = null,
                inlineError = null,
                pendingEndpointMismatch = null,
                protocolWireId = when (pending.origin) {
                    EndpointMismatch.Origin.SwitchProtocol -> pending.protocol
                    EndpointMismatch.Origin.Save -> protocolWireId
                },
            )
        }
        if (pending.origin == EndpointMismatch.Origin.Save) {
            save()
        }
    }

    private suspend fun cancelEndpointMismatch() {
        val pending = currentState.pendingEndpointMismatch ?: return
        updateState { copy(pendingEndpointMismatch = null) }
        when (pending.origin) {
            EndpointMismatch.Origin.SwitchProtocol -> {
                switchProtocolQuietly(pending.protocol)
            }

            EndpointMismatch.Origin.Save -> {
                save()
            }
        }
    }

    private fun switchProtocolQuietly(wireId: String) {
        updateState { copy(protocolWireId = wireId) }
        scheduleCatalogFetch()
    }

    /** 校验重名与必填字段；不通过时置错误并发焦点 Effect。 */
    private suspend fun validate(): Boolean {
        if (currentState.isSaving) {
            return false
        }
        val trimmedName = currentState.configNameInput.trim()
        val duplicateExists = dependencies.loadDocument().configs.any {
            it.id != currentState.editingConfigId && it.name.trim() == trimmedName
        }
        if (duplicateExists) {
            updateState {
                copy(nameErrorResId = R.string.ui_settings_configure_error_name_duplicate)
            }
            sendEffect(ConfigureEffect.FocusName)
            return false
        }
        when (val invalidField = currentState.firstInvalidField()) {
            ConfigureFieldTarget.Model -> {
                updateState { copy(modelErrorResId = R.string.ui_settings_configure_error_required) }
                sendEffect(ConfigureEffect.FocusModel)
                return false
            }

            ConfigureFieldTarget.ApiKey -> {
                updateState { copy(apiKeyErrorResId = R.string.ui_settings_configure_error_required) }
                sendEffect(ConfigureEffect.FocusApiKey)
                return false
            }

            ConfigureFieldTarget.Endpoint -> {
                updateState {
                    copy(endpointErrorResId = R.string.ui_settings_configure_error_required)
                }
                sendEffect(ConfigureEffect.FocusEndpoint)
                return false
            }

            ConfigureFieldTarget.Proxy -> {
                updateState {
                    copy(proxyErrorResId = R.string.ui_settings_configure_error_proxy_invalid)
                }
                sendEffect(ConfigureEffect.FocusProxy)
                return false
            }

            ConfigureFieldTarget.MaxTokens -> {
                updateState {
                    copy(maxTokensErrorResId = R.string.ui_settings_configure_error_max_tokens_invalid)
                }
                return false
            }

            ConfigureFieldTarget.ContextWindow -> {
                updateState {
                    copy(
                        contextWindowErrorResId =
                            R.string.ui_settings_configure_error_context_window_invalid,
                    )
                }
                return false
            }

            null -> Unit
        }
        return true
    }

    private suspend fun save() {
        if (validate()) {
            saveConfig()
        }
    }

    private suspend fun saveConfig() {
        updateState {
            copy(isSaving = true, inlineError = null)
        }
        runCatchingCancellable {
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
        }.onFailure { throwable ->
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
        return "cfg-" + UUID.randomUUID().toString().replace("-", "").take(12)
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
        thinkingLevel = thinkingLevelWire,
        supportsImages = supportsImages,
        prompt = promptInput.trim(),
        proxy = proxyInput,
        maxTokensText = maxTokensInput.trim(),
        contextWindowText = contextWindowInput.trim(),
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
        thinkingLevelWire = thinkingLevelWire,
        supportsImages = supportsImages,
        prompt = promptInput.trim(),
        proxy = proxyInput.trim(),
        maxTokens = maxTokensInput.trim(),
        contextWindow = contextWindowInput.trim(),
    )
}

private enum class ConfigureFieldTarget {
    Endpoint,
    Model,
    ApiKey,
    Proxy,
    MaxTokens,
    ContextWindow,
}

private fun ConfigureUiState.firstInvalidField(): ConfigureFieldTarget? {
    return when {
        // 与填写顺序一致：API Key → Model → Endpoint → Proxy → MaxTokens → ContextWindow
        apiKeyInput.trim().isBlank() -> ConfigureFieldTarget.ApiKey
        modelInput.trim().isBlank() -> ConfigureFieldTarget.Model
        endpointOverrideEnabled && endpointInput.trim().isBlank() -> ConfigureFieldTarget.Endpoint
        isValidProxy(proxyInput).not() -> ConfigureFieldTarget.Proxy
        isValidMaxTokens(maxTokensInput).not() -> ConfigureFieldTarget.MaxTokens
        isValidContextWindow(contextWindowInput).not() -> ConfigureFieldTarget.ContextWindow
        else -> null
    }
}

/** 上下文窗口：留空 = 不设置（云端/默认兑底）；填了就必须能解析且 ≥ [MIN_CONTEXT_WINDOW]。 */
private fun isValidContextWindow(value: String): Boolean {
    val trimmedValue = value.trim()
    if (trimmedValue.isBlank()) {
        return true
    }
    val parsed = TokenCountText.parse(trimmedValue) ?: return false
    return parsed >= MIN_CONTEXT_WINDOW
}

private const val MIN_CONTEXT_WINDOW = 1_000L

/** 单次输出上限：留空 = 不设置（云端/默认兑底）；填了就必须能解析且 ≥ [MIN_MAX_TOKENS]。 */
private fun isValidMaxTokens(value: String): Boolean {
    val trimmedValue = value.trim()
    if (trimmedValue.isBlank()) {
        return true
    }
    val parsed = TokenCountText.parse(trimmedValue) ?: return false
    return parsed >= MIN_MAX_TOKENS && parsed <= Int.MAX_VALUE
}

private const val MIN_MAX_TOKENS = 256

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
