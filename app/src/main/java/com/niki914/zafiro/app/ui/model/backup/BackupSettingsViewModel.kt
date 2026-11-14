package com.niki914.zafiro.app.ui.model.backup

import androidx.lifecycle.viewModelScope
import com.niki914.uikit.base.ComposeMVIViewModel
import com.niki914.zafiro.app.R
import com.niki914.zafiro.backup.BackupGroup
import com.niki914.zafiro.backup.BackupStrategy
import kotlinx.coroutines.launch
import java.io.File

class BackupSettingsViewModel internal constructor(
    private val work: BackupSettingsWork,
) : ComposeMVIViewModel<BackupSettingsIntent, BackupSettingsUiState, BackupSettingsEffect>() {

    constructor() : this(LiveBackupSettingsWork)

    @Volatile
    private var cancelRequested = false

    private var importPath: String? = null
    private var exportPath: String? = null
    private var stageToken = 0

    override fun initUiState(): BackupSettingsUiState = BackupSettingsUiState()

    fun pendingExportPath(): String? = exportPath

    override suspend fun handleIntent(intent: BackupSettingsIntent) {
        when (intent) {
            BackupSettingsIntent.OpenExport -> openExport()
            BackupSettingsIntent.OpenImport -> openImport()
            BackupSettingsIntent.Dismiss -> dismiss()
            is BackupSettingsIntent.ToggleGroup -> {
                val group = intent.group
                updateChoosing {
                    if (group !in options) return@updateChoosing this
                    val next = if (group in selected) selected - group else selected + group
                    copy(selected = next)
                }
            }
            BackupSettingsIntent.SelectAll -> updateChoosing { copy(selected = options.toSet()) }
            BackupSettingsIntent.SelectNone -> updateChoosing { copy(selected = emptySet()) }
            is BackupSettingsIntent.SetIncludeSecrets -> updateChoosing {
                copy(includeSecrets = intent.include)
            }
            is BackupSettingsIntent.SetStrategy -> updateChoosing {
                copy(strategy = intent.strategy)
            }
            BackupSettingsIntent.Confirm -> confirm()
            BackupSettingsIntent.CancelRestore -> cancelRestore()
            is BackupSettingsIntent.ImportStaged -> stageImport(intent.path)
            BackupSettingsIntent.ImportUnreadable -> {
                if (busy()) return
                sendEffect(BackupSettingsEffect.ShowToast(R.string.ui_backup_import_invalid))
            }
            BackupSettingsIntent.SaveCancelled -> saveCancelled()
            BackupSettingsIntent.SaveCompleted -> saveCompleted()
            is BackupSettingsIntent.SaveFailed -> saveFailed(intent.stackTrace)
            BackupSettingsIntent.CloseFailure -> updateState { copy(failure = null) }
            BackupSettingsIntent.Restart -> {
                val sheet = currentState.sheet ?: return
                if (sheet.phase != BackupSheetPhase.RestartRequired) return
                sendEffect(BackupSettingsEffect.Relaunch)
            }
        }
    }

    private fun openExport() {
        if (busy()) return
        discardImport()
        discardExport()
        stageToken++
        updateState {
            copy(
                sheet = BackupSheetState(kind = BackupSheetKind.Export),
                failure = null,
            )
        }
    }

    private fun openImport() {
        if (busy()) return
        sendEffect(BackupSettingsEffect.PickImport)
    }

    private fun dismiss() {
        val sheet = currentState.sheet ?: return
        if (sheet.phase != BackupSheetPhase.Choosing) return
        discardImport()
        discardExport()
        updateState { copy(sheet = null) }
    }

    private fun confirm() {
        val sheet = currentState.sheet ?: return
        if (sheet.phase != BackupSheetPhase.Choosing) return
        if (sheet.selected.isEmpty()) return
        cancelRequested = false
        updateState { copy(sheet = sheet.copy(phase = BackupSheetPhase.Restoring), failure = null) }
        when (sheet.kind) {
            BackupSheetKind.Export -> startExport(sheet)
            BackupSheetKind.Import -> startImport(sheet)
        }
    }

    private fun startExport(sheet: BackupSheetState) {
        val groups = sheet.selected
        val includeSecrets = sheet.includeSecrets
        viewModelScope.launch {
            val prep = work.prepareExport(groups, includeSecrets) { cancelRequested }
            if (!stillRestoring(BackupSheetKind.Export)) return@launch
            when (prep) {
                ExportPrep.Cancelled -> finishCancelled(BackupSheetKind.Export)
                is ExportPrep.Failed -> showFailure(
                    kind = BackupSheetKind.Export,
                    dataUnmodified = true,
                    stackTrace = prep.stackTrace,
                )
                is ExportPrep.Ready -> {
                    if (cancelRequested) {
                        File(prep.path).delete()
                        finishCancelled(BackupSheetKind.Export)
                    } else {
                        exportPath = prep.path
                        sendEffect(BackupSettingsEffect.OpenSavePicker)
                    }
                }
            }
        }
    }

    private fun startImport(sheet: BackupSheetState) {
        val path = importPath ?: run {
            updateState { copy(sheet = sheet.copy(phase = BackupSheetPhase.Choosing)) }
            return
        }
        val groups = sheet.selected
        val strategy = sheet.strategy
        viewModelScope.launch {
            val outcome = work.apply(File(path), groups, strategy) { cancelRequested }
            if (!stillRestoring(BackupSheetKind.Import)) return@launch
            when (outcome) {
                ImportOutcome.Cancelled -> finishCancelled(BackupSheetKind.Import)
                ImportOutcome.Success -> {
                    discardImport()
                    val current = currentState.sheet ?: return@launch
                    updateState { copy(sheet = current.copy(phase = BackupSheetPhase.RestartRequired)) }
                    return@launch
                }
                is ImportOutcome.Failed -> showFailure(
                    kind = BackupSheetKind.Import,
                    dataUnmodified = outcome.dataUnmodified,
                    stackTrace = outcome.stackTrace,
                )
            }
        }
    }

    private fun cancelRestore() {
        val sheet = currentState.sheet ?: return
        if (sheet.phase != BackupSheetPhase.Restoring) return
        cancelRequested = true
        if (sheet.kind == BackupSheetKind.Export && exportPath != null) {
            discardExport()
            updateState { copy(sheet = sheet.copy(phase = BackupSheetPhase.Choosing)) }
        }
    }

    private fun stageImport(path: String) {
        if (busy()) {
            File(path).delete()
            return
        }
        val token = ++stageToken
        viewModelScope.launch {
            val look = work.inspect(File(path))
            if (token != stageToken || busy()) {
                File(path).delete()
                return@launch
            }
            when (look) {
                InspectLook.Bad -> {
                    File(path).delete()
                    sendEffect(BackupSettingsEffect.ShowToast(R.string.ui_backup_import_invalid))
                }
                is InspectLook.Ok -> {
                    discardImport()
                    discardExport()
                    importPath = path
                    updateState {
                        copy(
                            sheet = BackupSheetState(
                                kind = BackupSheetKind.Import,
                                options = look.groups,
                                selected = look.groups.toSet(),
                            ),
                            failure = null,
                        )
                    }
                }
            }
        }
    }

    private fun saveCancelled() {
        val sheet = currentState.sheet ?: return
        if (sheet.phase != BackupSheetPhase.Restoring || sheet.kind != BackupSheetKind.Export) return
        discardExport()
        updateState { copy(sheet = sheet.copy(phase = BackupSheetPhase.Choosing)) }
    }

    private fun saveCompleted() {
        val sheet = currentState.sheet ?: return
        if (sheet.phase != BackupSheetPhase.Restoring || sheet.kind != BackupSheetKind.Export) return
        discardExport()
        updateState { copy(sheet = null, failure = null) }
        sendEffect(BackupSettingsEffect.ShowToast(R.string.ui_backup_export_done))
    }

    private fun saveFailed(stackTrace: String) {
        val sheet = currentState.sheet ?: return
        if (sheet.phase != BackupSheetPhase.Restoring || sheet.kind != BackupSheetKind.Export) return
        discardExport()
        showFailure(BackupSheetKind.Export, dataUnmodified = true, stackTrace = stackTrace)
    }

    private fun finishCancelled(kind: BackupSheetKind) {
        val sheet = currentState.sheet ?: return
        if (sheet.kind != kind || sheet.phase != BackupSheetPhase.Restoring) return
        updateState { copy(sheet = sheet.copy(phase = BackupSheetPhase.Choosing), failure = null) }
    }

    private fun showFailure(kind: BackupSheetKind, dataUnmodified: Boolean, stackTrace: String) {
        val sheet = currentState.sheet
        val backToChoosing = if (sheet != null && sheet.kind == kind && sheet.phase == BackupSheetPhase.Restoring) {
            sheet.copy(phase = BackupSheetPhase.Choosing)
        } else {
            sheet
        }
        updateState {
            copy(
                sheet = backToChoosing,
                failure = BackupFailureState(
                    kind = kind,
                    dataUnmodified = dataUnmodified,
                    stackTrace = stackTrace,
                ),
            )
        }
    }

    private fun stillRestoring(kind: BackupSheetKind): Boolean {
        val sheet = currentState.sheet ?: return false
        return sheet.kind == kind && sheet.phase == BackupSheetPhase.Restoring
    }

    private fun busy(): Boolean {
        val phase = currentState.sheet?.phase ?: return false
        return phase != BackupSheetPhase.Choosing
    }

    private fun discardExport() {
        exportPath?.let { path -> File(path).delete() }
        exportPath = null
    }

    private fun discardImport() {
        importPath?.let { path -> File(path).delete() }
        importPath = null
    }

    private fun updateChoosing(block: BackupSheetState.() -> BackupSheetState) {
        val sheet = currentState.sheet ?: return
        if (sheet.phase != BackupSheetPhase.Choosing) return
        updateState { copy(sheet = sheet.block()) }
    }

}
