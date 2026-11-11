package com.niki914.zafiro.app.ui.content

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.documentfile.provider.DocumentFile
import com.niki914.uikit.infra.component.settings.SettingsPageSpec
import com.niki914.uikit.infra.component.settings.SettingsRowAction
import com.niki914.uikit.infra.component.settings.SettingsRowSpec
import com.niki914.uikit.infra.component.settings.SettingsSectionLayout
import com.niki914.uikit.infra.component.settings.SettingsSectionSpec
import com.niki914.uikit.infra.component.settings.SettingsSpecPageContent
import com.niki914.uikit.infra.nav.pageViewModel
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.model.BrowserSettingsIntent
import com.niki914.zafiro.app.ui.model.BrowserSettingsViewModel
import com.niki914.zafiro.repo.XRepo
import kotlinx.coroutines.launch

private const val BROWSER_OPEN_ROW_ID = "browser.open"
private const val BROWSER_MEDIA_GRANT_ROW_ID = "browser.media_grant"
private const val BROWSER_LIVE_IN_CHAT_ROW_ID = "browser.live_in_chat"
private const val BROWSER_MAX_TABS_ROW_ID = "browser.max_tabs"
// r24 Item 5：下载文件夹行（点击打开系统文件夹选择器；替代旧的 OutlinedTextField 路径输入）。
private const val BROWSER_DOWNLOAD_DIR_ROW_ID = "browser.download_dir"

/**
 * 浏览器设置（Settings 组的 Browser 组内容）：
 * - 打开内置浏览器（IabBrowserPage 的入口）；
 * - 媒体权限自动放行开关（IAB 全局，进程级）；
 * - 标签上限滑杆（r15 点 5：1..8，即时热更新 IabManager + XRepo 持久化）；
 * - 聊天内实时 IAB 窗口开关（持久化，XRepo）；
 * - 下载文件夹（r24 Item 5：SAF OpenDocumentTree 选择器；未选时回落旧版绝对路径/默认内部目录）。
 * 工具 browser 的启停不在这里——它随 builtin-tools 设置页的注册表自动出现。
 */
@Composable
fun BrowserSettingsContent(
    onOpenBrowser: () -> Unit,
    viewModel: BrowserSettingsViewModel = pageViewModel(),
) {
    val uiState by viewModel.uiStateFlow.collectAsState()
    val liveIabInChat by XRepo.showLiveIabInChatSetting.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.sendIntent(BrowserSettingsIntent.Load)
    }

    val scope = rememberCoroutineScope()
    // r24 Item 5：SAF 树选择器。选中后必须自己取持久 URI 权限（OpenDocumentTree 契约
    // 默认只给单次读写权限），否则 App 重启/下载时 DocumentFile 写树会失败。
    val downloadFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        // 顶层文件夹显示名（行尾预览）：DocumentFile 接口没有 openOutputStream，但有 getName()。
        val folderName = runCatching {
            DocumentFile.fromTreeUri(context, uri)?.name?.takeIf { it.isNotBlank() }
        }.getOrNull() ?: uri.lastPathSegment ?: "download"
        viewModel.sendIntent(
            BrowserSettingsIntent.SetDownloadDirTree(uri.toString(), folderName),
        )
    }

    val spec = SettingsPageSpec(
        description = null,
        sections = listOf(
            SettingsSectionSpec(
                title = stringResource(R.string.ui_iab_browser_group),
                layout = SettingsSectionLayout.GroupedCard,
                rows = listOf(
                    SettingsRowSpec.Navigation(
                        id = BROWSER_OPEN_ROW_ID,
                        title = stringResource(R.string.ui_iab_open_browser),
                        summary = stringResource(R.string.ui_iab_open_browser_summary),
                    ),
                    SettingsRowSpec.Toggle(
                        id = BROWSER_MEDIA_GRANT_ROW_ID,
                        title = stringResource(R.string.ui_iab_media_grant),
                        summary = stringResource(R.string.ui_iab_media_grant_summary),
                        checked = uiState.autoGrantMedia,
                    ),
                    SettingsRowSpec.Toggle(
                        id = BROWSER_LIVE_IN_CHAT_ROW_ID,
                        title = stringResource(R.string.ui_iab_live_chat),
                        summary = stringResource(R.string.ui_iab_live_chat_summary),
                        checked = liveIabInChat,
                    ),
                    SettingsRowSpec.Slider(
                        id = BROWSER_MAX_TABS_ROW_ID,
                        title = stringResource(R.string.ui_iab_max_tabs),
                        summary = stringResource(R.string.ui_iab_max_tabs_summary),
                        value = uiState.maxTabs.toFloat(),
                        valueRange = 1f..8f,
                        steps = 7,
                        valueLabel = uiState.maxTabs.toString(),
                    ),
                    // r24 Item 5：点击 → 系统文件夹选择器；行尾预览 = 顶层文件夹名 / 未设置。
                    SettingsRowSpec.Navigation(
                        id = BROWSER_DOWNLOAD_DIR_ROW_ID,
                        title = stringResource(R.string.ui_iab_download_dir),
                        summary = stringResource(R.string.ui_iab_download_dir_summary),
                        currentState = uiState.downloadDirTreeName.ifBlank {
                            stringResource(R.string.ui_iab_download_dir_not_set)
                        },
                    ),
                ),
            ),
        ),
    )

    SettingsSpecPageContent(
        spec = spec,
        onAction = { action ->
            when (action) {
                is SettingsRowAction.Navigate -> when (action.id) {
                    BROWSER_OPEN_ROW_ID -> onOpenBrowser()
                    BROWSER_DOWNLOAD_DIR_ROW_ID -> downloadFolderLauncher.launch(null)
                    else -> Unit
                }
                is SettingsRowAction.ToggleChanged ->
                    when (action.id) {
                        BROWSER_MEDIA_GRANT_ROW_ID ->
                            viewModel.sendIntent(BrowserSettingsIntent.ToggleMediaGrant(action.checked))
                        BROWSER_LIVE_IN_CHAT_ROW_ID ->
                            scope.launch { XRepo.setShowLiveIabInChat(action.checked) }
                        else -> Unit
                    }
                // r15 点 5：拖动中（SliderChanged）只更新本地显示值，松手（SliderCommit）才
                // 热更新 IabManager.maxTabs 并持久化——避免高频拖动反复写 XRepo。
                is SettingsRowAction.SliderChanged ->
                    if (action.id == BROWSER_MAX_TABS_ROW_ID)
                        viewModel.sendIntent(BrowserSettingsIntent.SetMaxTabs(action.value.toInt()))
                is SettingsRowAction.SliderCommit ->
                    if (action.id == BROWSER_MAX_TABS_ROW_ID)
                        viewModel.sendIntent(BrowserSettingsIntent.SetMaxTabs(action.value.toInt()))
                else -> Unit
            }
        },
    )
}
