package com.niki914.nexus.agentic.app.ui.nexus.content

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.niki914.nexus.agentic.app.R
import com.niki914.nexus.agentic.app.ui.infra.ConfirmationLiquidDialog
import com.niki914.nexus.agentic.app.ui.infra.ProvideLiquidScreenContentForPreview
import com.niki914.nexus.agentic.app.ui.infra.component.settings.SettingsPageSpec
import com.niki914.nexus.agentic.app.ui.infra.component.settings.SettingsRowAction
import com.niki914.nexus.agentic.app.ui.infra.component.settings.SettingsRowSpec
import com.niki914.nexus.agentic.app.ui.infra.component.settings.SettingsSectionLayout
import com.niki914.nexus.agentic.app.ui.infra.component.settings.SettingsSectionSpec
import com.niki914.nexus.agentic.app.ui.infra.component.settings.SettingsSpecPageContent
import com.niki914.nexus.agentic.app.ui.infra.nav.pageViewModel
import com.niki914.nexus.agentic.app.ui.nexus.PageChromeContribution
import com.niki914.nexus.agentic.app.ui.nexus.RegisterPageChrome
import com.niki914.nexus.agentic.app.ui.nexus.model.SkillInlineError
import com.niki914.nexus.agentic.app.ui.nexus.model.SkillListItem
import com.niki914.nexus.agentic.app.ui.nexus.model.SkillSettingsEffect
import com.niki914.nexus.agentic.app.ui.nexus.model.SkillSettingsIntent
import com.niki914.nexus.agentic.app.ui.nexus.model.SkillSettingsUiState
import com.niki914.nexus.agentic.app.ui.nexus.model.SkillSettingsViewModel
import com.niki914.nexus.agentic.app.ui.nexus.nav.TopBarActionSpec
import java.io.File

