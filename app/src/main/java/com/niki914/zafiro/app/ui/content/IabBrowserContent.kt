package com.niki914.zafiro.app.ui.content

import android.content.Intent
import android.net.Uri
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Launch
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.niki914.uikit.infra.liquidScreenBottomPadding
import com.niki914.uikit.infra.liquidScreenTopPadding
import com.niki914.uikit.infra.nav.pageViewModel
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.helper.ZafiroToast
import com.niki914.zafiro.app.ui.model.IabBrowserEffect
import com.niki914.zafiro.app.ui.model.IabBrowserIntent
import com.niki914.zafiro.app.ui.model.IabBrowserViewModel
import com.niki914.zafiro.app.ui.model.isLikelyUrl
import com.niki914.zafiro.chat.agentic.iab.IabManager

/**
 * IAB 浏览器屏幕（对应 ZCode IAB 的可视化面）：
 * 地址栏 + 进度 + WebView（AndroidView，跨标签复用同一组 WebView 实例）+ 标签条 +
 * 工具行（截图/控制台/下载/历史）+ 控制台与下载面板。
 * 所有状态来自 [IabBrowserViewModel]（MVI）；WebView 实例归属 agent-runtime 的
 * [IabManager]（与 agent 的 browser 工具共享同一组标签）。
 *
 * r13：
 * - 顶 / 底边距由 [liquidScreenTopPadding] / [liquidScreenBottomPadding] 下发——
 *   地址栏 / 标签条不再压在 Liquid 顶栏（状态栏 + 返回箭头）之下，底部不贴导航栏。
 * - WebView 区右下角带缩放把手：拖动可改变区域高度（30%..95%，本会话内记忆），
 *   网页版式随新尺寸自动 reflow。
 * - 地址栏新增「在其它应用打开」（UI 侧 ACTION_VIEW，不进 agent 控制路径）。
 */
