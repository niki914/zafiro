package com.niki914.zafiro.app.ui.model

import androidx.lifecycle.viewModelScope
import com.niki914.uikit.base.ComposeMVIViewModel
import com.niki914.zafiro.chat.agentic.iab.IabCaptchaProbe
import com.niki914.zafiro.chat.agentic.iab.IabManager
import com.niki914.zafiro.chat.agentic.iab.IabManager.IabError
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 标签页 UI 快照（来自 IabManager 的 IabTabInfo，去掉进程内引用）。 */
data class IabTabUi(
    val id: String,
    val url: String,
    val title: String,
    val active: Boolean,
    val progress: Int,
)

/** 下载记录 UI 快照。 */
data class IabDownloadUi(
    val id: String,
    val url: String,
    val path: String,
    val sizeBytes: Long,
    val status: String,
    val error: String?,
)

data class IabBrowserUiState(
    val tabs: List<IabTabUi> = emptyList(),
    val activeTabId: String? = null,
    val progress: Int = 0,
    val busy: Boolean = false,
    /** r16 点 12：每次新导航 +1，供进度条 snap-to-0 判别"新一载"。 */
    val loadToken: Long = 0L,
    val console: String = "",
    val consoleOpen: Boolean = false,
    val downloads: List<IabDownloadUi> = emptyList(),
    /** r23 Item 4：下载日志面板的开合开关（与 consoleOpen 对称；Clear 后仍保持打开）。 */
    val downloadsOpen: Boolean = false,
    val screenshotPath: String? = null,
    val autoGrantMedia: Boolean = IabManager.autoGrantMedia,
    val lastError: String? = null,
    /** 当前活动标签是否正停在一个验证墙（CAPTCHA）上，需要用户手动完成。 */
    val captchaPending: Boolean = false,
)

sealed interface IabBrowserIntent {
    /** 进入页面：确保至少一个标签 + 刷新全部快照。 */
    data object Load : IabBrowserIntent

    /** 地址栏提交（有标签 → 导航；无标签 → 新开）。 */
    data class Navigate(val url: String) : IabBrowserIntent

    data object NewTab : IabBrowserIntent
    data object Reload : IabBrowserIntent
    data object Stop : IabBrowserIntent
    data object Back : IabBrowserIntent
    data object Forward : IabBrowserIntent

    data class CloseTab(val id: String) : IabBrowserIntent
    data class SwitchTab(val id: String) : IabBrowserIntent

    data object Screenshot : IabBrowserIntent
    /** r22c Bug3：下载当前页（复用 IabManager.handleDownloadStart 的后台落盘 + 环形缓冲）。 */
    data object DownloadPage : IabBrowserIntent
    data object RefreshConsole : IabBrowserIntent
    data object RefreshDownloads : IabBrowserIntent
    /** r23 Item 4：开合下载日志面板（与 RefreshConsole 对称的 toggle）。 */
    data object ToggleDownloads : IabBrowserIntent
    /** r23 Item 4：清空下载日志环形缓冲（仅清记录，不删已落盘文件）。 */
    data object ClearDownloads : IabBrowserIntent
    /** 轻量标签快照刷新（聊天窗格轮询用）：只拉 tabs，不碰 downloads / console。 */
    data object RefreshTabs : IabBrowserIntent

    /** 验证墙（CAPTCHA）探测（聊天窗格轮询用）：命中后 captchaPending 置位，驱动 UI 提示。 */
    data object RefreshCaptcha : IabBrowserIntent
    data class SetAutoGrantMedia(val enabled: Boolean) : IabBrowserIntent
}

sealed interface IabBrowserEffect {
    data object None : IabBrowserEffect
    data class Toast(val message: String) : IabBrowserEffect
}

/**
 * IAB 页面 MVI ViewModel。
 *
 * 状态与一次性效果的分界（项目 MVI 约定）：
 * - tabs/progress/console/downloads/screenshotPath = State（重组后要还能读到同一个答案）；
 * - 导航成功后的 Toast、错误提示 = Effect（错过就过期）。
 */