@Composable
fun SkillsSettingsContent(
    onOpenSkillDetail: (skillId: String, title: String) -> Unit,
) {
    val viewModel = pageViewModel<SkillSettingsViewModel>()
    val uiState by viewModel.uiStateFlow.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.sendIntent(SkillSettingsIntent.Load)

        viewModel.uiEffect.collect { effect ->
            when (effect) {
                SkillSettingsEffect.ShowNoSkillFileToast ->
                    Toast.makeText(context, R.string.skill_import_no_skill_file, Toast.LENGTH_SHORT)
                        .show()

                is SkillSettingsEffect.ShowImportErrorToast ->
                    Toast.makeText(
                        context,
                        effect.message ?: context.getString(effect.fallbackResId),
                        Toast.LENGTH_SHORT
                    ).show()

                else -> {}
            }
        }
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val sourceName = DocumentFile.fromTreeUri(context, uri)?.name
                ?.takeIf { it.isNotBlank() }
                ?: "imported_skill"
            val tempDir = File(context.cacheDir, sourceName)
            if (tempDir.exists()) tempDir.deleteRecursively()
            if (copyDocumentTree(context, uri, tempDir)) {
                viewModel.sendIntent(SkillSettingsIntent.Import(tempDir))
            } else {
                tempDir.deleteRecursively()
                Toast.makeText(context, R.string.skill_import_resolve_failed, Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }

    val importLabel = stringResource(R.string.skill_import_button)
    val pageChromeContribution = remember(importLabel) {
        PageChromeContribution(
            rightAction = TopBarActionSpec(
                icon = Icons.Default.Add,
                onClick = { launcher.launch(null) },
                contentDescription = importLabel,
            ),
        )
    }
    RegisterPageChrome(pageChromeContribution)

    val conflict = uiState.importConflict
    if (conflict != null) {
        ConfirmationLiquidDialog(
            visible = true,
            onDismissRequest = {
                viewModel.sendIntent(SkillSettingsIntent.DismissImportConflict)
                conflict.sourceDir.deleteRecursively()
            },
            title = stringResource(R.string.skill_import_conflict_title),
            text = stringResource(R.string.skill_import_conflict_text, conflict.skillName),
            positiveButtonText = stringResource(R.string.skill_import_overwrite),
            negativeButtonText = stringResource(R.string.skill_import_cancel),
            onPositiveClick = { viewModel.sendIntent(SkillSettingsIntent.ConfirmImport) },
            onNegativeClick = {
                viewModel.sendIntent(SkillSettingsIntent.DismissImportConflict)
                conflict.sourceDir.deleteRecursively()
            },
        )
    }

    SkillsSettingsContentBody(
        uiState = uiState,
        onOpenSkillDetail = onOpenSkillDetail,
        onEnabledChange = { id, enabled ->
            viewModel.sendIntent(SkillSettingsIntent.ToggleEnabled(id, enabled))
        },
    )
}

@Composable
private fun SkillsSettingsContentBody(
    uiState: SkillSettingsUiState,
    onOpenSkillDetail: (String, String) -> Unit,
    onEnabledChange: (String, Boolean) -> Unit,
) {
    val items = uiState.items
    SettingsSpecPageContent(
        spec = skillSettingsSpec(uiState),
        onAction = { action ->
            when (action) {
                is SettingsRowAction.Navigate -> {
                    val item =
                        items.firstOrNull { it.id == action.id } ?: return@SettingsSpecPageContent
                    onOpenSkillDetail(item.id, item.title)
                }

                is SettingsRowAction.ToggleChanged -> onEnabledChange(action.id, action.checked)
                is SettingsRowAction.Click -> Unit
            }
        },
    )
}

@Composable
private fun skillSettingsSpec(uiState: SkillSettingsUiState): SettingsPageSpec {
    val sections = when {
        uiState.isLoading -> listOf(
            SettingsSectionSpec(
                layout = SettingsSectionLayout.GroupedCard,
                rows = listOf(
                    SettingsRowSpec.Message(
                        title = stringResource(R.string.skill_loading),
                        horizontalPadding = 0.dp,
                        verticalPadding = 0.dp,
                    )
                ),
            )
        )

        uiState.items.isNotEmpty() -> listOf(
            SettingsSectionSpec(
                layout = SettingsSectionLayout.CardList,
                rows = uiState.items.map { item ->
                    SettingsRowSpec.ToggleNavigation(
                        id = item.id,
                        title = item.title,
                        summary = item.summary,
                        checked = item.enabled,
                        enabled = !uiState.isSaving,
                    )
                },
            )
        )

        else -> emptyList()
    }

    return SettingsPageSpec(
        description = skillPageDescription(uiState),
        sections = sections,
    )
}

@Composable
private fun skillPageDescription(uiState: SkillSettingsUiState): String {
    return when (val error = uiState.inlineError) {
        is SkillInlineError.LoadFailed -> stringResource(
            R.string.skill_error_load_failed,
            error.message ?: stringResource(error.fallbackResId),
        )

        is SkillInlineError.SaveFailed -> stringResource(
            R.string.skill_error_save_failed,
            error.message ?: stringResource(error.fallbackResId),
        )

        is SkillInlineError.DeleteFailed -> stringResource(
            R.string.skill_error_delete_failed,
            error.message ?: stringResource(error.fallbackResId),
        )

        null -> when {
            uiState.isLoading -> stringResource(R.string.skill_page_description)
            uiState.items.isEmpty() -> stringResource(R.string.skill_empty)
            else -> stringResource(R.string.skill_page_description)
        }
    }
}

@Preview(name = "Skills Loading", showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun SkillsSettingsContentLoadingPreview() {
    MaterialTheme {
        ProvideLiquidScreenContentForPreview(topPadding = 0.dp) {
            SkillsSettingsContentBody(
                uiState = SkillSettingsUiState(isLoading = true),
                onOpenSkillDetail = { _, _ -> },
                onEnabledChange = { _, _ -> },
            )
        }
    }
}

@Preview(name = "Skills Empty", showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun SkillsSettingsContentEmptyPreview() {
    MaterialTheme {
        ProvideLiquidScreenContentForPreview(topPadding = 0.dp) {
            SkillsSettingsContentBody(
                uiState = SkillSettingsUiState(isLoading = false),
                onOpenSkillDetail = { _, _ -> },
                onEnabledChange = { _, _ -> },
            )
        }
    }
}

@Preview(name = "Skills Loaded", showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun SkillsSettingsContentLoadedPreview() {
    MaterialTheme {
        ProvideLiquidScreenContentForPreview(topPadding = 0.dp) {
            SkillsSettingsContentBody(
                uiState = SkillSettingsUiState(
                    isLoading = false,
                    items = listOf(
                        SkillListItem(
                            id = "tools/android/SKILL.md",
                            title = "Android Debugging",
                            summary = "调试 Android 本地问题",
                            enabled = true,
                        ),
                        SkillListItem(
                            id = "workflow/release/SKILL.md",
                            title = "Release Checklist",
                            summary = "发布前检查",
                            enabled = false,
                        ),
                    ),
                ),
                onOpenSkillDetail = { _, _ -> },
                onEnabledChange = { _, _ -> },
            )
        }
    }
}

private fun copyDocumentTree(context: Context, treeUri: Uri, targetDir: File): Boolean {
    try {
        context.contentResolver.takePersistableUriPermission(
            treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
    } catch (_: SecurityException) {
        // Permission may already be held
    }

    val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return false
    return try {
        copyDocumentRecursive(context, rootDoc, targetDir)
    } catch (_: Exception) {
        false
    }
}

private fun copyDocumentRecursive(context: Context, source: DocumentFile, target: File): Boolean {
    if (source.isDirectory) {
        if (!target.exists() && !target.mkdirs()) return false
        var ok = true
        for (child in source.listFiles()) {
            val childName = child.name ?: continue
            ok = copyDocumentRecursive(context, child, File(target, childName)) && ok
        }
        return ok
    } else {
        return try {
            val input = context.contentResolver.openInputStream(source.uri) ?: return false
            input.use { inputStream ->
                target.outputStream().use { output -> inputStream.copyTo(output) }
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}
