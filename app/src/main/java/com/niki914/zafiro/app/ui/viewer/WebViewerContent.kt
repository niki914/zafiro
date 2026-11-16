package com.niki914.zafiro.app.ui.viewer

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.niki914.uikit.screen.ReportTitleBarCollapsed
import com.niki914.uikit.screen.zafiroScreenBottomPadding
import com.niki914.uikit.screen.zafiroScreenTopPadding
import com.niki914.zafiro.api.util.runCatchingCancellable
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.PageBackHandler
import com.niki914.zafiro.app.ui.PageChromeContribution
import com.niki914.zafiro.app.ui.PageChromeMenuItem
import com.niki914.zafiro.app.ui.RegisterPageChrome
import com.niki914.zafiro.app.ui.chat.helper.honestCopyToClipboard
import com.niki914.zafiro.app.ui.nav.NoTitle
import com.niki914.zafiro.app.ui.nav.TextTitle
import com.niki914.zafiro.app.ui.nav.TopBarActionSpec
import kotlinx.coroutines.launch
import java.io.File
import androidx.core.net.toUri

/**
 * 网页还在组合树里时，壳层不要把整页录进毛玻璃。
 * 滑走动画结束前网页仍在树上，计数归零后才恢复录制。
 */
internal class WebBackdropGate {
    var activeCount by mutableIntStateOf(0)
        private set

    @Composable
    fun Hold() {
        DisposableEffect(this) {
            activeCount++
            onDispose { activeCount-- }
        }
    }
}

