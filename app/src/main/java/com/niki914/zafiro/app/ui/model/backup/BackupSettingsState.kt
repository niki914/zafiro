package com.niki914.zafiro.app.ui.model.backup

import androidx.annotation.StringRes
import com.niki914.zafiro.backup.BackupGroup
import com.niki914.zafiro.backup.BackupStrategy

/** 备份单里可勾的项，顺序和名字以这个列表为准。 */
val backupSheetOptions: List<BackupGroup> = listOf(
    BackupGroup.Conversations,
    BackupGroup.Config,
    BackupGroup.Tools,
    BackupGroup.Skills,
    BackupGroup.ExecutionRules,
    BackupGroup.MaskingRules,
    BackupGroup.TakeoverRules,
)

enum class BackupSheetKind {
    Export,
    Import,
}

enum class BackupSheetPhase {
    Choosing,
    Restoring,
    RestartRequired,
}

data class BackupSheetState(
    val kind: BackupSheetKind,
    val phase: BackupSheetPhase = BackupSheetPhase.Choosing,
    val options: List<BackupGroup> = backupSheetOptions,
    val selected: Set<BackupGroup> = backupSheetOptions.toSet(),
    val includeSecrets: Boolean = false,
    val strategy: BackupStrategy = BackupStrategy.Merge,
)

data class BackupFailureState(
    val kind: BackupSheetKind,
    val dataUnmodified: Boolean,
    val stackTrace: String,
)

data class BackupSettingsUiState(
    val sheet: BackupSheetState? = null,
    val failure: BackupFailureState? = null,
)

sealed interface BackupSettingsIntent {
    data object OpenExport : BackupSettingsIntent
    data object OpenImport : BackupSettingsIntent
    data object Dismiss : BackupSettingsIntent
    data class ToggleGroup(val group: BackupGroup) : BackupSettingsIntent
    data object SelectAll : BackupSettingsIntent
    data object SelectNone : BackupSettingsIntent
    data class SetIncludeSecrets(val include: Boolean) : BackupSettingsIntent
    data class SetStrategy(val strategy: BackupStrategy) : BackupSettingsIntent
    data object Confirm : BackupSettingsIntent
    data object CancelRestore : BackupSettingsIntent
    data class ImportStaged(val path: String) : BackupSettingsIntent
    data object ImportUnreadable : BackupSettingsIntent
    data object SaveCancelled : BackupSettingsIntent
    data object SaveCompleted : BackupSettingsIntent
    data class SaveFailed(val stackTrace: String) : BackupSettingsIntent
    data object CloseFailure : BackupSettingsIntent
    data object Restart : BackupSettingsIntent
}

sealed interface BackupSettingsEffect {
    data object PickImport : BackupSettingsEffect
    data object OpenSavePicker : BackupSettingsEffect
    data class ShowToast(@StringRes val resId: Int) : BackupSettingsEffect
    data object Relaunch : BackupSettingsEffect
}
