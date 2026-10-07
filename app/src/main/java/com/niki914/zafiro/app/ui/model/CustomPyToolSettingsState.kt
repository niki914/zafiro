package com.niki914.zafiro.app.ui.model

import androidx.annotation.StringRes
import androidx.lifecycle.viewModelScope
import com.niki914.logging.Logger
import com.niki914.uikit.base.ComposeMVIViewModel
import com.niki914.zafiro.app.R
import com.niki914.zafiro.repo.XRepo
import com.niki914.zafiro.settings.model.RuntimeToolValidationOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import com.niki914.zafiro.settings.model.RuntimeCustomPyTool as CustomPyTool
import com.niki914.zafiro.settings.model.RuntimeToolValidation as ToolValidation

data class CustomPyToolItem(
    val name: String,
    val enabled: Boolean,
)

data class CustomPyToolFormState(
    val editingIndex: Int? = null,
    val previousName: String? = null,
    val name: String = "",
    val code: String = "",
    val enabled: Boolean = true,
    val initialSnapshot: CustomPyToolFormSnapshot? = null,
    @param:StringRes val nameErrorResId: Int? = null,
    @param:StringRes val codeErrorResId: Int? = null,
    val codeErrorMessage: String? = null,
)

data class CustomPyToolFormSnapshot(
    val name: String,
    val code: String,
    val enabled: Boolean,
)

val CustomPyToolFormState.hasUnsavedChanges: Boolean
    get() = initialSnapshot?.let { it != toSnapshot() } ?: false

data class CustomPyToolDeleteConfirmationState(
    val value: String,
)

/**
 * 保存进度，驱动保存弹窗的三态。
 *
 * 失败分两类：
 * - [CodeRejected]：工具代码自身的问题（语法错误、缺 main、参数缺标注、顶层代码抛异常等）；
 * - [InternalError]：应用侧问题（Python 运行时不可用、超时、写盘失败等）。
 *
 * 这两类需要让用户一眼分得清，所以不用一个 message 字符串带过，而是拆成两个状态。
 */
sealed interface CustomPyToolSaveProgress {
    data object Checking : CustomPyToolSaveProgress

    data object Succeeded : CustomPyToolSaveProgress

    data class CodeRejected(val message: String) : CustomPyToolSaveProgress

    data class InternalError(val message: String?) : CustomPyToolSaveProgress
}

data class CustomPyToolSettingsUiState(
    val items: List<CustomPyToolItem> = emptyList(),
    val formState: CustomPyToolFormState = CustomPyToolFormState(),
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val saveProgress: CustomPyToolSaveProgress? = null,
    val inlineError: CustomPyToolInlineError? = null,
    val deleteConfirmation: CustomPyToolDeleteConfirmationState? = null,
)

sealed interface CustomPyToolSettingsIntent {
    data object Load : CustomPyToolSettingsIntent
    data object StartCreate : CustomPyToolSettingsIntent
    data class StartEdit(val index: Int) : CustomPyToolSettingsIntent
    data class NameChanged(val value: String) : CustomPyToolSettingsIntent
    data class CodeChanged(val value: String) : CustomPyToolSettingsIntent
    data class EnabledChanged(val value: Boolean) : CustomPyToolSettingsIntent
    data object Save : CustomPyToolSettingsIntent
    data object CancelSave : CustomPyToolSettingsIntent
    data object ConfirmSaveSucceeded : CustomPyToolSettingsIntent
    data object DismissSaveFailed : CustomPyToolSettingsIntent
    data object RequestDelete : CustomPyToolSettingsIntent
    data object DismissDeleteConfirmation : CustomPyToolSettingsIntent
    data object ConfirmDelete : CustomPyToolSettingsIntent
}

sealed interface CustomPyToolInlineError {
    data class LoadFailed(val message: String?, @StringRes val fallbackResId: Int) :
        CustomPyToolInlineError

    data class SaveFailed(val message: String?, @StringRes val fallbackResId: Int) :
        CustomPyToolInlineError

    data class DeleteFailed(val message: String?, @StringRes val fallbackResId: Int) :
        CustomPyToolInlineError
}

sealed interface CustomPyToolSettingsEffect {
    data object ExitDetail : CustomPyToolSettingsEffect
    data object FocusName : CustomPyToolSettingsEffect
    data object FocusCode : CustomPyToolSettingsEffect
}