class IabBrowserViewModel :
    ComposeMVIViewModel<IabBrowserIntent, IabBrowserUiState, IabBrowserEffect>() {

    override fun initUiState(): IabBrowserUiState = IabBrowserUiState()

    override suspend fun handleIntent(intent: IabBrowserIntent) {
        when (intent) {
            IabBrowserIntent.Load -> {
                ensureTab()
                refreshTabs()
                refreshDownloads()
            }

            is IabBrowserIntent.Navigate -> {
                val url = intent.url.trim()
                if (url.isEmpty()) return

                beginIabLoad()

                try {
                    if (IabManager.tabs().isEmpty()) {
                        IabManager.newTab(url)
                    } else {
                        IabManager.navigate(null, url)
                    }

                    startIabProgressPoll { activeIabProgress() }
                } catch (e: IabError) {
                    finishIabLoad()
                    updateState {
                        copy(lastError = "${e.code}: ${e.message}")
                    }
                }
            }

            IabBrowserIntent.NewTab -> {
                try {
                    IabManager.newTab(null)
                    refreshTabs()
                } catch (e: IabError) {
                    updateState { copy(lastError = e.message) }
                }
            }

            IabBrowserIntent.Reload -> runTabAction { IabManager.reload(null) }
            IabBrowserIntent.Stop -> runTabAction { IabManager.stop(null) }
            IabBrowserIntent.Back -> runTabAction { IabManager.back(null) }
            IabBrowserIntent.Forward -> runTabAction { IabManager.forward(null) }

            is IabBrowserIntent.CloseTab -> {
                try {
                    IabManager.closeTab(intent.id)
                    refreshTabs()
                } catch (e: IabError) {
                    updateState { copy(lastError = e.message) }
                }
            }

            is IabBrowserIntent.SwitchTab -> {
                try {
                    IabManager.activateTab(intent.id)
                    refreshTabs()
                } catch (e: IabError) {
                    updateState { copy(lastError = e.message) }
                }
            }

            IabBrowserIntent.Screenshot -> {
                try {
                    val path = IabManager.screenshotPath(null)
                    updateState { copy(screenshotPath = path) }
                    sendEffect(IabBrowserEffect.Toast("Screenshot saved: $path"))
                } catch (e: IabError) {
                    updateState { copy(lastError = "${e.code}: ${e.message}") }
                }
            }

            // r22c Bug3："下载当前页"——不再只是刷新面板。复用 IabManager.downloadCurrentPage
            // 走 handleDownloadStart 的后台 HttpURLConnection 落盘 + 环形缓冲，
            // 落盘成功后 refreshDownloads() 把 SAVING→DONE 的同实例刷进 UI。
            IabBrowserIntent.DownloadPage -> {
                try {
                    val entry = IabManager.downloadCurrentPage(null)
                    refreshDownloads()
                    // r23 Item 4：下载触发后自动展开下载日志面板（用户要求"点下载后日志立刻出现"）。
                    updateState { copy(downloadsOpen = true) }
                    sendEffect(IabBrowserEffect.Toast("Downloading ${entry.url}…"))
                } catch (e: IabError) {
                    updateState { copy(lastError = "${e.code}: ${e.message}") }
                } catch (e: Exception) {
                    // require(url.isNotEmpty()) 的 IllegalStateException 走这里。
                    updateState { copy(lastError = e.message ?: "Download failed.") }
                }
            }

            IabBrowserIntent.RefreshConsole -> {
                val wasOpen = currentState.consoleOpen
                if (wasOpen) {
                    updateState { copy(consoleOpen = false) }
                } else {
                    // r21 Item 2（F3b）：优先读原生 onConsoleMessage 环（r17/r18 的可靠通道，
                    // 无需 JS 钩子安装）；原生环为空时回落 JS 环。两路都为空才提示无捕获。
                    val native = runCatching { IabManager.consoleOf(null, 50) }.getOrNull()
                    val nativeLines = parseConsoleArray(native)
                    val display = if (nativeLines.isNotEmpty()) {
                        nativeLines.joinToString("\n")
                    } else {
                        val jsRaw = runCatching { IabManager.evaluate(null, CONSOLE_JS) }.getOrNull()
                        parseConsoleJs(jsRaw).ifEmpty { null }
                    }
                    updateState {
                        copy(
                            console = display ?: "(no console capture yet)",
                            consoleOpen = true,
                        )
                    }
                }
            }

            IabBrowserIntent.RefreshDownloads -> refreshDownloads()

            IabBrowserIntent.RefreshTabs -> refreshTabs()

            IabBrowserIntent.RefreshCaptcha -> refreshCaptcha()

            IabBrowserIntent.ToggleDownloads -> toggleDownloads()

            IabBrowserIntent.ClearDownloads -> clearDownloads()

            is IabBrowserIntent.SetAutoGrantMedia -> {
                IabManager.autoGrantMedia = intent.enabled
                updateState { copy(autoGrantMedia = intent.enabled) }
            }
        }
    }

    /** 无标签时先开一个空白页（UI 与 agent 共享同一组标签，不各自为政）。 */
    private suspend fun ensureTab() {
        if (IabManager.tabs().isEmpty()) {
            runCatching { IabManager.newTab(null) }
        }
    }

    private suspend fun refreshTabs() {
        val tabs = runCatching { IabManager.tabs() }.getOrDefault(emptyList())
        val active = tabs.firstOrNull { it.active }
        updateState {
            copy(
                tabs = tabs.map { IabTabUi(it.id, it.url, it.title, it.active, it.progress) },
                activeTabId = active?.id,
                // 载入中且属于当前这次加载时保留轮询写入的实时进度；
                // 否则（非 busy / token 已翻篇）回落到标签快照里的 0/100 终值。
                progress = if (busy && loadToken == this@IabBrowserViewModel.loadToken) progress
                    else (active?.progress ?: 0),
            )
        }
    }

    private suspend fun refreshDownloads() {
        val list = runCatching { IabManager.downloads(20) }.getOrDefault(emptyList())
        updateState {
            copy(
                downloads = list.map {
                    IabDownloadUi(it.id, it.url, it.path, it.sizeBytes, it.status, it.error)
                },
            )
        }
    }

    /**
     * r23 Item 4：开合下载日志面板（与 RefreshConsole 对称）。
     * 首次打开（面板关闭）时拉一次最新下载记录并置位 downloadsOpen；已打开则关闭。
     * 下载过程中面板保持打开，后台 SAVING→DONE 由 refreshDownloads 同实例刷入。
     */
    private suspend fun toggleDownloads() {
        val wasOpen = currentState.downloadsOpen
        if (wasOpen) {
            updateState { copy(downloadsOpen = false) }
        } else {
            refreshDownloads()
            updateState { copy(downloadsOpen = true) }
        }
    }

    /** r23 Item 4：清空下载日志环形缓冲（不删已落盘文件）；面板保持打开以示"已清空"。 */
    private suspend fun clearDownloads() {
        IabManager.clearDownloads()
        updateState { copy(downloads = emptyList()) }
    }

    /**
     * r16 点 12：进度状态机（Qwen 源，API 面已改到 ComposeMVIViewModel 的 updateState）。
     * loadToken 每次新导航 +1；进度轮询 job 用 token 判别"是否还是同一次加载"，
     * 旧加载的迟到结果不会污染新加载的进度。
     */
    private var loadToken: Long = 0L
    private var progressJob: Job? = null

    private fun beginIabLoad() {
        loadToken += 1L
        progressJob?.cancel()
        progressJob = null

        updateState {
            copy(
                progress = 0,
                busy = true,
                loadToken = this@IabBrowserViewModel.loadToken,
                lastError = null,
                captchaPending = false,
            )
        }
    }

    private fun finishIabLoad() {
        progressJob?.cancel()
        progressJob = null

        updateState {
            if (loadToken == this@IabBrowserViewModel.loadToken) {
                copy(
                    progress = 100,
                    busy = false,
                )
            } else {
                this
            }
        }
    }

    private fun reportIabProgress(p: Int) {
        val safe = p.coerceIn(0, 100)

        updateState {
            if (busy && loadToken == this@IabBrowserViewModel.loadToken) {
                copy(progress = safe)
            } else {
                this
            }
        }
    }

    private suspend fun activeIabProgress(): Int =
        runCatching { IabManager.tabs() }
            .getOrDefault(emptyList())
            .firstOrNull { it.active }
            ?.progress
            ?: 0

    /** 轮询加载进度（每 500ms 一次，最多 15s），结束前刷新一次标签快照。 */
    private fun startIabProgressPoll(provider: suspend () -> Int) {
        progressJob?.cancel()

        val token = loadToken
        progressJob = viewModelScope.launch {
            repeat(30) {
                delay(500L)

                if (token != loadToken) return@launch

                val p = runCatching { provider() }
                    .getOrDefault(100)
                    .coerceIn(0, 100)

                reportIabProgress(p)

                if (p >= 100) {
                    finishIabLoad()
                    return@launch
                }
            }

            if (token == loadToken) {
                refreshTabs()
                finishIabLoad()
            }
        }
    }

    /** 验证墙探测（只读、不点击）：命中时 captchaPending=true，未命中清回 false。 */
    private suspend fun refreshCaptcha() {
        val probe = runCatching { IabManager.captchaProbe(null) }.getOrNull()
        val pending = probe is IabCaptchaProbe.Detected
        updateState { copy(captchaPending = pending) }
    }

    private suspend fun runTabAction(action: suspend () -> Unit) {
        try {
            action()
            refreshTabs()
        } catch (e: IabError) {
            updateState { copy(lastError = e.message) }
        }
    }

    private companion object {
        /** 控制台读取 JS（与 browser 工具 console action 同源：IabJs.consoleRead）。 */
        val CONSOLE_JS = com.niki914.zafiro.chat.agentic.iab.IabJs.consoleRead(50)

        /**
         * r21 Item 2（F3b）：解析原生 onConsoleMessage 环（IabManager.consoleOf 的 JSON 数组字符串，
         * 元素为转义后的日志行）为可读行列表。解析失败返回空。
         */
        fun parseConsoleArray(raw: String?): List<String> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching {
                val arr = org.json.JSONArray(raw)
                val out = ArrayList<String>(arr.length())
                for (i in 0 until arr.length()) {
                    val s = arr.getString(i)
                    if (s.isNotBlank()) out.add(s)
                }
                out
            }.getOrElse { emptyList() }
        }

        /**
         * r21 Item 2（F3b）：解析 JS 环（IabJs.consoleRead 的 JSON 对象字符串，
         * {"count":N,"lines":[...]}）为可读行文本。解析失败返回空串。
         */
        fun parseConsoleJs(raw: String?): String {
            if (raw.isNullOrBlank()) return ""
            return runCatching {
                val obj = org.json.JSONObject(raw)
                val lines = obj.optJSONArray("lines") ?: org.json.JSONArray()
                val sb = StringBuilder()
                for (i in 0 until lines.length()) {
                    val s = lines.optString(i)
                    if (s.isNotBlank()) {
                        if (sb.isNotEmpty()) sb.append('\n')
                        sb.append(s)
                    }
                }
                sb.toString()
            }.getOrElse { "" }
        }
    }
}
