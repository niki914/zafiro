package com.niki914.zafiro.app.ui.content

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.niki914.uikit.control.ZafiroLoadingIndicator
import com.niki914.uikit.button.ZafiroButton
import com.niki914.uikit.control.ZafiroSwitch
import com.niki914.uikit.infra.component.OptionRow
import com.niki914.uikit.infra.component.OptionSheet
import com.niki914.uikit.infra.component.SheetIconButton
import com.niki914.uikit.infra.component.SettingsSegmentedSelector
import com.niki914.uikit.infra.component.settings.SettingsPageSpec
import com.niki914.uikit.infra.component.settings.SettingsRowAction
import com.niki914.uikit.infra.component.settings.SettingsRowSpec
import com.niki914.uikit.infra.component.settings.SettingsSectionLayout
import com.niki914.uikit.infra.component.settings.SettingsSectionSpec
import com.niki914.uikit.infra.component.settings.SettingsSpecPageContent
import com.niki914.uikit.infra.nav.pageViewModel
import com.niki914.zafiro.api.util.runCatchingCancellable
import com.niki914.zafiro.app.AppRelaunch
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.PageBackHandler
import com.niki914.zafiro.app.ui.PageChromeContribution
import com.niki914.zafiro.app.ui.RegisterPageChrome
import com.niki914.zafiro.app.ui.helper.ZafiroToast
import com.niki914.zafiro.app.ui.model.backup.BackupSettingsEffect
import com.niki914.zafiro.app.ui.model.backup.BackupSettingsIntent
import com.niki914.zafiro.app.ui.model.backup.BackupSettingsViewModel
import com.niki914.zafiro.app.ui.model.backup.BackupSheetKind
import com.niki914.zafiro.app.ui.model.backup.BackupSheetPhase
import com.niki914.zafiro.app.ui.model.backup.BackupSheetState
import com.niki914.zafiro.backup.BackupGroup
import com.niki914.zafiro.backup.BackupStrategy
import com.niki914.zafiro.repo.XRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val EXPORT_ROW_ID = "backup.export"
private const val IMPORT_ROW_ID = "backup.import"

private const val EXPORT_FILE_NAME = "zafiro-backup.zafiro"