@Composable
fun IabBrowserContent(
    modifier: Modifier = Modifier,
    viewModel: IabBrowserViewModel = pageViewModel(),
) {
    val uiState by viewModel.uiStateFlow.collectAsState()
    val context = LocalContext.current
    var urlDraft by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        viewModel.sendIntent(IabBrowserIntent.Load)
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                IabBrowserEffect.None -> Unit
                is IabBrowserEffect.Toast -> ZafiroToast.show(effect.message, Toast.LENGTH_LONG)
            }
        }
    }

    // r19 BUG3：新标签 URL 空白 → 立即清空草稿；加载中保持现状；加载完成后回填最终 URL。
    LaunchedEffect(uiState.activeTabId, uiState.progress) {
        val active = uiState.tabs.firstOrNull { it.active }
        if (active != null) {
            if (active.url.isBlank()) {
                urlDraft = ""
            } else if (uiState.progress == 100 && urlDraft != active.url) {
                urlDraft = active.url
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(
                start = 16.dp,
                top = liquidScreenTopPadding(8.dp),
                end = 16.dp,
                bottom = liquidScreenBottomPadding(12.dp),
            ),
    ) {
        // ---- 地址栏 + 在其它应用打开（单键入：Enter 提交导航）----
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = urlDraft,
                onValueChange = { urlDraft = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text(stringResource(R.string.ui_iab_address_hint)) },
                textStyle = MaterialTheme.typography.bodyMedium,
                keyboardOptions = KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Go,
                    keyboardType = KeyboardType.Uri,
                ),
                keyboardActions = KeyboardActions(
                    onGo = {
                        val target = urlDraft.trim()
                        if (target.isNotEmpty()) {
                            // r16 点 5：非 URL 输入走 Google 搜索（isLikelyUrl 判定）。
                            val final = if (isLikelyUrl(target)) target
                                else "https://www.google.com/search?q=${java.net.URLEncoder.encode(target, Charsets.UTF_8)}"
                            viewModel.sendIntent(IabBrowserIntent.Navigate(final))
                        }
                    },
                ),
            )
            // 在其它应用打开（UI 侧 ACTION_VIEW；不进 IabManager / agent 控制路径）。
            // r14：FilledTonalButton 文字按钮替代两个同色圆形图标——旧的两个
            // FilledIconButton 视觉完全相同（金色圆 + 相近图标），用户无法区分；
            // 现在文字标签自解释，且与下方导航按钮职责不再重叠（导航统一走 Go）。
            FilledTonalButton(
                onClick = {
                    val url = urlDraft.trim()
                    if (url.isNotEmpty()) {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse(if (url.contains("://")) url else "https://$url"),
                                ),
                            )
                        }
                    }
                },
            ) {
                Icon(
                    Icons.Default.Launch,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.ui_iab_open_in_app), style = MaterialTheme.typography.labelLarge)
            }
        }

        // ---- 标签条（水平滚动；激活标签高亮，可关闭；始终可见，空态也保留以便新建）----
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            item("new_tab_button") {
                FilledIconButton(onClick = { viewModel.sendIntent(IabBrowserIntent.NewTab) }) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.ui_iab_new_tab))
                }
            }
            items(uiState.tabs, key = { it.id }) { tabUi ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (tabUi.active) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.clickable {
                        viewModel.sendIntent(IabBrowserIntent.SwitchTab(tabUi.id))
                    },
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.padding(start = 10.dp, top = 2.dp, bottom = 2.dp),
                    ) {
                        Text(
                            text = tabUi.title.ifBlank { tabUi.url.ifBlank { tabUi.id } },
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                        IconButton(
                            onClick = { viewModel.sendIntent(IabBrowserIntent.CloseTab(tabUi.id)) },
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.ui_iab_close_tab),
                            )
                        }
                    }
                }
            }
        }

        // ---- 进度（标签条之下，贴近内容）---- r16 点 12：平滑进度条替换 LinearProgressIndicator
        IabSmoothProgressBar(
            progress = uiState.progress,
            busy = uiState.busy,
            loadToken = uiState.loadToken,
            modifier = Modifier.fillMaxWidth(),
        )

        // ---- WebView 宿主（标签切换 = 换 WebView 实例，实例由 IabManager 持有）----
        val holder = remember { FrameLayout(context) }
        DisposableEffect(uiState.activeTabId) {
            val tabId = uiState.activeTabId
            runCatching { IabManager.attachUi(holder, tabId) }
            onDispose {
                runCatching { IabManager.detachUi(holder) }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.6f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
        ) {
            AndroidView(
                factory = { holder },
                modifier = Modifier.fillMaxSize(),
            )
            if (uiState.tabs.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Default.Android,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    )
                    Text(
                        text = stringResource(R.string.ui_iab_no_tab),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        // ---- 工具行（导航 / 操作 / 面板 三组，组间用竖线分隔）----
        // r14 点 1：整行放在带 Surface 底色的横条里，图标用 onSurface（全主题
        // 可见），竖线分隔保留——旧版裸 IconButton 在深色主题下对比度不足。
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { viewModel.sendIntent(IabBrowserIntent.Back) }) {
                    Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = stringResource(R.string.ui_iab_back),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(onClick = { viewModel.sendIntent(IabBrowserIntent.Forward) }) {
                    Icon(
                        Icons.Default.ArrowForward,
                        contentDescription = stringResource(R.string.ui_iab_forward),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(onClick = { viewModel.sendIntent(IabBrowserIntent.Reload) }) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.ui_iab_reload),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(onClick = { viewModel.sendIntent(IabBrowserIntent.Stop) }) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = stringResource(R.string.ui_iab_stop),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(24.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
                IconButton(onClick = { viewModel.sendIntent(IabBrowserIntent.Screenshot) }) {
                    Icon(
                        Icons.Default.Image,
                        contentDescription = stringResource(R.string.ui_iab_screenshot),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(onClick = { viewModel.sendIntent(IabBrowserIntent.RefreshConsole) }) {
                    Icon(
                        Icons.Default.Code,
                        contentDescription = stringResource(R.string.ui_iab_console),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                // r24 Item 3：工具栏"下载日志"开关按钮——用 History 图标（区别于 DownloadPage 的 Download），
                // 移到整排最右，点开/收起下方下载日志面板。
                IconButton(onClick = { viewModel.sendIntent(IabBrowserIntent.DownloadPage) }) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = stringResource(R.string.ui_iab_download_page),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(onClick = { viewModel.sendIntent(IabBrowserIntent.ToggleDownloads) }) {
                    Icon(
                        Icons.Default.History,
                        contentDescription = stringResource(R.string.ui_iab_downloads),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        uiState.lastError?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        // ---- 控制台 / 下载面板（按需展开；各块自带标题与水平滚动，不再裸堆 monospace 文本）----
        if (uiState.console.isNotBlank() && uiState.consoleOpen) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text(
                        text = stringResource(R.string.ui_iab_console),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    HorizontalDivider()
                    Text(
                        text = uiState.console,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .height(160.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }
        }
        // r23 Item 4：下载日志面板——按需展开（downloadsOpen 开关，与 consoleOpen 对称），
        // 自带 Clear / Close 头按钮 + 垂直滚动正文 + 高对比配色（surface / onSurface）。
        if (uiState.downloadsOpen) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.ui_iab_downloads),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { viewModel.sendIntent(IabBrowserIntent.ClearDownloads) },
                        ) {
                            Text(
                                text = stringResource(R.string.ui_iab_downloads_clear),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        IconButton(
                            onClick = { viewModel.sendIntent(IabBrowserIntent.ToggleDownloads) },
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.ui_iab_downloads_close),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    HorizontalDivider()
                    if (uiState.downloads.isEmpty()) {
                        Text(
                            text = stringResource(R.string.ui_iab_downloads_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .height(160.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            uiState.downloads.forEach { dl ->
                                Text(
                                    text = "${dl.status} ${dl.path} (${dl.sizeBytes} B)",
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState())
                                        .padding(vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

