package com.niki914.nexus.agentic.app.ui.nexus.model

import androidx.annotation.StringRes
import androidx.lifecycle.viewModelScope
import com.niki914.nexus.agentic.app.R
import com.niki914.nexus.agentic.repo.XRepo
import com.niki914.nexus.base.ComposeMVIViewModel
import com.niki914.nexus.xposed.api.util.xTry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URI
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeMcpServer as McpServer

data class McpServerItem(
    val name: String,
    val url: String,
    val enabled: Boolean,
    val headers: Map<String, String> = emptyMap(),
)

data class McpServerFormState(
    val editingIndex: Int? = null,
    val name: String = "",
    val url: String = "",
    val enabled: Boolean = true,
    val headersInput: String = "",
    val initialSnapshot: McpServerFormSnapshot? = null,
    @param:StringRes val nameErrorResId: Int? = null,
    @param:StringRes val urlErrorResId: Int? = null,
    @param:StringRes val headersErrorResId: Int? = null,
)

data class McpServerFormSnapshot(
    val name: String,
    val url: String,
    val enabled: Boolean,
    val headers: Map<String, String>?,
    val headersInputFallback: String,
)

val McpServerFormState.hasUnsavedChanges: Boolean
    get() = initialSnapshot?.let { it != toSnapshot() } ?: false

data class McpDeleteConfirmationState(
    val value: String,
)

data class McpSettingsUiState(
    val items: List<McpServerItem> = emptyList(),
    val formState: McpServerFormState = McpServerFormState(),
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val inlineError: McpInlineError? = null,
    val deleteConfirmation: McpDeleteConfirmationState? = null,
)

sealed interface McpSettingsIntent {
    data object Load : McpSettingsIntent
    data object StartCreate : McpSettingsIntent
    data class StartEdit(val index: Int) : McpSettingsIntent
    data class ItemEnabledChanged(val index: Int, val value: Boolean) : McpSettingsIntent
    data class NameChanged(val value: String) : McpSettingsIntent
    data class UrlChanged(val value: String) : McpSettingsIntent
    data class HeadersChanged(val value: String) : McpSettingsIntent
    data class EnabledChanged(val value: Boolean) : McpSettingsIntent
    data object Save : McpSettingsIntent
    data object RequestDelete : McpSettingsIntent
    data object DismissDeleteConfirmation : McpSettingsIntent
    data object ConfirmDelete : McpSettingsIntent
}

sealed interface McpInlineError {
    data class LoadFailed(val message: String?, @StringRes val fallbackResId: Int) : McpInlineError
    data class SaveFailed(val message: String?, @StringRes val fallbackResId: Int) : McpInlineError
    data class DeleteFailed(val message: String?, @StringRes val fallbackResId: Int) :
        McpInlineError
}

sealed interface McpSettingsEffect {
    data object ExitDetail : McpSettingsEffect
    data object FocusName : McpSettingsEffect
    data object FocusUrl : McpSettingsEffect
    data object FocusHeaders : McpSettingsEffect
}