internal val LocalWebBackdropGate = staticCompositionLocalOf<WebBackdropGate?> { null }

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun WebViewerContent(
    content: ViewerContent.Web,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val webBackdropGate = LocalWebBackdropGate.current
    webBackdropGate?.Hold()

    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    val refreshLabel = stringResource(R.string.ui_viewer_refresh)
    val openInBrowserLabel = stringResource(R.string.ui_viewer_open_in_browser)
    val copyLinkLabel = stringResource(R.string.ui_viewer_copy_link)
    val copyPathLabel = stringResource(R.string.ui_viewer_copy_path)

    val rawTarget = content.url.trim()
    val isRemoteWeb = remember(rawTarget) {
        rawTarget.startsWith("http://", ignoreCase = true) || rawTarget.startsWith("https://", ignoreCase = true)
    }

    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var pageTitle by rememberSaveable(content.url) { mutableStateOf(content.initialTitle.orEmpty()) }
    var canGoBack by remember { mutableStateOf(false) }
    var loadProgress by remember { mutableFloatStateOf(0f) }

    val chrome = remember(
        refreshLabel,
        openInBrowserLabel,
        copyLinkLabel,
        copyPathLabel,
        pageTitle,
        canGoBack,
        onBack,
        isRemoteWeb,
        rawTarget,
        webViewRef,
    ) {
            val titleSpec = if (pageTitle.isNotBlank()) TextTitle(pageTitle) else NoTitle
            val menuItems = buildList {
                add(
                    PageChromeMenuItem(
                        key = "refresh",
                        title = refreshLabel,
                        onClick = { webViewRef?.reload() },
                    )
                )
                if (isRemoteWeb) {
                    add(
                        PageChromeMenuItem(
                            key = "open_in_browser",
                            title = openInBrowserLabel,
                            onClick = {
                                val currentUrl = webViewRef?.url ?: rawTarget
                                val targetUri = currentUrl.toUri()
                                val intent = Intent(Intent.ACTION_VIEW, targetUri).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                runCatchingCancellable { context.startActivity(intent) }
                            },
                        )
                    )
                    add(
                        PageChromeMenuItem(
                            key = "copy_link",
                            title = copyLinkLabel,
                            onClick = {
                                val currentUrl = webViewRef?.url ?: rawTarget
                                scope.launch {
                                    honestCopyToClipboard(
                                        text = currentUrl,
                                        clipboard = clipboard,
                                        hapticFeedback = haptic,
                                    )
                                }
                            },
                        )
                    )
                } else {
                    add(
                        PageChromeMenuItem(
                            key = "copy_path",
                            title = copyPathLabel,
                            onClick = {
                                scope.launch {
                                    honestCopyToClipboard(
                                        text = rawTarget,
                                        clipboard = clipboard,
                                        hapticFeedback = haptic,
                                    )
                                }
                            },
                        )
                    )
                }
            }

            PageChromeContribution(
                titleSpec = titleSpec,
                leftAction = TopBarActionSpec(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    onClick = onBack,
                    contentDescription = "Back Button",
                ),
                backHandler = PageBackHandler(
                    shouldConsumeBack = { canGoBack },
                    onConsumeBack = { webViewRef?.goBack() },
                ),
                menuItems = menuItems,
            )
    }
    val topBarProgress = if (loadProgress in 0.01f..0.99f) loadProgress else null
    RegisterPageChrome(chrome.copy(topBarProgress = topBarProgress))

    ReportTitleBarCollapsed { false }

    val assetLoader = remember(context) {
        WebViewAssetLoader.Builder()
            .setDomain("appassets.androidplatform.net")
            .addPathHandler("/files/", WebViewAssetLoader.InternalStoragePathHandler(context, context.filesDir))
            .build()
    }

    val targetUrlToLoad = remember(rawTarget, context) {
        resolveWebTarget(rawTarget, context)
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.apply {
                stopLoading()
                loadUrl("about:blank")
                clearHistory()
                removeAllViews()
                destroy()
            }
            webViewRef = null
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(
                top = zafiroScreenTopPadding(0.dp),
                bottom = zafiroScreenBottomPadding(0.dp),
            ),
    ) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        loadWithOverviewMode = true
                        useWideViewPort = true
                        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        allowFileAccess = true
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onReceivedTitle(view: WebView?, title: String?) {
                            if (!title.isNullOrBlank() &&
                                !title.startsWith("http://", ignoreCase = true) &&
                                !title.startsWith("https://", ignoreCase = true) &&
                                !title.startsWith("file://", ignoreCase = true)
                            ) {
                                pageTitle = title
                            }
                        }

                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            loadProgress = newProgress / 100f
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest,
                        ): WebResourceResponse? {
                            return assetLoader.shouldInterceptRequest(request.url)
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ): Boolean {
                            val uri = request.url ?: return false
                            val scheme = uri.scheme?.lowercase().orEmpty()
                            if (scheme == "http" || scheme == "https" || scheme == "file") {
                                return false
                            }
                            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            return runCatchingCancellable {
                                ctx.startActivity(intent)
                                true
                            }.getOrDefault(false)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            canGoBack = view?.canGoBack() == true
                        }

                        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                            canGoBack = view?.canGoBack() == true
                        }
                    }
                    loadUrl(targetUrlToLoad)
                    webViewRef = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

internal fun resolveWebTarget(target: String, context: Context): String =
    resolveWebTarget(target, context.filesDir)

internal fun resolveWebTarget(target: String, filesRoot: File): String {
    val trimmed = target.trim()
    if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
        return trimmed
    }
    val rawPath = if (trimmed.startsWith("file://", ignoreCase = true)) {
        trimmed.removePrefix("file://")
    } else {
        trimmed
    }
    val targetFile = File(rawPath)
    return runCatchingCancellable {
        val canonicalFile = targetFile.canonicalPath
        val canonicalRoot = filesRoot.canonicalPath
        if (canonicalFile.startsWith(canonicalRoot)) {
            val relativePath = canonicalFile.removePrefix(canonicalRoot).trimStart('/')
            "https://appassets.androidplatform.net/files/$relativePath"
        } else {
            "file://$canonicalFile"
        }
    }.getOrDefault("file://${targetFile.absolutePath}")
}