@Composable
fun BackupSettingsContent(
    viewModel: BackupSettingsViewModel = pageViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uiState by viewModel.uiStateFlow.collectAsState()
    val latestSheet by rememberUpdatedState(uiState.sheet)
    val latestViewModel by rememberUpdatedState(viewModel)
    val pageChromeContribution = remember(viewModel) {
        PageChromeContribution(
            backHandler = PageBackHandler(
                shouldConsumeBack = { latestSheet != null },
                onConsumeBack = {
                    latestViewModel.sendIntent(BackupSettingsIntent.Dismiss)
                },
            ),
        )
    }
    RegisterPageChrome(pageChromeContribution)

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri == null) {
            viewModel.sendIntent(BackupSettingsIntent.SaveCancelled)
            return@rememberLauncherForActivityResult
        }
        val path = viewModel.pendingExportPath()
        if (path == null) {
            viewModel.sendIntent(BackupSettingsIntent.SaveCancelled)
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val stack = copyFileToUri(context, File(path), uri)
            if (stack == null) {
                viewModel.sendIntent(BackupSettingsIntent.SaveCompleted)
            } else {
                viewModel.sendIntent(BackupSettingsIntent.SaveFailed(stack))
            }
        }
    }
    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        viewModel.sendIntent(BackupSettingsIntent.BeginImportInspection)
        scope.launch {
            val staged = stageImportUri(context, uri)
            if (staged == null) {
                viewModel.sendIntent(BackupSettingsIntent.ImportUnreadable)
            } else {
                viewModel.sendIntent(BackupSettingsIntent.ImportStaged(staged))
            }
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                BackupSettingsEffect.PickImport -> openLauncher.launch(arrayOf("*/*"))
                BackupSettingsEffect.OpenSavePicker -> {
                    if (viewModel.pendingExportPath() != null) {
                        saveLauncher.launch(EXPORT_FILE_NAME)
                    }
                }
                is BackupSettingsEffect.ShowToast -> ZafiroToast.show(effect.resId)
                BackupSettingsEffect.Relaunch -> {
                    if (!XRepo.onboardingCompleted()) {
                        XRepo.setOnboardingCompleted(true)
                    }
                    AppRelaunch.schedule(context)
                }
            }
        }
    }

    // FULL backup: one zip with settings, MCP servers, memory, skills, agents, tasks, workspaces, saved files and chats.
    val fullSave = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            runCatching { com.niki914.zafiro.backup.BackupManager.create(context, uri) }
                .onSuccess { android.widget.Toast.makeText(context, "Full backup saved (${it.fileCount} files)", android.widget.Toast.LENGTH_LONG).show() }
                .onFailure { android.widget.Toast.makeText(context, "Backup failed: ${it.message}", android.widget.Toast.LENGTH_LONG).show() }
        }
    }
    var fullStaged by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<com.niki914.zafiro.backup.BackupArchive.Summary?>(null) }
    val fullPick = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { com.niki914.zafiro.backup.BackupManager.stageRestore(context, uri) }
                .onSuccess { fullStaged = it }
                .onFailure { android.widget.Toast.makeText(context, "Not a valid full backup: ${it.message}", android.widget.Toast.LENGTH_LONG).show() }
        }
    }
    fullStaged?.let { st ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { com.niki914.zafiro.backup.BackupManager.cancelStagedRestore(context); fullStaged = null },
            title = { androidx.compose.material3.Text("Restore full backup?") },
            text = { androidx.compose.material3.Text("${st.fileCount} files from version ${st.appVersion.ifBlank { "?" }}. Everything now on this phone is replaced. The app restarts.") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { fullStaged = null; AppRelaunch.schedule(context) }) { androidx.compose.material3.Text("Restore") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { com.niki914.zafiro.backup.BackupManager.cancelStagedRestore(context); fullStaged = null }) { androidx.compose.material3.Text("Cancel") } },
        )
    }

    BackupSettingsPage(
        onAction = { action ->
            if (action !is SettingsRowAction.Navigate) return@BackupSettingsPage
            when (action.id) {
                FULL_EXPORT_ID -> fullSave.launch(com.niki914.zafiro.backup.BackupManager.suggestedFileName())
                FULL_IMPORT_ID -> fullPick.launch(arrayOf("application/zip", "application/octet-stream"))
                EXPORT_ROW_ID -> viewModel.sendIntent(BackupSettingsIntent.OpenExport)
                IMPORT_ROW_ID -> viewModel.sendIntent(BackupSettingsIntent.OpenImport)
            }
        },
    )
    BackupOptionSheet(
        sheet = uiState.sheet,
        onIntent = viewModel::sendIntent,
    )
    val failure = uiState.failure
    if (failure != null) {
        BackupFailureDialog(
            failure = failure,
            onDismiss = { viewModel.sendIntent(BackupSettingsIntent.CloseFailure) },
        )
    }
}

private suspend fun stageImportUri(context: Context, uri: Uri): String? {
    return withContext(Dispatchers.IO) {
        runCatchingCancellable {
            val dest = File(context.cacheDir, "backup-import-${System.nanoTime()}.zafiro")
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: error("no input")
            dest.absolutePath
        }.getOrNull()
    }
}

private suspend fun copyFileToUri(context: Context, file: File, uri: Uri): String? {
    return withContext(Dispatchers.IO) {
        runCatchingCancellable {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            } ?: error("no output")
        }.exceptionOrNull()?.stackTraceToString()
    }
}

private const val FULL_EXPORT_ID = "backup.full_export"
private const val FULL_IMPORT_ID = "backup.full_import"

