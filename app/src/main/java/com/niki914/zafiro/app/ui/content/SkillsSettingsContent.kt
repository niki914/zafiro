package com.niki914.zafiro.app.ui.content

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
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
import com.niki914.uikit.dialog.ZafiroConfirmationDialog
import com.niki914.uikit.infra.component.settings.SettingsPageSpec
import com.niki914.uikit.infra.component.settings.SettingsRowAction
import com.niki914.uikit.infra.component.settings.SettingsRowSpec
import com.niki914.uikit.infra.component.settings.SettingsSectionLayout
import com.niki914.uikit.infra.component.settings.SettingsSectionSpec
import com.niki914.uikit.infra.component.settings.SettingsSpecPageContent
import com.niki914.uikit.infra.nav.pageViewModel
import com.niki914.uikit.screen.ProvideZafiroScreenContentForPreview
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.PageChromeContribution
import com.niki914.zafiro.app.ui.RegisterPageChrome
import com.niki914.zafiro.app.ui.helper.ZafiroToast
import com.niki914.zafiro.app.ui.model.SkillInlineError
import com.niki914.zafiro.app.ui.model.SkillListItem
import com.niki914.zafiro.app.ui.model.SkillSettingsEffect
import com.niki914.zafiro.app.ui.model.SkillSettingsIntent
import com.niki914.zafiro.app.ui.model.SkillSettingsUiState
import com.niki914.zafiro.app.ui.model.SkillSettingsViewModel
import com.niki914.zafiro.app.ui.nav.TopBarActionSpec
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
                    ZafiroToast.show(R.string.skill_import_no_skill_file)

                is SkillSettingsEffect.ShowImportErrorToast -> {
                    val msg = effect.message
                    if (msg != null) {
                        ZafiroToast.show(msg)
                    } else {
                        ZafiroToast.show(effect.fallbackResId)
                    }
                }

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
                ZafiroToast.show(R.string.skill_import_resolve_failed)
            }
        }
    }

    val pageChromeContribution = remember {
        PageChromeContribution(
            rightAction = TopBarActionSpec(
                icon = Icons.Default.Add,
                onClick = { launcher.launch(null) },
                contentDescription = "Add Button",
            ),
        )
    }
    RegisterPageChrome(pageChromeContribution)

    val conflict = uiState.importConflict
    if (conflict != null) {
        ZafiroConfirmationDialog(
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

/**
 * Lazy list with a search box: with 270+ skills the old all-at-once card page froze the phone. Only visible rows are composed.
 */
@Composable
private fun SkillsSettingsContentBody(
    uiState: SkillSettingsUiState,
    onOpenSkillDetail: (String, String) -> Unit,
    onEnabledChange: (String, Boolean) -> Unit,
) {
    var query by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    val shown = androidx.compose.runtime.remember(uiState.items, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) uiState.items else uiState.items.filter { it.title.lowercase().contains(q) || it.summary.orEmpty().lowercase().contains(q) }
    }
    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxSize()) {
        if (uiState.isLoading) androidx.compose.material3.Text(stringResource(R.string.skill_loading), modifier = androidx.compose.ui.Modifier.padding(16.dp))
        androidx.compose.material3.OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { androidx.compose.material3.Text("Search ${uiState.items.size} skills") },
            modifier = androidx.compose.ui.Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        androidx.compose.foundation.lazy.LazyColumn(androidx.compose.ui.Modifier.fillMaxSize()) {
            items(shown.size, key = { shown[it].id }) { i ->
                val item = shown[i]
                androidx.compose.foundation.layout.Row(
                    androidx.compose.ui.Modifier.fillMaxWidth().clickable { onOpenSkillDetail(item.id, item.title) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.weight(1f).padding(end = 12.dp)) {
                        androidx.compose.material3.Text(item.title, style = androidx.compose.material3.MaterialTheme.typography.titleSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        androidx.compose.material3.Text(item.summary.orEmpty(), style = androidx.compose.material3.MaterialTheme.typography.bodySmall, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    androidx.compose.material3.Switch(checked = item.enabled, onCheckedChange = { onEnabledChange(item.id, it) }, enabled = !uiState.isSaving)
                }
            }
        }
    }
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
        ProvideZafiroScreenContentForPreview(topPadding = 0.dp) {
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
        ProvideZafiroScreenContentForPreview(topPadding = 0.dp) {
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
        ProvideZafiroScreenContentForPreview(topPadding = 0.dp) {
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