class McpSettingsViewModel :
    ComposeMVIViewModel<McpSettingsIntent, McpSettingsUiState, McpSettingsEffect>() {

    init {
        viewModelScope.launch {
            settingsChanges.collect {
                load()
            }
        }
    }

    override fun initUiState(): McpSettingsUiState = McpSettingsUiState()

    override suspend fun handleIntent(intent: McpSettingsIntent) {
        when (intent) {
            McpSettingsIntent.Load -> load()
            McpSettingsIntent.StartCreate -> startCreate()
            is McpSettingsIntent.StartEdit -> startEdit(intent.index)
            is McpSettingsIntent.ItemEnabledChanged -> toggleItemEnabled(
                index = intent.index,
                enabled = intent.value,
            )

            is McpSettingsIntent.NameChanged -> updateState {
                copy(
                    formState = formState.copy(
                        name = intent.value,
                        nameErrorResId = null,
                    ),
                    inlineError = null,
                )
            }

            is McpSettingsIntent.UrlChanged -> updateState {
                copy(
                    formState = formState.copy(
                        url = intent.value,
                        urlErrorResId = null,
                    ),
                    inlineError = null,
                )
            }

            is McpSettingsIntent.HeadersChanged -> updateHeaders(intent.value)
            is McpSettingsIntent.EnabledChanged -> updateState {
                copy(
                    formState = formState.copy(enabled = intent.value),
                    inlineError = null,
                )
            }

            McpSettingsIntent.Save -> save()
            McpSettingsIntent.RequestDelete -> requestDelete()
            McpSettingsIntent.DismissDeleteConfirmation -> updateState {
                copy(deleteConfirmation = null)
            }

            McpSettingsIntent.ConfirmDelete -> confirmDelete()
        }
    }

    private suspend fun load() {
        updateState { copy(isLoading = true) }
        try {
            val loadedItems = XRepo.mcp.list().map { it.toItem() }
            updateState {
                copy(
                    items = loadedItems,
                    isLoading = false,
                    inlineError = null,
                )
            }
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            updateState {
                copy(
                    isLoading = false,
                    inlineError = McpInlineError.LoadFailed(
                        message = throwable.message,
                        fallbackResId = R.string.error_mcp_load_failed,
                    ),
                )
            }
        }
    }

    private fun startCreate() {
        val formState = McpServerFormState()
        updateState {
            copy(
                formState = formState.withCurrentSnapshotAsInitial(),
                inlineError = null,
            )
        }
    }

    private fun startEdit(index: Int) {
        val item = currentState.items.getOrNull(index) ?: return
        val formState = McpServerFormState(
            editingIndex = index,
            name = item.name,
            url = item.url,
            enabled = item.enabled,
            headersInput = headersToInput(item.headers),
        )
        updateState {
            copy(
                formState = formState.withCurrentSnapshotAsInitial(),
                inlineError = null,
            )
        }
    }

    private fun updateHeaders(value: String) {
        updateState {
            copy(
                formState = formState.copy(
                    headersInput = value,
                    headersErrorResId = null,
                ),
                inlineError = null,
            )
        }
    }

    private suspend fun toggleItemEnabled(index: Int, enabled: Boolean) {
        val currentItem = currentState.items.getOrNull(index) ?: return
        val previousItems = currentState.items
        val updatedItems = previousItems.toMutableList().apply {
            this[index] = currentItem.copy(enabled = enabled)
        }
        updateState {
            copy(
                items = updatedItems,
                formState = if (formState.editingIndex == index) {
                    formState.copy(enabled = enabled).withCurrentSnapshotAsInitial()
                } else {
                    formState
                },
                isSaving = true,
                inlineError = null,
            )
        }
        try {
            XRepo.mcp.setEnabled(currentItem.name, enabled)
            updateState {
                copy(
                    isSaving = false,
                )
            }
            notifySettingsChanged()
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            updateState {
                copy(
                    items = previousItems,
                    formState = if (formState.editingIndex == index) {
                        formState.copy(enabled = previousItems[index].enabled)
                            .withCurrentSnapshotAsInitial()
                    } else {
                        formState
                    },
                    isSaving = false,
                )
            }
        }
    }

    private suspend fun save() {
        val formState = currentState.formState
        val trimmedName = formState.name.trim()
        val trimmedUrl = formState.url.trim()
        val editingIndex = formState.editingIndex
        val headersResult = normalizedHeadersOrError(formState.headersInput)
        val nameErrorResId = when {
            trimmedName.isBlank() -> R.string.mcp_error_name_required
            currentState.items.anyIndexed { index, item ->
                item.name == trimmedName && index != editingIndex
            } -> R.string.mcp_error_name_duplicate

            else -> null
        }
        val urlErrorResId = when {
            trimmedUrl.isBlank() -> R.string.mcp_error_url_required
            !isValidUrl(trimmedUrl) -> R.string.mcp_error_url_invalid
            else -> null
        }
        val headersErrorResId = (headersResult as? McpHeadersParseResult.Error)?.messageResId
        if (nameErrorResId != null || urlErrorResId != null || headersErrorResId != null) {
            updateState {
                copy(
                    formState = formState.copy(
                        name = trimmedName,
                        url = trimmedUrl,
                        nameErrorResId = nameErrorResId,
                        urlErrorResId = urlErrorResId,
                        headersErrorResId = headersErrorResId,
                    ),
                    isSaving = false,
                    inlineError = null,
                )
            }
            firstInvalidFieldEffect(
                nameErrorResId = nameErrorResId,
                urlErrorResId = urlErrorResId,
                headersErrorResId = headersErrorResId,
            )?.let { effect ->
                sendEffect(effect)
            }
            return
        }

        val headers = (headersResult as McpHeadersParseResult.Success).headers
        updateState {
            copy(
                formState = formState.copy(
                    name = trimmedName,
                    url = trimmedUrl,
                    nameErrorResId = null,
                    urlErrorResId = null,
                    headersErrorResId = null,
                ),
                isSaving = true,
                inlineError = null,
            )
        }
        try {
            val previousName = editingIndex
                ?.let { currentState.items.getOrNull(it)?.name }
            val nextItem = McpServerItem(
                name = trimmedName,
                url = trimmedUrl,
                enabled = formState.enabled,
                headers = headers,
            )
            val updatedItems = currentState.items.toMutableList().also { mutableItems ->
                if (editingIndex == null || editingIndex !in mutableItems.indices) {
                    mutableItems += nextItem
                } else {
                    mutableItems[editingIndex] = nextItem
                }
            }
            if (previousName != null && previousName != nextItem.name) {
                XRepo.mcp.replace(previousName, nextItem.toRepo())
            } else {
                XRepo.mcp.save(nextItem.toRepo())
            }
            updateState {
                copy(
                    items = updatedItems,
                    formState = formState.copy(
                        editingIndex = updatedItems.indexOf(nextItem),
                        name = trimmedName,
                        url = trimmedUrl,
                        headersInput = headersToInput(headers),
                        nameErrorResId = null,
                        urlErrorResId = null,
                        headersErrorResId = null,
                    ).withCurrentSnapshotAsInitial(),
                    isSaving = false,
                    inlineError = null,
                )
            }
            notifySettingsChanged()
            sendEffect(McpSettingsEffect.ExitDetail)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            updateState {
                copy(
                    isSaving = false,
                    inlineError = McpInlineError.SaveFailed(
                        message = throwable.message,
                        fallbackResId = R.string.error_mcp_save_failed,
                    ),
                )
            }
        }
    }

    private fun requestDelete() {
        val editingIndex = currentState.formState.editingIndex ?: return
        val value = currentState.items.getOrNull(editingIndex)?.name ?: return
        updateState {
            copy(
                deleteConfirmation = McpDeleteConfirmationState(value = value),
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
            val updatedItems =
                currentState.items.filterIndexed { index, _ -> index != editingIndex }
            XRepo.mcp.delete(currentItem.name)
            updateState {
                copy(
                    items = updatedItems,
                    formState = McpServerFormState(),
                    isSaving = false,
                    inlineError = null,
                )
            }
            notifySettingsChanged()
            sendEffect(McpSettingsEffect.ExitDetail)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            updateState {
                copy(
                    isSaving = false,
                    inlineError = McpInlineError.DeleteFailed(
                        message = throwable.message,
                        fallbackResId = R.string.error_mcp_delete_failed,
                    ),
                )
            }
        }
    }

    private fun notifySettingsChanged() {
        settingsChanges.tryEmit(Unit)
    }

    private companion object {
        val settingsChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    }
}

private sealed interface McpHeadersParseResult {
    data class Success(val headers: Map<String, String>) : McpHeadersParseResult
    data class Error(@param:StringRes val messageResId: Int) : McpHeadersParseResult
}

private fun normalizedHeadersOrError(input: String): McpHeadersParseResult {
    if (input.isBlank()) {
        return McpHeadersParseResult.Success(emptyMap())
    }
    val element = try {
        Json.parseToJsonElement(input)
    } catch (_: Throwable) {
        return McpHeadersParseResult.Error(R.string.mcp_error_headers_invalid_json)
    }
    val jsonObject = element as? JsonObject
        ?: return McpHeadersParseResult.Error(R.string.mcp_error_headers_not_object)
    val headers = linkedMapOf<String, String>()
    jsonObject.forEach { (key, value) ->
        if (key.isBlank()) {
            return McpHeadersParseResult.Error(R.string.mcp_error_headers_empty_key)
        }
        val primitive = value as? JsonPrimitive
            ?: return McpHeadersParseResult.Error(R.string.mcp_error_headers_non_string)
        val content = primitive.contentOrNull
            ?: return McpHeadersParseResult.Error(R.string.mcp_error_headers_non_string)
        headers[key] = content
    }
    return McpHeadersParseResult.Success(headers.toSortedMap())
}

private fun McpServerFormState.toSnapshot(): McpServerFormSnapshot {
    val headersResult = normalizedHeadersOrError(headersInput)
    return McpServerFormSnapshot(
        name = name.trim(),
        url = url.trim(),
        enabled = enabled,
        headers = (headersResult as? McpHeadersParseResult.Success)?.headers,
        headersInputFallback = if (headersResult is McpHeadersParseResult.Success) {
            ""
        } else {
            headersInput
        },
    )
}

private fun McpServerFormState.withCurrentSnapshotAsInitial(): McpServerFormState {
    return copy(initialSnapshot = toSnapshot())
}

private fun headersToInput(headers: Map<String, String>): String {
    if (headers.isEmpty()) return ""
    val normalizedEntries = headers.toSortedMap().entries.toList()
    return buildString {
        appendLine("{")
        normalizedEntries.forEachIndexed { index, (key, value) ->
            append("  ")
            append(JsonPrimitive(key).toString())
            append(": ")
            append(JsonPrimitive(value).toString())
            if (index != normalizedEntries.lastIndex) {
                append(',')
            }
            appendLine()
        }
        append("}")
    }
}

private fun isValidUrl(value: String): Boolean {
    return xTry("McpSettingsState.isValidUrl") {
        val uri = URI(value)
        val scheme = uri.scheme?.lowercase()
        val isHttpScheme = scheme == "http" || scheme == "https"
        isHttpScheme && !uri.host.isNullOrBlank()
    } ?: false
}

private fun firstInvalidFieldEffect(
    nameErrorResId: Int?,
    urlErrorResId: Int?,
    headersErrorResId: Int?,
): McpSettingsEffect? {
    return when {
        nameErrorResId != null -> McpSettingsEffect.FocusName
        urlErrorResId != null -> McpSettingsEffect.FocusUrl
        headersErrorResId != null -> McpSettingsEffect.FocusHeaders
        else -> null
    }
}

private inline fun <T> List<T>.anyIndexed(predicate: (Int, T) -> Boolean): Boolean {
    forEachIndexed { index, item ->
        if (predicate(index, item)) {
            return true
        }
    }
    return false
}

private fun McpServer.toItem(): McpServerItem {
    return McpServerItem(
        name = name,
        url = url,
        enabled = enabled,
        headers = headers,
    )
}

private fun McpServerItem.toRepo(): McpServer {
    return McpServer(
        name = name,
        url = url,
        enabled = enabled,
        headers = headers,
    )
}