@Composable
private fun BackupSettingsPage(
    onAction: (SettingsRowAction) -> Unit,
) {
    val spec = SettingsPageSpec(
        description = stringResource(R.string.ui_settings_backup_description),
        sections = listOf(
            SettingsSectionSpec(
                layout = SettingsSectionLayout.GroupedCard,
                rows = listOf(
                    SettingsRowSpec.Navigation(
                        id = EXPORT_ROW_ID,
                        title = stringResource(R.string.ui_settings_backup_export),
                        summary = stringResource(R.string.ui_settings_backup_export_summary),
                    ),
                    SettingsRowSpec.Navigation(
                        id = IMPORT_ROW_ID,
                        title = stringResource(R.string.ui_settings_backup_import),
                        summary = stringResource(R.string.ui_settings_backup_import_summary),
                    ),
                ),
            ),
            SettingsSectionSpec(
                title = "Full backup",
                layout = SettingsSectionLayout.GroupedCard,
                rows = listOf(
                    SettingsRowSpec.Navigation(id = FULL_EXPORT_ID, title = "Back up everything", summary = "Agents, tasks, workspaces, memory, MCP, skills, files"),
                    SettingsRowSpec.Navigation(id = FULL_IMPORT_ID, title = "Restore everything"),
                ),
            ),
        ),
    )
    SettingsSpecPageContent(
        spec = spec,
        onAction = onAction,
    )
}

@Composable
private fun BackupOptionSheet(
    sheet: BackupSheetState?,
    onIntent: (BackupSettingsIntent) -> Unit,
) {
    val inspecting = sheet?.phase == BackupSheetPhase.Inspecting
    val choosing = sheet?.phase == BackupSheetPhase.Choosing
    val dismissible = choosing || inspecting
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    OptionSheet(
        visible = sheet != null,
        onDismissRequest = { onIntent(BackupSettingsIntent.Dismiss) },
        dismissible = dismissible,
        title = sheet?.let { stringResource(it.kind.titleRes()) },
        bodyModifier = Modifier.heightIn(max = screenHeight * 0.85f),
        header = {
            if (sheet != null) {
                BackupSheetSlot(
                    sheet = sheet,
                    onIntent = onIntent,
                )
                when (sheet.kind) {
                    BackupSheetKind.Export -> IncludeSecretsRow(
                        includeSecrets = sheet.includeSecrets,
                        enabled = choosing,
                        onIncludeSecrets = { include ->
                            onIntent(BackupSettingsIntent.SetIncludeSecrets(include))
                        },
                    )
                    BackupSheetKind.Import -> ImportStrategySlot(
                        strategy = sheet.strategy,
                        onStrategy = { strategy ->
                            if (choosing) onIntent(BackupSettingsIntent.SetStrategy(strategy))
                        },
                    )
                }
            }
        },
    ) { _ ->
        if (sheet == null) return@OptionSheet
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
        ) {
            sheet.options.forEach { group ->
                OptionRow(
                    title = stringResource(group.titleRes()),
                    checked = group in sheet.selected,
                    onClick = {
                        if (choosing) onIntent(BackupSettingsIntent.ToggleGroup(group))
                    },
                )
            }
        }
    }
}