class CustomPyToolSettingsViewModel :
    ComposeMVIViewModel<
            CustomPyToolSettingsIntent,
            CustomPyToolSettingsUiState,
            CustomPyToolSettingsEffect,
            >() {

    init {
        viewModelScope.launch {
            settingsChanges.collect {
                load()
            }
        }
    }

    override fun initUiState(): CustomPyToolSettingsUiState = CustomPyToolSettingsUiState()

    /** 在途保存。取消 = 取消这个 Job；反射与写盘之间无副作用，所以取消不会留下半个工具。 */
    private var saveJob: Job? = null

    override suspend fun handleIntent(intent: CustomPyToolSettingsIntent) {
        when (intent) {
            CustomPyToolSettingsIntent.Load -> load()
            CustomPyToolSettingsIntent.StartCreate -> startCreate()
            is CustomPyToolSettingsIntent.StartEdit -> startEdit(intent.index)
            is CustomPyToolSettingsIntent.NameChanged -> updateState {
                copy(
                    formState = formState.copy(
                        name = intent.value,
                        nameErrorResId = null,
                    ),
                    inlineError = null,
                )
            }

            is CustomPyToolSettingsIntent.CodeChanged -> updateState {
                copy(
                    formState = formState.copy(
                        code = intent.value,
                        codeErrorResId = null,
                        codeErrorMessage = null,
                    ),
                    inlineError = null,
                )
            }

            is CustomPyToolSettingsIntent.EnabledChanged -> updateState {
                copy(
                    formState = formState.copy(enabled = intent.value),
                    inlineError = null,
                )
            }

            CustomPyToolSettingsIntent.Save -> save()
            CustomPyToolSettingsIntent.CancelSave -> cancelSave()
            CustomPyToolSettingsIntent.ConfirmSaveSucceeded -> confirmSaveSucceeded()
            CustomPyToolSettingsIntent.DismissSaveFailed -> dismissSaveFailed()
            CustomPyToolSettingsIntent.RequestDelete -> requestDelete()
            CustomPyToolSettingsIntent.DismissDeleteConfirmation -> updateState {
                copy(deleteConfirmation = null)
            }

            CustomPyToolSettingsIntent.ConfirmDelete -> confirmDelete()
        }
    }

    private suspend fun load() {
        updateState { copy(isLoading = true) }
        val startedAtMs = System.currentTimeMillis()
        try {
            val loadedItems = XRepo.customPyTools.list().map { it.toItem() }
            Logger.d(
                LOG_TAG,
                "load tools=${loadedItems.size} " +
                        "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
            )
            updateState {
                copy(
                    items = loadedItems,
                    isLoading = false,
                    inlineError = null,
                )
            }
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            Logger.w(LOG_TAG, "load failed reason=${throwable.message}")
            updateState {
                copy(
                    isLoading = false,
                    inlineError = CustomPyToolInlineError.LoadFailed(
                        message = throwable.message,
                        fallbackResId = R.string.error_custom_py_tool_load_failed,
                    ),
                )
            }
        }
    }

    private fun startCreate() {
        val formState = CustomPyToolFormState()
        updateState {
            copy(
                formState = formState.withCurrentSnapshotAsInitial(),
                inlineError = null,
            )
        }
    }

    private suspend fun startEdit(index: Int) {
        val item = currentState.items.getOrNull(index) ?: return
        val tool = try {
            XRepo.customPyTools.get(item.name)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            Logger.w(LOG_TAG, "startEdit load failed tool=${item.name} reason=${throwable.message}")
            null
        }
        if (tool == null) {
            updateState {
                copy(
                    inlineError = CustomPyToolInlineError.LoadFailed(
                        message = null,
                        fallbackResId = R.string.error_custom_py_tool_load_failed,
                    ),
                )
            }
            return
        }
        val formState = CustomPyToolFormState(
            editingIndex = index,
            previousName = tool.name,
            name = tool.name,
            code = tool.code,
            enabled = tool.enabled,
        )
        updateState {
            copy(
                formState = formState.withCurrentSnapshotAsInitial(),
                inlineError = null,
            )
        }
    }

    private fun save() {
        val formState = currentState.formState
        val normalizedFormState = formState.copy(
            name = formState.name.trim(),
            code = formState.code.trim(),
        )
        val requiredErrors = requiredFieldErrors(normalizedFormState)
        if (requiredErrors.hasErrors) {
            updateFormErrors(normalizedFormState, requiredErrors)
            firstInvalidFieldEffect(requiredErrors)?.let { effect ->
                sendEffect(effect)
            }
            return
        }

        // 反射要起 Python（秒级），所以放到独立协程里跑，不占 intent 通道，也能被取消。
        saveJob?.cancel()
        saveJob = viewModelScope.launch { runSave(normalizedFormState) }
    }

    private suspend fun runSave(formState: CustomPyToolFormState) {
        updateState {
            copy(
                formState = formState.copy(
                    nameErrorResId = null,
                    codeErrorResId = null,
                    codeErrorMessage = null,
                ),
                inlineError = null,
            )
        }
        try {
            // timeoutMs 不在 UI 编辑：编辑时保留原值，新建用默认值
            val previousTool = XRepo.customPyTools.get(
                formState.previousName ?: formState.name
            )
            val nextTool = CustomPyTool(
                name = formState.name,
                code = formState.code,
                enabled = formState.enabled,
                timeoutMs = previousTool?.timeoutMs
                    ?: CustomPyTool.DEFAULT_CUSTOM_PY_TOOL_TIMEOUT_MS,
            )
            // 便宜校验（名字、保留名、安全策略）先于耗时的反射，也先于弹窗：
            // 毫秒级的失败不该让弹窗闪一下
            XRepo.customPyTools.validate(nextTool)?.let { validation ->
                handleValidationError(formState, validation)
                return
            }
            // 到这里才真的要起 Python（秒级），弹窗与表单禁用同步开启
            updateState {
                copy(
                    isSaving = true,
                    saveProgress = CustomPyToolSaveProgress.Checking,
                )
            }
            val introspection = XRepo.customPyTools.introspect(nextTool.code)
            // 取消是協作式的，而这次调用打不断（Binder 调用）：它可能在用户点了取消之后才返回。
            // 所以任何结果落地之前都再确认一次——取消之后这次保存不该再打扰用户。
            currentCoroutineContext().ensureActive()
            introspection.error?.let { message ->
                Logger.w(
                    LOG_TAG,
                    "introspect rejected tool=${nextTool.name} " +
                            "origin=${introspection.origin}:$message"
                )
                val internalError = introspection.origin == RuntimeToolValidationOrigin.Internal
                val saveError = CustomPyToolInlineError.SaveFailed(
                    message = message,
                    fallbackResId = R.string.error_custom_py_tool_save_failed,
                )
                updateState {
                    copy(
                        // 代码问题标在代码框下方（带行号）；应用问题不冒充代码错误，只保留底部行内提示
                        formState = if (internalError) {
                            formState
                        } else {
                            formState.copy(codeErrorMessage = message, codeErrorResId = null)
                        },
                        isSaving = false,
                        inlineError = saveError.takeIf { internalError },
                        saveProgress = if (internalError) {
                            CustomPyToolSaveProgress.InternalError(message)
                        } else {
                            CustomPyToolSaveProgress.CodeRejected(message)
                        },
                    )
                }
                return
            }
            val validation = XRepo.customPyTools.save(
                nextTool.copy(
                    description = introspection.description.orEmpty(),
                    schemaJson = introspection.schemaJson.orEmpty(),
                ),
            )
            if (validation != null) {
                Logger.w(
                    LOG_TAG,
                    "save rejected tool=${nextTool.name} " +
                            "validation=${validation.field}:${validation.message}"
                )
                handleValidationError(formState, validation)
                return
            }
            if (formState.previousName != null &&
                formState.previousName != nextTool.name
            ) {
                XRepo.customPyTools.delete(formState.previousName)
            }
            Logger.i(LOG_TAG, "save succeeded tool=${nextTool.name}")

            val nextItem = nextTool.toItem()
            val updatedItems = buildUpdatedItems(formState.editingIndex, nextItem)
            updateState {
                copy(
                    items = updatedItems,
                    formState = formState.copy(
                        editingIndex = updatedItems.indexOf(nextItem),
                        previousName = nextTool.name,
                        nameErrorResId = null,
                        codeErrorResId = null,
                        codeErrorMessage = null,
                    ).withCurrentSnapshotAsInitial(),
                    isSaving = false,
                    inlineError = null,
                    saveProgress = CustomPyToolSaveProgress.Succeeded,
                )
            }
            notifySettingsChanged()
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            Logger.w(LOG_TAG, "save failed reason=${throwable.message}")
            updateState {
                copy(
                    isSaving = false,
                    inlineError = CustomPyToolInlineError.SaveFailed(
                        message = throwable.message,
                        fallbackResId = R.string.error_custom_py_tool_save_failed,
                    ),
                    saveProgress = CustomPyToolSaveProgress.InternalError(throwable.message),
                )
            }
        }
    }

    /**
     * 取消保存：留在原页面，关掉弹窗，不写盘。
     * 状态收尾由本 intent 完成，不依赖已被取消的协程。
     */
    private fun cancelSave() {
        if (currentState.saveProgress != CustomPyToolSaveProgress.Checking) return
        saveJob?.cancel()
        saveJob = null
        updateState {
            copy(
                isSaving = false,
                saveProgress = null,
            )
        }
    }

    private fun confirmSaveSucceeded() {
        if (currentState.saveProgress != CustomPyToolSaveProgress.Succeeded) return
        updateState { copy(saveProgress = null) }
        sendEffect(CustomPyToolSettingsEffect.ExitDetail)
    }

    /**
     * 关闭失败弹窗。失败来自工具代码时，再展开代码框并把光标放进去。
     *
     * 展开会顺带拉起键盘（SettingExpandableTextItem 把展开与 requestFocus 绑在一起），
     * 所以必须等弹窗关掉之后再做：否则键盘会顶在还开着的弹窗上。
     */
    private fun dismissSaveFailed() {
        val codeRejected = currentState.saveProgress is CustomPyToolSaveProgress.CodeRejected
        updateState { copy(saveProgress = null) }
        if (codeRejected) sendEffect(CustomPyToolSettingsEffect.FocusCode)
    }

    private fun requestDelete() {
        val editingIndex = currentState.formState.editingIndex ?: return
        val value = currentState.items.getOrNull(editingIndex)?.name ?: return
        updateState {
            copy(
                deleteConfirmation = CustomPyToolDeleteConfirmationState(value = value),
                inlineError = null,
            )
        }
    }

    private suspend fun confirmDelete() {
        val confirmation = currentState.deleteConfirmation ?: return
        updateState { copy(deleteConfirmation = null) }
        deleteCurrent()
    }

    private suspend fun deleteCurrent() {
        val editingIndex = currentState.formState.editingIndex ?: return
        val currentItem = currentState.items.getOrNull(editingIndex) ?: return
        updateState { copy(isSaving = true) }
        try {
            val updatedItems = currentState.items.filterIndexed { index, _ ->
                index != editingIndex
            }
            XRepo.customPyTools.delete(currentItem.name)
            Logger.i(LOG_TAG, "deleteCurrent succeeded tool=${currentItem.name}")
            updateState {
                copy(
                    items = updatedItems,
                    formState = CustomPyToolFormState(),
                    isSaving = false,
                    inlineError = null,
                )
            }
            notifySettingsChanged()
            sendEffect(CustomPyToolSettingsEffect.ExitDetail)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            Logger.w(
                LOG_TAG,
                "deleteCurrent failed tool=${currentItem.name} reason=${throwable.message}"
            )
            updateState {
                copy(
                    isSaving = false,
                    inlineError = CustomPyToolInlineError.DeleteFailed(
                        message = throwable.message,
                        fallbackResId = R.string.error_custom_py_tool_delete_failed,
                    ),
                )
            }
        }
    }

    private fun handleValidationError(
        formState: CustomPyToolFormState,
        validation: ToolValidation,
    ) {
        val errors = validationToFieldErrors(validation)
        if (errors.hasErrors) {
            updateFormErrors(formState, errors)
            firstInvalidFieldEffect(errors)?.let { effect ->
                sendEffect(effect)
            }
        } else {
            updateState {
                copy(
                    formState = formState,
                    isSaving = false,
                    inlineError = CustomPyToolInlineError.SaveFailed(
                        validation.message,
                        fallbackResId = R.string.error_custom_py_tool_save_failed
                    ),
                    // 校验结果一律走行内；弹窗只由反射结果与写盘异常触发
                    saveProgress = null,
                )
            }
        }
    }

    private fun updateFormErrors(
        formState: CustomPyToolFormState,
        errors: CustomPyToolFieldErrors,
    ) {
        updateState {
            copy(
                formState = formState.copy(
                    nameErrorResId = errors.nameErrorResId,
                    codeErrorResId = errors.codeErrorResId,
                    codeErrorMessage = errors.codeErrorMessage,
                ),
                isSaving = false,
                inlineError = null,
                // 弹窗只能由反射/写盘的结果关闭：校验失败在这里收尾，别把弹窗留在屏幕上
                saveProgress = null,
            )
        }
    }

    private fun buildUpdatedItems(
        editingIndex: Int?,
        nextItem: CustomPyToolItem,
    ): List<CustomPyToolItem> {
        return currentState.items.toMutableList().also { mutableItems ->
            if (editingIndex == null || editingIndex !in mutableItems.indices) {
                mutableItems += nextItem
            } else {
                mutableItems[editingIndex] = nextItem
            }
        }
    }

    private fun notifySettingsChanged() {
        settingsChanges.tryEmit(Unit)
    }

    private companion object {
        private const val LOG_TAG = "niki914_zafiro_CustomPyToolSettingsViewModel"
        val settingsChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    }
}