@Composable
private fun BackupSheetSlot(
    sheet: BackupSheetState,
    onIntent: (BackupSettingsIntent) -> Unit,
) {
    val choosing = sheet.phase == BackupSheetPhase.Choosing
    val inspecting = sheet.phase == BackupSheetPhase.Inspecting
    val actionText = when (sheet.phase) {
        BackupSheetPhase.Inspecting -> stringResource(R.string.ui_backup_inspecting)
        BackupSheetPhase.Choosing -> stringResource(sheet.kind.titleRes())
        BackupSheetPhase.Restoring -> stringResource(R.string.dialog_cancel)
        BackupSheetPhase.RestartRequired -> stringResource(R.string.ui_backup_restart)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SheetToggleIcon(
            imageVector = Icons.Filled.SelectAll,
            contentDescription = "Select all",
            highlighted = sheet.options.isNotEmpty() && sheet.selected.containsAll(sheet.options),
            enabled = choosing,
            onClick = { onIntent(BackupSettingsIntent.SelectAll) },
        )
        SheetToggleIcon(
            imageVector = Icons.Filled.Deselect,
            contentDescription = "Select none",
            highlighted = sheet.selected.isEmpty(),
            enabled = choosing,
            onClick = { onIntent(BackupSettingsIntent.SelectNone) },
        )
        ZafiroButton(
            text = actionText,
            onClick = {
                when (sheet.phase) {
                    BackupSheetPhase.Inspecting -> Unit
                    BackupSheetPhase.Choosing -> onIntent(BackupSettingsIntent.Confirm)
                    BackupSheetPhase.Restoring -> onIntent(BackupSettingsIntent.CancelRestore)
                    BackupSheetPhase.RestartRequired -> onIntent(BackupSettingsIntent.Restart)
                }
            },
            enabled = when (sheet.phase) {
                BackupSheetPhase.Inspecting -> false
                BackupSheetPhase.Choosing -> sheet.selected.isNotEmpty()
                BackupSheetPhase.Restoring, BackupSheetPhase.RestartRequired -> true
            },
            leadingIcon = if (sheet.phase == BackupSheetPhase.Restoring || sheet.phase == BackupSheetPhase.Inspecting) {
                { ChatRunningIndicator() }
            } else {
                null
            },
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
            buttonHeight = 48.dp,
        )
    }
}

@Composable
private fun SheetToggleIcon(
    imageVector: ImageVector,
    contentDescription: String,
    highlighted: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    SheetIconButton(
        imageVector = imageVector,
        contentDescription = contentDescription,
        highlighted = highlighted,
        enabled = enabled,
        onClick = onClick,
    )
}

@Composable
private fun IncludeSecretsRow(
    includeSecrets: Boolean,
    enabled: Boolean,
    onIncludeSecrets: (Boolean) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = includeSecrets,
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = { include ->
                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onIncludeSecrets(include)
                },
            )
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.ui_backup_include_secrets),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.ui_backup_include_secrets_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Box(Modifier.clearAndSetSemantics {}) {
            ZafiroSwitch(
                checked = includeSecrets,
                onCheckedChange = null,
                enabled = enabled,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
    }
}

@Composable
private fun ImportStrategySlot(
    strategy: BackupStrategy,
    onStrategy: (BackupStrategy) -> Unit,
) {
    SettingsSegmentedSelector(
        title = stringResource(R.string.ui_backup_strategy),
        options = BackupStrategy.entries,
        selected = strategy,
        label = { option ->
            stringResource(
                when (option) {
                    BackupStrategy.Merge -> R.string.ui_backup_strategy_merge
                    BackupStrategy.Overwrite -> R.string.ui_backup_strategy_overwrite
                },
            )
        },
        onSelected = onStrategy,
    )
}

@StringRes
private fun BackupSheetKind.titleRes(): Int = when (this) {
    BackupSheetKind.Export -> R.string.ui_settings_backup_export
    BackupSheetKind.Import -> R.string.ui_settings_backup_import
}

@Composable
private fun ChatRunningIndicator() {
    ZafiroLoadingIndicator(
        color = LocalContentColor.current,
        size = 22.dp,
        petalSize = 28.dp,
        strokeWidth = 2.dp,
    )
}

@StringRes
private fun BackupGroup.titleRes(): Int = when (this) {
    BackupGroup.Conversations -> R.string.ui_backup_group_conversations
    BackupGroup.Config -> R.string.ui_backup_group_config
    BackupGroup.Tools -> R.string.ui_backup_group_tools
    BackupGroup.Skills -> R.string.ui_backup_group_skills
    BackupGroup.ExecutionRules -> R.string.ui_backup_group_execution
    BackupGroup.MaskingRules -> R.string.ui_backup_group_masking
    BackupGroup.TakeoverRules -> R.string.ui_backup_group_takeover
}