private data class CustomPyToolFieldErrors(
    @param:StringRes val nameErrorResId: Int? = null,
    @param:StringRes val codeErrorResId: Int? = null,
    val codeErrorMessage: String? = null,
) {
    val hasErrors: Boolean
        get() = nameErrorResId != null ||
                codeErrorResId != null ||
                codeErrorMessage != null
}

private fun CustomPyToolFormState.toSnapshot(): CustomPyToolFormSnapshot {
    return CustomPyToolFormSnapshot(
        name = name.trim(),
        code = code.trim(),
        enabled = enabled,
    )
}

private fun CustomPyToolFormState.withCurrentSnapshotAsInitial(): CustomPyToolFormState {
    return copy(initialSnapshot = toSnapshot())
}

private fun requiredFieldErrors(formState: CustomPyToolFormState): CustomPyToolFieldErrors {
    return CustomPyToolFieldErrors(
        nameErrorResId = if (formState.name.isBlank()) {
            R.string.custom_py_tool_error_name_required
        } else {
            null
        },
        codeErrorResId = if (formState.code.isBlank()) {
            R.string.custom_py_tool_error_code_required
        } else {
            null
        },
    )
}

private fun validationToFieldErrors(validation: ToolValidation): CustomPyToolFieldErrors {
    return when (validation.field) {
        "name" -> CustomPyToolFieldErrors(
            nameErrorResId = validation.nameErrorResId(),
        )

        "code" -> if (validation.message.contains("Required field", ignoreCase = true)) {
            CustomPyToolFieldErrors(codeErrorResId = R.string.custom_py_tool_error_code_required)
        } else {
            // 反射/安全策略的错误信息直接透传给用户（含行号与原因）
            CustomPyToolFieldErrors(codeErrorMessage = validation.message)
        }

        else -> CustomPyToolFieldErrors()
    }
}

private fun firstInvalidFieldEffect(
    errors: CustomPyToolFieldErrors,
): CustomPyToolSettingsEffect? {
    return when {
        errors.nameErrorResId != null -> CustomPyToolSettingsEffect.FocusName
        errors.codeErrorResId != null || errors.codeErrorMessage != null -> CustomPyToolSettingsEffect.FocusCode
        else -> null
    }
}

@StringRes
private fun ToolValidation.nameErrorResId(): Int {
    return when {
        message.contains("Already exists", ignoreCase = true) ->
            R.string.custom_py_tool_error_name_duplicate

        message.contains("Reserved builtin tool name", ignoreCase = true) ->
            R.string.custom_py_tool_error_name_reserved

        else -> R.string.custom_py_tool_error_name_invalid
    }
}

private fun CustomPyTool.toItem(): CustomPyToolItem {
    return CustomPyToolItem(
        name = name,
        enabled = enabled,
    )
}
