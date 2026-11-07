package com.niki914.zafiro.chat.agentic.iab

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import android.os.Bundle
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.annotation.MainThread
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import com.niki914.logging.Logger
import com.niki914.xposed.api.util.ActivityProvider
import com.niki914.xposed.api.util.ContextProvider
import com.niki914.zafiro.chat.agentic.SharedImageCodec
import com.niki914.zafiro.chat.agentic.image.ImageCodec
import com.niki914.zafiro.chat.agentic.image.IngestResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.json.JSONObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume

/**
 * IAB（In-App Browser）核心管理器：进程级单例（与 [SharedImageCodec] 同一装配模式——
 * 懒创建，上下文来自 App 启动时 ContextProvider.provide(applicationContext)，工具层零参构造即可调用）。
 *
 * 能力面（对齐 ZCode 的 IAB）：
 * - 多标签：每标签独占一个系统 WebView（Chromium 内核），[MAX_TABS] 上限，LRU 驱逐；
 *   注：现代 SDK 的 WebViewClient 已移除 onCreatedWindow，window.open / target=_blank 不会自动开新标签，
 *   需要时由 agent 显式 new_tab。
 * - 导航：navigate / back / forward / reload / stop；SSL 错误不静默放行（handler.cancel）。
 * - JS：evaluate（任意表达式 + await 语义，结果 JSON 归一）/ querySelector / page_text / page_html。
 * - 交互：fill（React 兼容 value setter）/ click / click_text / click_at / set_checked /
 *   select_option / keypress / scroll / submit_form。
 * - 文件上传：JS 点击 input[type=file] 触发原生选择器 → onShowFileChooser 回填 FileProvider Uri，
 *   只接受 App 私有目录内的文件（filesDir / cacheDir）。
 * - 下载捕获：WebView.setDownloadListener → 后台落盘 filesDir/downloads/，环形记录（status/size 可查）。
 * - 截图（r33 单一路径，**可见 Dialog 宿主**（OPT2）软件重光栅；彻底取代 r31 的离屏 overlay 宿主）：
 *   截图是给 **agent** 消费的，故**不**绑定用户所见活窗格（IAB 开 / 收起 / 全关，捕获都成立，
 *   窗口态与结果无关）。`screenshot` 新增 [view_mode] 形参（mobile / desktop，默认 mobile），
 *   按**固定宽度**独立渲染，与活标签 clientWidth 解耦（`emulate_desktop` 只改 UA、不改布局
 *   宽度，活标签恒为窄窗格；故活标签重光栅出细不可读）：
 *   1) 活标签 [IabTab.webView] 上量取种子：DOM 快照（[IabJs.pageHtml]，上限 200_000）+
 *      cookie（[CookieManager]）+ localStorage/sessionStorage（JS 导出）+ 按 [view_mode] 配的
 *      user-agent（mobile=[defaultUserAgent]、desktop=[DESKTOP_UA]）；
 *   2) 新建**独立渲染 WebView**（[buildWebView] 后仅按 [view_mode] 覆写其 UA，**不**改全局
 *      [customUserAgent]），注入 `<meta viewport width=393|1280>` + storage 再播种脚本（try/catch
 *      兜底，失败即 no-op），`loadDataWithBaseURL(tab.url, wrapperHtml, …)`；
 *   3) **可见 Dialog 宿主**（r33 OPT2，取代 r31 的离屏 overlay）：渲染 WebView 挂进
 *      [ActivityProvider.current()]（resumed Activity，App.kt 生命周期回调维护的弱引用）上的
 *      真可见 `Dialog`（`FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE`、无标题）
 *      ——Chromium 合成器对真可见窗口确定产帧（r28/r30 的已知良好画素路径），代价是 ~150ms
 *      可见闪烁；宿主对话框无交互、不抢焦点；Activity 为 null（无前台 UI）→
 *      显式 [IAB_SCREENSHOT_FAILED]，绝不挂起。`Dialog.show` 走 runCatching（宿主 attach/
 *      显示失败 → 显式 [IAB_SCREENSHOT_FAILED]），可见性门禁（**宿主已就绪** 门，r34 起：
 *      `dialog.isShowing && hostFrame.isAttachedToWindow && ViewCompat.isLaidOut(hostFrame)`；
 *      布局静置用 ViewTreeObserver.OnGlobalLayoutListener 确定性触发（Qwen 裁决 thread
 *      e659f019）；r33 的 `hostFrame.isShown && getGlobalVisibleRect` 全局可见门禁已废除——
 *      它测的是**整个 App 的全局可见区**，会在 App 前台时误 abort）不满足才在绘制循环前
 *      abort（绝不在 dismiss 之后评估）；
 *   4) 在该宽度**内**重测整文档 scrollHeight（D2=b；高度是页面在**此宽度**下的布局高，
 *      不复用活标签值）；两级静置 2s+4s；`draw(canvas)` dpr=2（位图密度 320）；双哨兵 +
 *      [pdfPageHasContent] 全白守卫；仍全白 → [IAB_BLANK_CAPTURE]；
 *   5) finally dismiss Dialog + 销毁渲染 WebView（`detachFromParent` + `destroy()`），活标签
 *      全程不动；[encodeIabPng] → [SharedImageCodec] ingest。
 *   只有管线真失败（宿主取不到 / 全白守卫后仍全白 / PNG 编码失败）才抛
 *   [IAB_SCREENSHOT_FAILED] / [IAB_BLANK_CAPTURE]（全白守卫 r28 引入、r30/r31 保留），
 *   绝不产出 0 字节文件、绝不静默回传白图。
 *   （**不**走 `WebView.createPrintDocumentAdapter().onLayout/onWrite`：其 `LayoutResultCallback`/
 *   `WriteResultCallback` 无参构造在 AOSP 里 `android.print` 包私有，应用代码编译不通，本管线绕开。）
 * - 日志：控制台钩子（console.* → 500 条环形缓冲）+ 网络请求级日志（200 条环形缓冲）。
 * - Cookie：android.webkit CookieManager（WebView 数据目录内跨重启持久）。
 * - 其余：剪贴板读写、UA 覆盖、桌面模式、文本缩放、媒体权限请求（onPermissionRequest 可开关自动放行）、
 *   本地文件 / HTML 字符串预览（data: URI）。
 *
 * 线程模型：全部 WebView 访问走主线程（withContext(Dispatchers.Main)）；下载在 IO 线程；
 * 标签表与环形缓冲加锁，主线程回调与挂起 API 共用。
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
object IabManager {

    private const val TAG = "niki914_zafiro_IabManager"

    /** 标签上限（低内存设备约束；驱逐 LRU 非激活标签，cookie 仍在 WebView 数据目录，重新导航可恢复会话）。 */
    const val MAX_TABS = 4

    /** r15 点 5：上限可配置（App 侧设置页 1..8），运行中调整即时生效；默认 [MAX_TABS]。 */
    @Volatile
    var maxTabs: Int = MAX_TABS

    /**
     * r23 Item 3：下载目录可配置（App 侧「浏览器」设置页）。App 层写入 [File]；
     * null = 走默认 [android.app.Context.getFilesDir] `filesDir/downloads`。
     * 目标目录不存在时 [handleDownloadStart] 会 `mkdirs()` 补齐（失败则回默认目录，不中断下载）。
     */
    @Volatile
    var downloadDir: File? = null

    /**
     * r24 Item 5：下载目录可走 SAF 树（App 侧设置页用 Android 文件夹选择器 OpenDocumentTree 选中）。
     * 设值后 [handleDownloadStart] 优先写入该树内；`null` = 回退 [downloadDir] / 默认目录。
     * 树 URI 由 App 层 `takePersistableUriPermission` 授权后传入，可跨重启保留。
     */
    @Volatile
    var downloadDirTree: Uri? = null

    /** 树顶层文件夹名（仅用于设置行尾显示 / 日志，可空）。 */
    @Volatile
    var downloadDirTreeName: String? = null

    /** 错误码（BrowserBuiltin 映射为工具结果 code）。 */
    const val IAB_UNAVAILABLE = "IAB_UNAVAILABLE"
    const val IAB_NO_TAB = "IAB_NO_TAB"
    const val IAB_BAD_TAB = "IAB_BAD_TAB"
    const val IAB_FILE_FORBIDDEN = "IAB_FILE_FORBIDDEN"
    const val IAB_FILE_MISSING = "IAB_FILE_MISSING"
    const val IAB_BAD_URL = "IAB_BAD_URL"
    const val IAB_SCREENSHOT_FAILED = "IAB_SCREENSHOT_FAILED"
    /**
     * r28：空白捕获守卫——离屏渲染出的页面 ~100% 为白底（渲染未完成 / 无内容）时抛出。
     * 绝不把一张全白图当成成功的截图返回给 agent（r28 Item 2b）。
     */
    const val IAB_BLANK_CAPTURE = "IAB_BLANK_CAPTURE"
    const val IAB_JS_ENGINE_ERROR = "IAB_JS_ENGINE_ERROR"
    const val IAB_DOWNLOAD_FAILED = "IAB_DOWNLOAD_FAILED"

    /** evaluate 引擎回调超时（防标签销毁 / 页面崩溃导致永久挂起）。 */
    private const val EVALUATE_TIMEOUT_MS = 60_000L

    // r19：Promise 结果落地改用「pending 全局 + 轮询」——async IIFE 的回调值会序列化成 "{}"，
    // 因此 evaluateEnvelope(awaitPromise=true) 立即返回 __ZAFIRO_EVAL_PENDING__:<id>，
    // 实际结果由 [evaluatePollEnvelope] 从 window.__zafiroEvalResults 取回。
    private const val EVALUATE_PENDING_PREFIX = "__ZAFIRO_EVAL_PENDING__:"

    /** evaluate 整体（含 pending 轮询）超时——复用旧的 60s 引擎回调语义。 */
    private const val EVALUATE_ENVELOPE_TIMEOUT_MS = 60_000L

    /** pending 轮询节奏（~60fps，与主线程帧同步）。 */
    private const val EVALUATE_POLL_INTERVAL_MS = 16L

    /**
     * r31：截图固定渲染宽度（px）——按 [view_mode] 二选一，**与活标签的 clientWidth / 可见窗格尺寸
     * 完全无关**。新无头渲染 WebView 按该固定宽度重新排布整页，再全文档高光栅化（2× dpr，文本
     * 清晰）。r30 的「以活标签 clientWidth 为准、钳到 IAB_CAPTURE_WIDTH 上限」已随双路径活标签重
     * 光栅一并废弃：`emulate_desktop` 只改 UA/viewport、不改布局宽度，故活标签布局宽恒为窄窗格
     * （~93–241px），截图出细不可读——r31 用固定宽度彻底解耦。
     */
    const val IAB_CAPTURE_MOBILE_W = 393
    const val IAB_CAPTURE_DESKTOP_W = 1280

    /** r31 固定截图像素比：位图按 dpr=2 出（`Bitmap.setDensity(2*160f=320)`），文本锐利、体积可控。 */
    private const val IAB_CAPTURE_DPR = 2f

    /**
     * r26 起：统一整页捕获（`screenshot`，r28 起 print_pdf 已合并）的**硬超时**（ms）。
     * 整个「可见宿主软件重光栅」管线（活标签 JS 量取几何 → 宿主挂载/尺寸钉定 → 静置帧 → draw →
     * 全白守卫重试）包在 `withTimeoutOrNull` 内；超时 / OOM / 内核卡死 → 显式
     * [IAB_SCREENSHOT_FAILED]，绝不挂起、绝不回退旧帧。
     */
    const val IAB_CAPTURE_TIMEOUT_MS = 30_000L

    /**
     * r31：无头离屏渲染的**首帧静置**（ms）——离屏 WM 宿主挂载 + 种子页面 `loadDataWithBaseURL`
     * 后、`draw(canvas)` 前让 Chromium 软件栅格化跟上的等待。须显著小于外层 [IAB_CAPTURE_TIMEOUT_MS]
     * （30s）使整条链安全收敛。
     */
    private const val IAB_CAPTURE_PAINT_WAIT_MS = 2_000L

    /**
     * r31：全白守卫**重试帧静置**（ms）——首帧判全白后多等这段再对同一离屏渲染 WebView 重光栅
     * 一次（两级 2s+4s 静置）；仍全白则抛 [IAB_BLANK_CAPTURE]（绝不静默回传白图）。首帧 ~2s +
     * 重试 ~4s + draw/PNG 开销控制在 30s 外层内。
     */
    private const val IAB_CAPTURE_RETRY_WAIT_MS = 4_000L

    /**
     * r31 无头离屏渲染的双哨兵（dual sentinel）参数：
     * 注入**新渲染 WebView 的整文档**的两个 2×2px `#010203` `position:fixed` 标记（左上 / 左下，
     * `z-index: 2147483647`、`pointer-events:none`），draw 后在位图顶/底 64px 带内扫描近色像素。
     * 双点而非单点：单点命中可能是恰好渲染成该色的页面内容；两点同中说明「整文档两端都被
     * 光栅化」——`enableSlowWholeDocumentDraw` 生效且 draw 真正画到文档底部。
     * 容差 24/通道（软件光栅可能有轻微色差，不要求逐 bit 相等）。
     * 哨兵留在最终位图里（agent 侧消费者，左上/左下各 2 个近黑像素可接受；摘除需再走一轮
     * JS→draw，不值）。捕获结束一律 [IAB_SENTINEL_REMOVE_JS] 摘除（渲染 WebView 随即销毁，
     * 不影响活标签）。
     */
    private const val IAB_SENTINEL_BAND_PX = 64
    private const val IAB_SENTINEL_TOLERANCE = 24

    /**
     * r31 离屏宿主静置：离屏 WM 挂载 + `loadDataWithBaseURL` 后 `invalidate` 等的主线程帧数。
     * Chromium 软件栅格化需要若干帧把内容推到 `draw(canvas)` 可读的层；2 帧 + 100ms 兜底
     * （attach/repaint 竞态）是首帧预算。
     */
    private const val IAB_CAPTURE_SETTLE_FRAMES = 2
    private const val IAB_CAPTURE_SETTLE_DELAY_MS = 100L

    /**
     * r31 全白重试的退避阶梯（ms）——draw 后静置时长递增，共 [IAB_CAPTURE_RETRY_MAX_ATTEMPTS]
     * 次、总预算 ≈4s（与 [IAB_CAPTURE_RETRY_WAIT_MS] 对齐），每次间隔等两帧让光栅跟上。
     * 对**同一离屏渲染 WebView** 重光栅（单一路径，无 `attachedVisible` 再分派）。
     */
    private val IAB_CAPTURE_RETRY_BACKOFF_MS = intArrayOf(16, 32, 64, 128, 250, 500, 750, 1000)
    private const val IAB_CAPTURE_RETRY_MAX_ATTEMPTS = 8

    /**
     * r28 Item 2b 引入、r30 保留：空白判定阈值——栅格化页面中**非白像素占比**低于该比例
     * 即判为"全白页"。用 0.5%（万分之一~五）而非 0：真实内容页几乎必然显著超过；
     * 接近全白的渲染失败会远低于它。判在 [pdfPageHasContent] 的采样网格上，避免
     * 全像素扫描的重开销。
     */
    private const val IAB_BLANK_CONTENT_RATIO = 0.005f

    /** 空白判定的"白"参考色（与白底一致；ARGB 全不透明白）。 */
    private const val IAB_BLANK_WHITE = 0xFFFFFFFF.toInt()

    /** 空白判定采样网格的步长（px）：每隔 32px 取一个像素，O((W/32)·(H/32))，足够稳又不重。 */
    private const val IAB_BLANK_SAMPLE_STRIDE_PX = 32

    class IabError(val code: String, override val message: String) : Exception(message)

    /** 文件上传结果：ACCEPTED = 内核已接受；CHOOSER_SKIPPED = 选择器未弹出或选择被取消。 */
    data class IabUploadResult(val status: String, val detail: String)

    private val lock = Any()
    private val tabs = mutableListOf<IabTab>()
    private var seq = 0

    /** r30：[WebView.enableSlowWholeDocumentDraw] 的 once-guard（静态全进程调用，构造首个标签前触发）。 */
    private val slowWholeDocumentDrawEnabled = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile
    var autoGrantMedia: Boolean = true

    @Volatile
    var customUserAgent: String? = null

    @Volatile
    var desktopMode: Boolean = false

    @Volatile
    private var context: Context? = null

    private val downloads = IabRingBuffer<IabDownload>(50)
    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val defaultUserAgent: String?
        get() = runCatching { WebSettings.getDefaultUserAgent(context) }.getOrNull()

    /** 非挂起可用性检查（JVM 单测 / 冷启动未就绪时使用）。 */
    fun isReady(): Boolean = context != null

    /**
     * 取应用上下文；未就绪（App 冷启动早期 / JVM 单测环境）时立即抛 [IAB_UNAVAILABLE]，
     * 绝不挂起——这保证 browser 工具在任何环境都不会卡死，而是把「稍后重试」交给模型。
     */
    private fun contextOrUnavailable(): Context {
        context?.let { return it }
        val ctx = ContextProvider.awaitIfAvailable()?.applicationContext
            ?: throw IabError(IAB_UNAVAILABLE, "IAB context is not ready yet; retry shortly after app start.")
        synchronized(this) {
            if (context == null) context = ctx
        }
        return ctx
    }

    // ------------------------------------------------------------- tabs

    private fun createTabBlocking(): IabTab {
        val ctx = context ?: throw IabError(IAB_UNAVAILABLE, "IAB not initialized yet; retry after app start.")
        val tab = synchronized(lock) {
            if (tabs.size >= maxTabs) {
                val lru = tabs.filter { !it.active }.minByOrNull { it.lastActiveMs }
                if (lru != null) {
                    detachFromParent(lru.webView)
                    lru.webView.destroy()
                    tabs.remove(lru)
                    Logger.d(TAG, "IAB LRU-evicted tab ${lru.id} (${lru.url})")
                }
            }
            seq += 1
            val t = IabTab(id = "t$seq", webView = buildWebView(ctx).apply { setBackgroundColor(Color.WHITE) }, createdAtMs = System.currentTimeMillis())
            tabs.forEach { it.active = false }
            t.active = true
            t.touch()
            tabs.add(t)
            t
        }
        tab.configureClients()
        injectConsoleHook(tab)
        return tab
    }

    private fun buildWebView(ctx: Context): WebView {
        // r30：enableSlowWholeDocumentDraw 是全进程静态开关，必须在首个 WebView 实例构造前调用
        // 才生效（Chromium 内核据此整文档栅格化，而非按可视区裁剪——整页 draw 的 pre-flag）。
        // once-guard：重复调用无副作用，runCatching 兜底（极少数 OEM 上该 API 行为异常也不拖垮标签创建）。
        if (slowWholeDocumentDrawEnabled.compareAndSet(false, true)) {
            runCatching { WebView.enableSlowWholeDocumentDraw() }
                .onFailure { Logger.w(TAG, "enableSlowWholeDocumentDraw unavailable: ${it.message}") }
        }
        val webView = WebView(ctx)
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.builtInZoomControls = false
        s.setSupportZoom(true)
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.setJavaScriptCanOpenWindowsAutomatically(true)
        s.userAgentString = customUserAgent ?: s.userAgentString
        return webView
    }

    private fun IabTab.configureClients() {
        webView.webViewClient = IabWebViewClient(IabManager)
        webView.webChromeClient = IabWebChromeClient(IabManager)
        // 下载钩子挂在 WebView 自身（WebChromeClient 无 onDownloadStart 重载可覆写）
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            val t = tabForWebView(webView) ?: activeTabForWebView()
            t?.let { handleDownloadStart(it, url, userAgent, contentDisposition, mimeType) }
        }
    }

    /** 新建标签（url 为 null = 空白页）。 */
    suspend fun newTab(url: String? = null): IabTabInfo {
        contextOrUnavailable()
        return withContext(Dispatchers.Main) {
            val tab = createTabBlocking()
            if (url != null && url.isNotEmpty()) loadUrlBlocking(tab, url)
            tab.snapshot()
        }
    }

    suspend fun tabs(): List<IabTabInfo> {
        contextOrUnavailable()
        return withContext(Dispatchers.Main) {
            synchronized(lock) { tabs.map { it.snapshot() } }
        }
    }

    suspend fun closeTab(tabId: String): Boolean {
        contextOrUnavailable()
        return withContext(Dispatchers.Main) {
            val removed = synchronized(lock) {
                val idx = tabs.indexOfFirst { it.id == tabId }
                if (idx < 0) null else tabs.removeAt(idx)
            }
            if (removed == null) throw IabError(IAB_BAD_TAB, "No tab with id '$tabId'. Current: ${synchronized(lock) { tabs.map { it.id } }}")
            detachFromParent(removed.webView)
            removed.webView.destroy()
            synchronized(lock) {
                if (removed.active) tabs.firstOrNull()?.active = tabs.isNotEmpty()
            }
            true
        }
    }

    suspend fun activateTab(tabId: String): IabTabInfo {
        contextOrUnavailable()
        return withContext(Dispatchers.Main) {
            val tab = synchronized(lock) { tabs.firstOrNull { it.id == tabId } }
                ?: throw IabError(IAB_BAD_TAB, "No tab with id '$tabId'.")
            synchronized(lock) {
                tabs.forEach { it.active = false }
                tab.active = true
            }
            tab.touch()
            tab.snapshot()
        }
    }

    // ------------------------------------------------------------- navigation

    private suspend fun tabForSuspend(tabId: String?): IabTab = withContext(Dispatchers.Main) {
        tabForBlocking(tabId)
    }

    private fun tabForBlocking(tabId: String?): IabTab {
        synchronized(lock) {
            if (tabId == null) {
                val t = tabs.firstOrNull { it.active } ?: tabs.firstOrNull()
                    ?: throw IabError(IAB_NO_TAB, "No open tab. Call 'new_tab' (or 'navigate') first.")
                t.active = true
                t.touch()
                return t
            }
            val t = tabs.firstOrNull { it.id == tabId }
                ?: throw IabError(IAB_BAD_TAB, "No tab with id '$tabId'. Current: ${tabs.map { it.id }}")
            tabs.forEach { it.active = false }
            t.active = true
            t.touch()
            return t
        }
    }

    /** 拒绝 script 伪 URL；超大 data: 载荷拒绝（防内存炸弹）；其余放行。 */
    private fun sanitizeUrl(url: String) {
        val u = url.trim()
        if (u.isEmpty()) throw IabError(IAB_BAD_URL, "url is empty.")
        val low = u.lowercase(Locale.ROOT)
        if (low.startsWith("javascript:") || low.startsWith("vbscript:")) {
            throw IabError(IAB_BAD_URL, "Refusing to load '$u' (script pseudo-URLs are not allowed).")
        }
        if (low.startsWith("data:") && u.length > 400_000) {
            throw IabError(IAB_BAD_URL, "data: payload too large (${u.length} chars; cap 400000).")
        }
    }

    suspend fun navigate(tabId: String?, url: String): IabTabInfo {
        contextOrUnavailable()
        return withContext(Dispatchers.Main) {
            sanitizeUrl(url)
            val tab = tabForBlocking(tabId)
            loadUrlBlocking(tab, url)
            tab.snapshot()
        }
    }

    private fun loadUrlBlocking(tab: IabTab, url: String) {
        tab.url = url
        tab.progress = 0
        runCatching { tab.webView.loadUrl(url) }
        injectConsoleHook(tab)
    }

    suspend fun back(tabId: String?): IabTabInfo {
        contextOrUnavailable()
        return withContext(Dispatchers.Main) {
            val tab = tabForBlocking(tabId)
            if (tab.webView.canGoBack()) tab.webView.goBack()
            tab.snapshot()
        }
    }

    suspend fun forward(tabId: String?): IabTabInfo {
        contextOrUnavailable()
        return withContext(Dispatchers.Main) {
            val tab = tabForBlocking(tabId)
            if (tab.webView.canGoForward()) tab.webView.goForward()
            tab.snapshot()
        }
    }

    suspend fun reload(tabId: String?): IabTabInfo {
        contextOrUnavailable()
        return withContext(Dispatchers.Main) {
            val tab = tabForBlocking(tabId)
            tab.webView.reload()
            tab.snapshot()
        }
    }

    suspend fun stop(tabId: String?): IabTabInfo {
        contextOrUnavailable()
        return withContext(Dispatchers.Main) {
            val tab = tabForBlocking(tabId)
            tab.webView.stopLoading()
            tab.snapshot()
        }
    }

    // ------------------------------------------------------------- JS / DOM

    /**
     * 在标签内执行 JS（主线程派发，回调桥接为挂起；超时 [EVALUATE_ENVELOPE_TIMEOUT_MS] 返回 null）。
     *
     * r19：载荷若是 [IabJs.evaluateEnvelope]（awaitPromise=true）形态，引擎回调值形如
     * `__ZAFIRO_EVAL_PENDING__:<id>`——真正的结果异步写入 window.__zafiroEvalResults，
     * 此处按 [EVALUATE_POLL_INTERVAL_MS] 节奏轮询 [IabJs.evaluatePollEnvelope] 直至取回（并
     * 从全局 map 删除，防泄漏）；非 pending 载荷直接返回，旧调用方（page_text / 交互原语 /
     * 裸 JSON.stringify 表达式）不受影响。
     */
    suspend fun evaluate(tabId: String?, js: String): String? {
        contextOrUnavailable()
        return withContext(Dispatchers.Main) {
            val tab = tabForBlocking(tabId)
            val deferred = CompletableDeferred<String?>()
            runCatching {
                tab.webView.evaluateJavascript(js) { value ->
                    if (!deferred.isCompleted) deferred.complete(value)
                }
            }.onFailure { e -> throw IabError(IAB_JS_ENGINE_ERROR, "evaluate failed to dispatch: ${e.message}") }
            withContext(Dispatchers.IO) {
                withTimeoutOrNull(EVALUATE_ENVELOPE_TIMEOUT_MS) {
                    var payload = deferred.await().unwrapJsonStringLiteral()
                    if (payload?.startsWith(EVALUATE_PENDING_PREFIX) == true) {
                        val id = payload.substring(EVALUATE_PENDING_PREFIX.length)
                        while (true) {
                            payload = tab.webView.awaitEvaluate(IabJs.evaluatePollEnvelope(id)).unwrapJsonStringLiteral()
                            if (payload != null && payload != "null") return@withTimeoutOrNull payload
                            delay(EVALUATE_POLL_INTERVAL_MS)
                        }
                    }
                    payload
                }
            }
        }
    }

    /** 主线程派发一段 JS 并挂起等待引擎回调（返回 Android 序列化后的字面值）。 */
    private suspend fun WebView.awaitEvaluate(script: String): String? =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                evaluateJavascript(script) { result -> continuation.resume(result) }
            }
        }

    /**
     * Android 会把 JS 字符串回调值再包一层双引号（JSON 字符串字面量）：
     * `"{}"` / `"\"{\\\"ok\\\":true,…}\""`。剥回内层原文；非字符串形态（"null" 等）原样返回。
     */
    private fun String?.unwrapJsonStringLiteral(): String? {
        val raw = this ?: return null
        if (raw.length >= 2 && raw.first() == '"' && raw.last() == '"') {
            return runCatching { org.json.JSONArray("[$raw]").getString(0) }.getOrDefault(raw)
        }
        return raw
    }

    private fun injectConsoleHook(tab: IabTab) {
        runCatching { tab.webView.evaluateJavascript(IabJs.consoleHook()) { } }
    }

    /**
     * r20：guarantee the console ring-buffer hook is installed before reading the console.
     *
     * [injectConsoleHook] is fire-and-forget: if the page was loaded before the hook fired,
     * or an SPA navigation replaced the console bindings, or DOMContentLoaded never fired,
     * `window.__zafiroConsole` may be empty or undefined, so [IabJs.consoleRead] returns
     * count:0. This function:
     *  1. looks up the tab and calls [injectConsoleHook] once,
     *  2. probes `window.__zafiroConsoleHook === true` via [evaluate],
     *  3. if the probe returned not-true (page was mid-navigation or hook was reset),
     *     re-injects once more and probes again,
     *  4. returns the final confirmed state.
     *
     * Call this in `BrowserBuiltin.kt` "console" action BEFORE [IabJs.consoleRead].
     * Does not modify IabJs.kt or evaluate().
     */
    suspend fun ensureConsoleHook(tabId: String?): Boolean {
        contextOrUnavailable()
        val tab = withContext(Dispatchers.Main) { tabForBlocking(tabId) }
        // Step 1: try existing injection (fire-and-forget but cheap)
        injectConsoleHook(tab)
        // Step 2: probe whether the hook flag is set
        val probeScript = "JSON.stringify(window.__zafiroConsoleHook === true)"
        val probe = evaluate(tabId, probeScript)
        val parsed = probe?.trim()?.removeSurrounding("\"")
        if (parsed == "true") return true
        // Step 3: re-inject once more (page may have been mid-navigation when first call ran)
        injectConsoleHook(tab)
        // Step 4: final probe
        val probe2 = evaluate(tabId, probeScript)
        val parsed2 = probe2?.trim()?.removeSurrounding("\"")
        return parsed2 == "true"
    }

    /**
     * 验证墙（CAPTCHA / 人机校验）探测（UI 侧公开入口，纯只读、不点击）：
     * 命中返回 [IabCaptchaProbe]（kind + hints），未命中返回 [IabCaptchaProbe.None]。
     * 冷启动 / 无标签 / 引擎异常一律收敛为 None（调用方据此不弹提示，不挂起、不抛）。
     * 供聊天窗格的轮询环使用：加载完成或疑似卡死时调用，命中标记后驱动 UI 提示用户手动完成。
     */
    suspend fun captchaProbe(tabId: String? = null): IabCaptchaProbe =
        runCatching {
            val raw = evaluate(tabId, IabJs.captchaProbe())
            parseCaptchaProbeJson(raw)
        }.getOrDefault(IabCaptchaProbe.None)

    /** 解析 [IabJs.captchaProbe] 的 JSON 信封（{ok,detected,kind,hints,selector,inputCount,inputLabel}）。 */
    private fun parseCaptchaProbeJson(raw: String?): IabCaptchaProbe {
        if (raw.isNullOrBlank()) return IabCaptchaProbe.None
        val obj = runCatching {
            Json.parseToJsonElement(raw).jsonObject
        }.getOrNull() ?: return IabCaptchaProbe.None
        fun s(key: String): String? = (obj[key] as? JsonPrimitive)?.contentOrNull
        val detected = (obj["detected"] as? JsonPrimitive)?.contentOrNull?.toBoolean() == true
        if (!detected) return IabCaptchaProbe.None
        val hints = (obj["hints"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .orEmpty()
        return IabCaptchaProbe.Detected(
            kind = s("kind").orEmpty(),
            hints = hints,
            selector = s("selector").orEmpty(),
            inputCount = s("inputCount")?.toIntOrNull() ?: 0,
            inputLabel = s("inputLabel").orEmpty(),
        )
    }

    // ------------------------------------------------------------- screenshot

    /**
     * 截图（公共 UI 侧入口）：成功返回落盘路径（String，跨模块可见）；
     * 失败抛 [IabError]（code = IAB_SCREENSHOT_FAILED / IAB_UNAVAILABLE）。
     * 工具侧请直接用 [screenshot]（内部 IngestResult 契约，与 screenshot/view_image 一致）。
     *
     * [viewMode] = "mobile"（默认，393px）或 "desktop"（1280px）——固定无头渲染宽度，
     * 与活标签的 clientWidth / 可见窗格尺寸无关（r31 裁定 D1/D2/E1/E2/E3）。
     */
    suspend fun screenshotPath(tabId: String?, viewMode: String = "mobile"): String {
        return when (val r = screenshot(tabId, viewMode)) {
            is IngestResult.Ok -> r.image.path
            is IngestResult.Err -> throw IabError(IAB_SCREENSHOT_FAILED, "screenshot failed: ${r.error}")
        }
    }

    /**
     * 截图（r29 起唯一工具入口——print_pdf 已于 r28 合并进本入口；r33 起走**单一可见
     * Dialog 宿主**（OPT2）软件重光栅，彻底取代 r31 的离屏 overlay 宿主）：
     *
     * 活标签窗格态（开 / 收起 / 全关）与结果无关——截图是给 agent 消费的，绑定活窗格宽度
     * 会让 `emulate_desktop` 失效（它只改 UA、不改布局宽度，活标签恒窄窗格 ~93–241px，
     * 截图出细不可读）。r31 改在**独立渲染 WebView** 上按 [viewMode] 固定宽度渲染，与活
     * 标签完全解耦。
     *
     * 机制：[captureHeadless] 新建渲染 WebView → 注入 DOM 快照 + cookie + storage + 按
 * [viewMode] 的 UA → **可见 Dialog 宿主**（r33 OPT2：[ActivityProvider.current()] 上的
 * 真可见 Dialog，`FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE`、无标题，对话框随 WebView
 * 几何自适应、~150ms 可见闪烁——Chromium 合成器对真可见窗口确定产帧；r31 的离屏
 * `TYPE_APPLICATION_OVERLAY` 宿主被证伪：attach/layout 均成功但合成器视其不可见，
 * draw(canvas) 全白，r33 诊断日志实证）→ 在该宽度内重测 scrollHeight（D2=b）→ 两级
     * 静置 2s+4s → `draw(canvas)` dpr=2（位图密度 320）→ 双哨兵 + [pdfPageHasContent]
     * 全白守卫 → finally dismiss Dialog + 销毁渲染 WebView → [encodeIabPng] →
     * [SharedImageCodec] ingest。
     *
     * 管线真失败（无 resumed Activity → 显式 [IAB_SCREENSHOT_FAILED]「UI 回到前台后重试」；
     * 全白守卫后仍全白 → [IAB_BLANK_CAPTURE]；PNG 编码失败 → [IAB_SCREENSHOT_FAILED]），
     * 绝不挂起、绝不静默回传白图。
     *
     * @param viewMode "mobile"（默认，[IAB_CAPTURE_MOBILE_W] px）或 "desktop"（[IAB_CAPTURE_DESKTOP_W] px）；
     *                 未知值回退 "mobile"（agent 侧 SCHEMA 枚举只允许这两个值，此处兜底防
     *                 工具层绕过 schema 直调时传入非法字符串）。
     * UI 侧（设置页 / 聊天窗格的 AndroidView）请走 [screenshotPath]。
     */
    internal suspend fun screenshot(tabId: String?, viewMode: String = "mobile"): IngestResult =
        captureFullPagePng(tabId, viewMode)

    // ─────────────────── r33：可见 Dialog 宿主（OPT2）软件重光栅 ───────────────────
    //
    // r30 的活标签双路径（captureAttachedLiveTab / captureHiddenIabTab + ActivityProvider
    // Dialog 宿主）已被彻底移除：绑定活窗格宽度使 emulate_desktop 对截图无效（活标签恒窄）。
    // r31 改在**独立渲染 WebView** 上按固定宽度渲染，离屏 WM 宿主（x=y=-10000，FLAG_NOT_*
    // 三连）被假设 Chromium 照常光栅但永不在真屏可见——**r33 实证证伪**（宿主 attach/layout
    // 均成功，但合成器视其不可见，draw(canvas) 全白，r33 DIAG 日志在 r32 包上实证）。
    // r33 OPT2 因此复活 r30 的机制形状：宿主挂到 [ActivityProvider.current()]（resumed
    // Activity）上的**真可见 Dialog**（`FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE`、无标题、
    // 对话框随 WebView 几何自适应，~150ms 可见闪烁）——Chromium 对真可见窗口确定产帧。
    // 活标签全程不动。

    /**
     * r35：双哨兵注入脚本 + 整文档高度量取（**一次** JS 往返完成三件事，避免注入后再走
     * 一次 evaluate 的时序竞态）：
     *  ① 挂两个 2×2px `#010203` `position:fixed` 标记（左上 + 左下，
     *     `z-index:2147483647`、`pointer-events:none`）——draw 后在位图顶/底 [IAB_SENTINEL_BAND_PX]
     *     带内各扫一次近色像素（[IAB_SENTINEL_TOLERANCE] 容差）：双点同中说明整文档两端都被
     *     光栅化（[WebView.enableSlowWholeDocumentDraw] 生效 + draw 画到了文档底部）。
     *  ② 返回 `h = Math.max(documentElement.scrollHeight, documentBody.scrollHeight, 0)`——
     *     r35 起这是 [captureHeadless] 的主高度源（原 r31/r34 的裸 scrollHeight 探针在 DOM 尚未
     *     光栅化时返回 0，经 `coerceAtLeast(1)` 塌成 1px 高 → 393:1 极端宽高比 → 下游 400；
     *     该探针 r35 已删除）。脚本在首帧静置（[IAB_CAPTURE_PAINT_WAIT_MS]）**之后**执行，
     *     且高度计算**不含** innerHeight/viewport（r31 的 `Math.max(h,1)` 内层钳已移除）。
     *  ③ `top/bottom` 为哨兵注入结果（各 0/1；`document` 缺失时为 0，Kotlin 侧 `>0` 判定）。
     */
    private const val IAB_SENTINEL_INJECT_JS = "(function(){" +
        "function m(id,style){try{var e=document.getElementById(id);" +
        "if(!e){e=document.createElement('div');e.id=id;e.style.cssText=" +
        "'position:fixed;width:2px;height:2px;background:#010203;z-index:2147483647;pointer-events:none;'+style;" +
        "document.documentElement.appendChild(e);}return 1;}catch(x){return 0;}}" +
        "var de=document.documentElement;var b=document.body;" +
        "var h=Math.max(de?de.scrollHeight:0,b?b.scrollHeight:0,0);" +
        "return JSON.stringify({top:m('zafiroSentinelTop','left:0;top:0;')," +
        "bottom:m('zafiroSentinelBottom','left:0;bottom:0;'),h:h});})()"

    /** r30 哨兵摘除脚本：捕获结束一律执行（无论成败），不污染活页面 DOM。 */
    private const val IAB_SENTINEL_REMOVE_JS = "(function(){" +
        "try{document.getElementById('zafiroSentinelTop')?.remove();" +
        "document.getElementById('zafiroSentinelBottom')?.remove();}catch(x){}" +
        "return JSON.stringify({ok:true});})()"

    /**
     * r36 捕获路径视口 meta 归一脚本（Bug 1 修复）：在独立渲染 WebView 上把**所有**
     * `meta[name="viewport"]` 的内容归一为 `width=$contentW`。
     *
     * 背景（F1/F2，用户 DEX 实证）：`IabJs.desktopViewport(true)`（`emulate_desktop` 工具）
     * 会把 `content="width=1280"` 的 meta **写进活标签页面的 DOM**（[IabManager.setDesktopMode]
     * L1639）。[IabJs.pageHtml] 导出活标签 `outerHTML` 时**连该 meta 一起带出**；
     * [captureHeadless] 的 wrapper 在 `</head>` 位置追加的 `width=$contentW` meta 排在其后。
     * Chromium 依序采用视口 meta → 导出的 `width=1280` 生效 → mobile 模式（393px 宿主）下
     * 页面按 1280 CSS 宽布局、整体缩到 ~393/1280 → `documentElement.clientWidth` 读到
     * ≈143（用户日志实证）而非 393 → 哨兵高度探针读到 0 → 全白 393×700 图被判"成功"。
     * 同理，**页面自身**硬编码的 `width=1280`（常见于桌面站点）会触发同一陷阱。
     *
     * 修复：渲染 WebView 加载 wrapper 后（首帧静置后）在**该 WebView 自身**执行本脚本，
     * 把页内全部视口 meta 归一到 [contentW]（mobile=393 / desktop=1280），使
     * `documentElement.clientWidth` 读回 [contentW]。不改 `IabJs.desktopViewport`
     * （活标签桌面仿真语义 + `IabJsTest` 断言 `width=1280` 保持绿）——修复落在捕获路径。
     * 脚本在 [renderWebView] 上运行，与活标签 DOM 无关（无交叉污染）。
     */
    private fun normalizeViewportMetaJs(widthPx: Int): String = "(function(){" +
        "var ms=document.querySelectorAll('meta[name=\"viewport\"]');var n=0;" +
        "for(var i=0;i<ms.length;i++){try{ms[i].setAttribute('content','width=$widthPx');n++;}" +
        "catch(x){}}return JSON.stringify({n:n});})()"

    /**
     * 主线程：等 [Choreographer] 走完 [count] 帧（不可取消时随协程取消一起解除）。
     * Chromium 软件重光栅需要若干帧把内容推到 `draw(canvas)` 可读的层。
     */
    private suspend fun awaitFrames(count: Int) = withContext(Dispatchers.Main) {
        var remaining = count
        while (remaining > 0) {
            suspendCancellableCoroutine { cont ->
                Choreographer.getInstance().postFrameCallback {
                    remaining -= 1
                    if (remaining > 0) Choreographer.getInstance().postFrameCallback { }
                    cont.resume(Unit) { }
                }
            }
        }
    }

    /**
     * r33 可见 Dialog 宿主捕获（screenshot 唯一路径，OPT2）：在**独立渲染 WebView** 上按
     * [viewMode] 固定宽度（mobile=[IAB_CAPTURE_MOBILE_W] / desktop=[IAB_CAPTURE_DESKTOP_W]）
     * 重新排布 [tab] 的 DOM 快照，宿主挂到 [ActivityProvider.current()]（resumed Activity）上的
     * **真可见 Dialog**（`FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE`、无标题、~150ms 可见闪烁）——
     * r31 的离屏 `TYPE_APPLICATION_OVERLAY` 宿主被
     * 证伪（attach/layout 均成功但合成器视其不可见 → draw 全白；r33 诊断日志实证），OPT2
     * 复活的正是 r28/r30 的已知良好路径：真可见合成器窗口确定产帧。与活标签窗格开/合/关无关。
     *
     * 机制（r31 §6 裁定 D1/D2/E2/E3/E4/E5 + r33 OPT2 替换 E1）：
     *  1. 引擎侧（活标签 [IabTab.webView]，主线程）：导出 DOM `outerHTML`（[IabJs.pageHtml]，
     *     上限 200_000）+ `localStorage`/`sessionStorage`（JSON 序列化）+ cookie 头
     *     （[CookieManager.getCookie](tab.url)）。活标签全程不 reparent、不 invalidate、不动宿主。
     *  2. 构造 wrapper HTML：`outerHTML` + `<meta name="viewport" content="width=<mode 宽>">`
     *     + 内联 storage 再播种 `<script>`（`try/catch` 兜底，失败即 no-op，不阻塞渲染）。
     *  3. 新建独立渲染 WebView（[buildWebView] 后按 mode 覆写 UA：mobile=[defaultUserAgent] /
     *     desktop=[DESKTOP_UA]，**不改**全局 [customUserAgent]），挂进**可见 Dialog 宿主**
     *     （`Dialog(hostActivity).apply{ setContentView(hostFrame); show() }` 包 `runCatching`：
     *     Activity 为 null（无前台 UI）→ 显式 [IAB_SCREENSHOT_FAILED]；Dialog 显示失败 →
     *     同码收敛。随后可见性门禁（r34：`dialog.isShowing && hostFrame.isAttachedToWindow &&
     *     ViewCompat.isLaidOut(hostFrame)`；布局静置用 OnGlobalLayoutListener 确定性触发，
     *     取代 r33 的 `hostFrame.isShown && getGlobalVisibleRect` 全局可见门禁 + 盲 10×2 帧
     *     settle；Qwen 裁决 thread e659f019）不满足才在 draw 循环前 abort。
     *  4. `loadDataWithBaseURL(tab.url, wrapperHtml, ...)`（同 origin base URL → cookie 自动
     *     携带）→ 首帧静置（[IAB_CAPTURE_SETTLE_FRAMES] 帧 + [IAB_CAPTURE_PAINT_WAIT_MS]）→
     *     **在该渲染 WebView 的该固定宽度内重测 scrollHeight**（D2=b，不沿用活标签几何）→
     *     钉宿主到 `contentW × docHeight`（EXACTLY）再静置 → 注入双哨兵。
     *  5. `draw(canvas)`（[IAB_CAPTURE_DPR] 密度 320）→ 双哨兵 + [pdfPageHasContent] 全白守卫；
     *     全白 → 退避阶梯对**同一**渲染 WebView 重光栅（E5，无 `attachedVisible` 再分派），
     *     仍全白 → 抛 [IAB_BLANK_CAPTURE]。
     *  6. finally：主线程 `if (dialog.isShowing) dialog.dismiss()`（对话框窗口即刻消失，
     *     闪屏终止于 dismiss）+ [detachFromParent] + `destroy()` 渲染 WebView。
     *
     * @return 整文档位图（width = mode 固定宽；height = 该宽度内 scrollHeight 重测值），
     *         调用方负责 [Bitmap.recycle]。
     */
    private suspend fun captureHeadless(tab: IabTab, viewMode: String): Bitmap {
        val ctx = contextOrUnavailable()
        val contentW = if (viewMode == "desktop") IAB_CAPTURE_DESKTOP_W else IAB_CAPTURE_MOBILE_W
        val contentDpr = IAB_CAPTURE_DPR

        // ── 1. 引擎侧（活标签）：导出 DOM 快照 + storage + cookie（主线程）──
        val pageHtml: String
        val localStorageJson: String
        val sessionStorageJson: String
        withContext(Dispatchers.Main) {
            val html = tab.webView.awaitEvaluate(IabJs.pageHtml(200_000))
                ?.unwrapJsonStringLiteral()
                ?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }
                ?.optString("html")
                ?: ""
            pageHtml = html
            // 引擎回调值形如 `"…JSON 字面量…"`；unwrapJsonStringLiteral 已剥回内层原文。
            // 内层是 `JSON.stringify(localStorage)` 的结果（本身是合法 JSON 对象字面量）。
            localStorageJson = tab.webView.awaitEvaluate(
                "(function(){try{return JSON.stringify(localStorage)||'{}';}catch(e){return '{}';}})()"
            )?.unwrapJsonStringLiteral() ?: "{}"
            sessionStorageJson = tab.webView.awaitEvaluate(
                "(function(){try{return JSON.stringify(sessionStorage)||'{}';}catch(e){return '{}';}})()"
            )?.unwrapJsonStringLiteral() ?: "{}"
        }

        val base = if (tab.url.isNotEmpty()) tab.url else "about:blank"
        // 同 origin 下 cookie 由 CookieManager 自动携带（loadDataWithBaseURL 的 base 即 tab.url）；
        // 非 http(s) base（about:blank 等）cookie 不适用，跳过。
        val cookieHeader = if (base.startsWith("http://") || base.startsWith("https://")) {
            runCatching { CookieManager.getInstance().getCookie(base) ?: "" }.getOrDefault("")
        } else ""

        // ── 2. 构造 wrapper HTML：outerHTML + viewport meta + storage 再播种 + cookie 注释 ──
        val viewportMeta = "<meta name=\"viewport\" content=\"width=$contentW\">"
        val seedScript = buildString {
            append("<script>(function(){try{")
            append("var L=")
            append(IabJs.jsQuote(localStorageJson))
            append(";if(typeof L==='string')L=JSON.parse(L);if(L)Object.keys(L).forEach(function(k){try{localStorage.setItem(k,L[k]);}catch(e){}});")
            append("var S=")
            append(IabJs.jsQuote(sessionStorageJson))
            append(";if(typeof S==='string')S=JSON.parse(S);if(S)Object.keys(S).forEach(function(k){try{sessionStorage.setItem(k,S[k]);}catch(e){}})")
            if (cookieHeader.isNotEmpty()) {
                append(";var C=")
                append(IabJs.jsQuote(cookieHeader))
                append(";if(C)C.split('; ').forEach(function(p){try{document.cookie=p;}catch(e){}})")
            }
            append("}catch(e){}})()")
            append("</script>")
        }
        val injection = "$viewportMeta$seedScript"
        val wrapperHtml = buildString {
            append(pageHtml)
            val headClose = pageHtml.lastIndexOf("</head>")
            when {
                headClose > 0 -> insert(headClose, injection)
                else -> {
                    // 无 </head>（outerHTML 截断或无 head）：插到 <html> 开标签后
                    val htmlOpen = pageHtml.indexOf("<html")
                    val insertAt = if (htmlOpen >= 0) {
                        val gt = pageHtml.indexOf('>', htmlOpen)
                        if (gt >= 0) gt + 1 else htmlOpen
                    } else 0
                    insert(insertAt, "<head>$injection</head>")
                }
            }
        }

        // ── 3. 新建独立渲染 WebView（主线程；按 mode 覆写 UA，不动全局 customUserAgent）──
        val renderWebView = withContext(Dispatchers.Main) {
            buildWebView(ctx).apply {
                val ua = if (viewMode == "desktop") DESKTOP_UA else defaultUserAgent
                if (ua != null) settings.userAgentString = ua
                setBackgroundColor(Color.WHITE)
            }
        }

        // ── 4. 可见 Dialog 宿主（OPT2，r33 Qwen 裁决 §3）：真屏短暂可见（~150ms 闪烁），
        //     换取 Chromium 合成器确定产帧——r31/r32 的离屏 TYPE_APPLICATION_OVERLAY 宿主
        //     被证伪（宿主 attach/layout 均成功，但合成器视其不可见，draw(canvas) 全白）。
        //     r32 线程结构保留：构造 + addView + show 全在同一 runCatching{withContext(Main)} 原子块。
        //     宿主 Context 必须是 **Activity**（Dialog 硬要求）：[ActivityProvider.current()]
        //     （App.kt 生命周期回调维护的弱引用，r30 起在册；null = 无 resumed Activity →
        //     按 ActivityProvider KDoc 以 [IAB_SCREENSHOT_FAILED] 收敛，绝不挂起）。
        val hostActivity = ActivityProvider.current()
            ?: throw IabError(
                IAB_SCREENSHOT_FAILED,
                "No resumed Activity is available to host the capture Dialog — retry once the " +
                    "app UI is back in the foreground.",
            )
        // r32 线程教训保留：hostFrame 构造 + addView(renderWebView) + Dialog show **全部**在同一
        // 主线程原子块内——离主线程构造 FrameLayout/addView 会触发 WebViewChromium 的 UI-thread
        // 断言崩溃（r32 crash root cause），OPT2 不得重新引入该模式。
        val (hostFrame, dialog) = runCatching {
            withContext(Dispatchers.Main) {
                val frame = FrameLayout(ctx).apply {
                    addView(renderWebView, FrameLayout.LayoutParams(contentW, -1))
                }
                val d = Dialog(hostActivity).apply {
                    requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
                    window?.addFlags(
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    )
                    setContentView(frame)
                    show()
                }
                frame to d
            }
        }.onFailure { e ->
            withContext(Dispatchers.Main) {
                detachFromParent(renderWebView)
                renderWebView.destroy()
            }
            throw IabError(
                IAB_SCREENSHOT_FAILED,
                "Capture host Dialog could not be shown on the resumed Activity: ${e.message}.",
            )
        }.getOrThrow()
        // 触摸已禁用（FLAG_NOT_TOUCHABLE），但显式关掉 touch-outside 取消，
        // 避免极端竞态下 Dialog 被 dismiss、门禁检查（r34，draw 循环前的一次性检查）
        // 读到 isShowing=false 误 abort。
        runCatching { dialog.setCanceledOnTouchOutside(false) }

        try {
            // ── 4b. 可见窗口内的「宿主已就绪」门禁（r34，取代 r33 的 `getGlobalVisibleRect` 全局
            //    可见性门禁；Qwen 裁决 thread e659f019 §3）：
            //    * r33 的 bug：`hostFrame.getGlobalVisibleRect(r)` 测的是**整个 App 的全局可见
            //      区域**，而非宿主 Dialog 窗口自身的状态。当 App 在前台、目标标签健康时，
            //      它仍可能收敛为 false（首帧排布竞态 / 时序错位），导致本应成功的捕获被误 abort。
            //    * r34 门禁只问宿主 Dialog 窗口自己的三件事（均在**绘制循环之前**、Dialog 仍
            //      showing 的可见窗口内执行，绝不在 dismiss 之后评估——那会变成「我们刚把它
            //      关掉了它还在可见吗？」的恒假 tautology）：
            //        ① dialog.isShowing        —— 窗口已 attach 到 WindowManager（Qwen B 的 live 锚点）
            //        ② hostFrame.isAttachedToWindow —— 宿主 View 仍附着（它本身即蕴含 window token
            //           存活；Qwen A/B：显式 `getRootWindowToken()!=null` 与之冗余，仅**记录**不**门禁**）
            //        ③ ViewCompat.isLaidOut(hostFrame) —— 宿主 View 已被 measure+layout（Qwen A：
            //           这是「能向 Bitmap 出帧」的决定性前置；仅 attach 不足以保证已排布）
            //    * 确定性的布局静置（Qwen C）：OnGlobalLayoutListener 取代 r33 的盲 `awaitFrames(2)`，
            //      并保留 awaitFrames(2) 作上界、绝不让门禁挂死（外层仍有 30s 硬超时兜底）。
            //    * 每次检查记录观测值（用户 spec 3.4）：isShowing / attached / tokenAlive（仅记录）/
            //      isLaidOut / window?.visibility / 窗口 flag 位 —— tag `r34 GATE`。
            // 确定性布局静置（Qwen C）：等宿主 View 完成一次 global-layout（measure+layout），
            // 取代 r33 的盲 `awaitFrames(2)`；listener 在 globalLayout 触发后即移除（不留悬挂引用）。
            suspend fun waitForGlobalLayout(view: View, capMs: Long) {
                var layoutComplete = false
                val observer = view.viewTreeObserver
                if (observer != null) {
                    val listener = object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                        override fun onGlobalLayout() {
                            layoutComplete = true
                            runCatching { view.viewTreeObserver?.removeOnGlobalLayoutListener(this) }
                        }
                    }
                    observer?.addOnGlobalLayoutListener(listener)
                    var waitedMs = 0L
                    while (!layoutComplete && waitedMs < capMs) {
                        awaitFrames(1)
                        waitedMs += 16L
                    }
                } else {
                    withTimeoutOrNull(capMs) { awaitFrames(IAB_CAPTURE_SETTLE_FRAMES) }
                }
            }
            waitForGlobalLayout(hostFrame, capMs = 2_000L)

            // 决定性门禁检查（主线程；Dialog 仍 showing）：
            // ① dialog.isShowing  ② hostFrame.isAttachedToWindow  ③ ViewCompat.isLaidOut(hostFrame)
            // tokenAlive（getRootWindowToken()!=null）仅作观测记录（Qwen B：与 isAttachedToWindow 冗余）。
            val gatePass = withContext(Dispatchers.Main) {
                val isShowing = dialog.isShowing
                val attached = hostFrame.isAttachedToWindow
                val tokenAlive = hostFrame.windowToken != null
                val laidOut = ViewCompat.isLaidOut(hostFrame)
                val win = dialog.window
                Logger.d(
                    TAG,
                    "r34 GATE @final: isShowing=$isShowing attached=$attached " +
                        "tokenAlive=$tokenAlive isLaidOut=$laidOut " +
                        "winFlags=0x${(win?.attributes?.flags ?: 0).toString(16)} " +
                        "hostMeasured=${hostFrame.measuredWidth}x${hostFrame.measuredHeight}",
                )
                isShowing && attached && laidOut
            }
            if (!gatePass) {
                val failed =
                    if (!dialog.isShowing) "dialog not showing"
                    else if (!hostFrame.isAttachedToWindow) "host View detached from window"
                    else "host View not laid out (isLaidOut=false)"
                throw IabError(
                    IAB_SCREENSHOT_FAILED,
                    "ScreenshotFailed: window not ready (failed: $failed) " +
                        "[isShowing=${dialog.isShowing}, " +
                        "attached=${hostFrame.isAttachedToWindow}, " +
                        "token=${hostFrame.windowToken != null}, " +
                        "laidOut=${ViewCompat.isLaidOut(hostFrame)}] — " +
                        "capture aborted before the draw loop (Dialog still up; dismissed in finally).",
                )
            }
            // ── 5. 注入种子页面（cookie 由同 origin base URL 自动携带；DOM/storage 由 wrapper 重建）──
            withContext(Dispatchers.Main) {
                renderWebView.loadDataWithBaseURL(base, wrapperHtml, "text/html", "UTF-8", null)
            }
            withContext(Dispatchers.Main) { awaitFrames(IAB_CAPTURE_SETTLE_FRAMES) }
            delay(IAB_CAPTURE_PAINT_WAIT_MS)

            // ── 5b. r36 捕获路径视口 meta 归一（Bug 1 修复）：把渲染 WebView 页内**所有**
            //    `meta[name="viewport"]`（含 `emulate_desktop` 导出 / 页面自带 1280 meta）归一为
            //    `width=$contentW`。必须先于哨兵注入（高度探针要按正确宽度布局）+ 宿主钉定
            //    （钉定后的 layout pass 才按 contentW 生效）。脚本在 renderWebView 自身执行，
            //    与活标签 DOM 无关；改完立即 invalidate + 静置，让 Chromium 按新视口重排。
            withContext(Dispatchers.Main) {
                val metaJson = renderWebView.awaitEvaluate(normalizeViewportMetaJs(contentW))
                    ?.unwrapJsonStringLiteral()
                Logger.d(TAG, "r36 META: viewport metas normalized to width=$contentW " +
                    "mode=$viewMode → $metaJson")
                renderWebView.invalidate()
                awaitFrames(IAB_CAPTURE_SETTLE_FRAMES)
            }

            // ── 6. 双哨兵注入 + 整文档高度量取（r35：一次 JS 往返，替代 r31/r34 的裸
            //    scrollHeight 探针——后者在 DOM 尚未光栅化时返回 0，经 coerceAtLeast(1)
            //    塌成 1px 高 → 393:1 极端宽高比 → 下游 400）──
            //    执行序：首帧静置（上方 SETTLE_FRAMES + PAINT_WAIT_MS）之后、宿主钉定之前。
            //    h 为本渲染 WebView 内的 Math.max(documentElement.scrollHeight,
            //    body.scrollHeight, 0)（脚本内计算，见 [IAB_SENTINEL_INJECT_JS] KDoc）。
            //    floorH：按 [viewMode] 的视口高度下限（700 @ 393 / 900 @ 1280）——渲染高度
            //    塌到 0 时以该下限保底出图，绝不再 floor 到 1。
            //    哨兵 top/bottom 任一 <1（DOM 未就绪 / document 缺失）→ 本量取值判不可信，
            //    回落 [contentW]（宽度即高度，宽高比 1:1）继续走钉定 + 宽高比守卫，
            //    而非把 0 静默 floor 成极端图。
            val floorH = if (viewMode == "desktop") 900 else 700
            val sentinelJson = withContext(Dispatchers.Main) {
                renderWebView.awaitEvaluate(IAB_SENTINEL_INJECT_JS)
                    ?.unwrapJsonStringLiteral()
            }
            val sentinelObj = sentinelJson?.let { runCatching { JSONObject(it) }.getOrNull() }
            val sentinelH = sentinelObj?.optInt("h", -1) ?: -1
            val sentinelsInjected =
                (sentinelObj?.optInt("top", 0) ?: 0) > 0 && (sentinelObj?.optInt("bottom", 0) ?: 0) > 0
            if (sentinelH > 0 && sentinelsInjected) {
                Logger.d(TAG, "r35 HEIGHT @floor: h=$sentinelH floorH=$floorH mode=$viewMode")
            } else {
                Logger.w(TAG, "r35 HEIGHT @fallback: sentinel h=$sentinelH injected=$sentinelsInjected " +
                    "→ falling back to contentW=$contentW (guard ③ below still applies)")
            }
            val docHeight = (if (sentinelH > 0 && sentinelsInjected) sentinelH else contentW)
                .coerceAtLeast(floorH)

            // 双哨兵已在步骤 6 与本渲染 WebView 内注入（[IAB_SENTINEL_INJECT_JS]，
            // 首帧静置之后、宿主钉定之前）——此处不再重复注入。

            // ── 7. 首次 draw + 全白守卫；全白 → 退避阶梯对同一渲染 WebView 重光栅（E5）──
            // r35 守卫③：宽高比 >200 的一次长静置重试——位图宽高比塌到 >200:1（高度相对
            // 宽度退化，如 393×1）→ 多等 [IAB_CAPTURE_RETRY_WAIT_MS]（4s 长静置，原 r31
            // 全白专用、r35 起与哨兵重测共用）后**重跑哨兵注入 + 高度量取 + 宿主钉定 + 重光栅**；
            // 仍 >200 → 抛 [IAB_BLANK_CAPTURE]（绝不把极端宽高比图发给下游——OpenAI 400
            // "absolute aspect ratio must be smaller than 200" 的本地前置拦截）。
            suspend fun measurePinInvalidate(): Int {
                val j = withContext(Dispatchers.Main) {
                    renderWebView.awaitEvaluate(IAB_SENTINEL_INJECT_JS)
                        ?.unwrapJsonStringLiteral()
                }
                val o = j?.let { runCatching { JSONObject(it) }.getOrNull() }
                val h = o?.optInt("h", -1) ?: -1
                val injected =
                    (o?.optInt("top", 0) ?: 0) > 0 && (o?.optInt("bottom", 0) ?: 0) > 0
                Logger.d(TAG, "r35 HEIGHT @re-measure: h=$h injected=$injected")
                return if (h > 0 && injected) h else contentW
            }
            suspend fun pinAndInvalidate(h: Int) {
                withContext(Dispatchers.Main) {
                    hostFrame.layoutParams = FrameLayout.LayoutParams(contentW, h)
                    hostFrame.requestLayout()
                    hostFrame.measure(
                        View.MeasureSpec.makeMeasureSpec(contentW, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
                    )
                    hostFrame.layout(0, 0, hostFrame.measuredWidth, hostFrame.measuredHeight)
                    renderWebView.invalidate()
                    hostFrame.invalidate()
                    awaitFrames(IAB_CAPTURE_SETTLE_FRAMES)
                }
            }
            // 钉宿主到 contentW × docHeight（EXACTLY；OPT2：宿主此刻是可见 Dialog 的 contentView，
            // 钉定让 Dialog 真合成器窗口按该几何出帧），让 Chromium 按该几何光栅
            pinAndInvalidate(docHeight)

            var aspectRetried = false
            var bmp: Bitmap? = null
            var attempt = 0
            while (true) {
                // r33 诊断（用户指定）：在**离屏渲染 WebView 自身** JS 上下文读 clientWidth +
                // visibilityState（非活标签）——若 clientWidth=0 或 visibilityState=hidden，
                // 证明离屏 WM 宿主未被视为「可见」，Chromium 合成器不产新栅格 → 全白。
                val diag = renderWebView.awaitEvaluate(
                    "(function(){try{return JSON.stringify({" +
                        "w:document.documentElement?document.documentElement.clientWidth:0," +
                        "v:document.visibilityState});}catch(e){return JSON.stringify({err:String(e)});}})()"
                )?.unwrapJsonStringLiteral()
                Logger.d(TAG, "r33 DIAG headless host @attempt=$attempt: $diag " +
                    "(expected w=$contentW; w=0 or v=hidden ⇒ host not visible to Chromium)")
                val candidate = withContext(Dispatchers.Main) {
                    Bitmap.createBitmap(contentW, docHeight, Bitmap.Config.ARGB_8888).apply {
                        setDensity((contentDpr * 160f).toInt().coerceAtLeast(1))
                        val c = Canvas(this)
                        c.drawColor(Color.WHITE)
                        renderWebView.draw(c)
                    }
                }
                val dual = hasSentinel(candidate, top = true) && hasSentinel(candidate, top = false)
                val hasContent = dual || pdfPageHasContent(candidate)
                if (hasContent) {
                    // r35 守卫③（在**任何**位图返回前）：宽高比 >200 → 一次 4s 长静置 +
                    // 重测/重钉/重光栅；仍 >200 → 抛 [IAB_BLANK_CAPTURE]。
                    val w = candidate.width
                    val h = candidate.height
                    val ar = maxOf(w, h).toDouble() / minOf(w, h).toDouble()
                    if (ar > 200.0) {
                        if (aspectRetried) {
                            // 二次退化（退避阶梯把全白帧刷回来了）→ 直接抛，避免与
                            // 阶梯无限互撞；错误文案与单次重试耗尽时完全一致。
                            runCatching { candidate.recycle() }
                            throw IabError(
                                IAB_BLANK_CAPTURE,
                                "render height was < viewport minimum; DOM did not paint in time " +
                                    "(aspect ratio $ar > 200:1 persisted through the " +
                                    "IAB_CAPTURE_RETRY_WAIT_MS=" + IAB_CAPTURE_RETRY_WAIT_MS + "ms " +
                                    "longer-paint retry) — a degenerate capture is never " +
                                    "returned; reload the page and retry.",
                            )
                        }
                        aspectRetried = true
                        Logger.w(TAG, "r35 ASPECT: capture degenerate ar=$ar (w=$w h=$h, " +
                            "aspect>200) — one retry with IAB_CAPTURE_RETRY_WAIT_MS=" +
                            IAB_CAPTURE_RETRY_WAIT_MS + "ms + re-measure")
                        runCatching { candidate.recycle() }
                        delay(IAB_CAPTURE_RETRY_WAIT_MS)
                        val h2 = measurePinInvalidate()
                        val docHeight2 = h2.coerceAtLeast(floorH)
                        pinAndInvalidate(docHeight2)
                        val candidate2 = withContext(Dispatchers.Main) {
                            Bitmap.createBitmap(contentW, docHeight2, Bitmap.Config.ARGB_8888).apply {
                                setDensity((contentDpr * 160f).toInt().coerceAtLeast(1))
                                val c = Canvas(this)
                                c.drawColor(Color.WHITE)
                                renderWebView.draw(c)
                            }
                        }
                        val w2 = candidate2.width
                        val h2b = candidate2.height
                        val ar2 = maxOf(w2, h2b).toDouble() / minOf(w2, h2b).toDouble()
                        val dual2 = hasSentinel(candidate2, top = true) &&
                            hasSentinel(candidate2, top = false)
                        val hasContent2 = dual2 || pdfPageHasContent(candidate2)
                        Logger.d(TAG, "r35 ASPECT retry: ar=$ar2 (w=$w2 h=$h2b) " +
                            "dual=$dual2 hasContent=$hasContent2")
                        if (ar2 <= 200.0 && hasContent2) {
                            bmp = candidate2
                            break
                        }
                        runCatching { candidate2.recycle() }
                        throw IabError(
                            IAB_BLANK_CAPTURE,
                            "render height was < viewport minimum; DOM did not paint in time " +
                                "(aspect ratio $ar > 200:1, re-measured $ar2 after one " +
                                IAB_CAPTURE_RETRY_WAIT_MS + "ms longer paint wait) — " +
                                "a degenerate capture is never returned; reload the page " +
                                "and retry.",
                        )
                    }
                    bmp = candidate
                    break
                }
                runCatching { candidate.recycle() }
                attempt++
                if (attempt >= IAB_CAPTURE_RETRY_MAX_ATTEMPTS) break
                delay(IAB_CAPTURE_RETRY_BACKOFF_MS[minOf(attempt, IAB_CAPTURE_RETRY_BACKOFF_MS.size - 1)].toLong())
                withContext(Dispatchers.Main) { renderWebView.invalidate() }
                withContext(Dispatchers.Main) { awaitFrames(2) }
            }
            // ── r36 最终位图守卫（Bug 2 修复）：守卫必须落在**要发出去的这张位图**上（下游
            //    即用它编码 PNG），而非仅中间阶梯帧。正常 break 的 `bmp = candidate` 可能只过了
            //    哨兵 OR 的弱判（`dual || pdfPageHasContent`）；退避阶梯耗尽则 `bmp` 为 null。
            //    对用户规格的两分支：
            //    * `bmp != null` 但非白像素 < [IAB_BLANK_CONTENT_RATIO]（全白/近白）→ 一次
            //      [IAB_CAPTURE_RETRY_WAIT_MS]（4s）长静置 + 重测/重钉/重光栅（同 r35 宽高比
            //      重试复用 [measurePinInvalidate]/[pinAndInvalidate]）；仍白 → 抛 [IAB_BLANK_CAPTURE]。
            //    * `bmp == null`（退避阶梯全白用尽）→ 同样一次 4s 长静置重试；仍白 → 抛。
            //    成功（有内容）则返回该位图。绝不把全白图当"成功"返回。
            val guardFinal: Bitmap? = bmp
            if (guardFinal == null || !pdfPageHasContent(guardFinal)) {
                runCatching { guardFinal?.recycle() }
                Logger.w(
                    TAG,
                    "r36 FINAL-GUARD: source bitmap " +
                        (if (guardFinal == null) "was null (blank backoff ladder exhausted)"
                        else "is blank (non-white ratio < ${IAB_BLANK_CONTENT_RATIO})") +
                        " — one longer-paint retry IAB_CAPTURE_RETRY_WAIT_MS=" +
                        IAB_CAPTURE_RETRY_WAIT_MS + "ms + re-measure/re-pin/re-draw",
                )
                delay(IAB_CAPTURE_RETRY_WAIT_MS)
                val gh = measurePinInvalidate().coerceAtLeast(floorH)
                pinAndInvalidate(gh)
                val g2 = withContext(Dispatchers.Main) {
                    Bitmap.createBitmap(contentW, gh, Bitmap.Config.ARGB_8888).apply {
                        setDensity((contentDpr * 160f).toInt().coerceAtLeast(1))
                        val c = Canvas(this)
                        c.drawColor(Color.WHITE)
                        renderWebView.draw(c)
                    }
                }
                val g2has = (hasSentinel(g2, top = true) && hasSentinel(g2, top = false)) ||
                    pdfPageHasContent(g2)
                Logger.d(
                    TAG,
                    "r36 FINAL-GUARD retry: ar=${maxOf(g2.width, g2.height) * 1.0 / minOf(g2.width, g2.height)} " +
                        "hasContent=$g2has",
                )
                if (g2has) {
                    return g2
                }
                runCatching { g2.recycle() }
                throw IabError(
                    IAB_BLANK_CAPTURE,
                    "final raster was blank (>${(1.0 - IAB_BLANK_CONTENT_RATIO)} white) even after " +
                        "one " + IAB_CAPTURE_RETRY_WAIT_MS + "ms longer-paint retry — the page did not " +
                        "paint real content at width=" + contentW + " within the wait. A blank capture " +
                        "is never returned as a successful screenshot; reload the page and retry.",
                )
            }
            bmp?.let { return it }
            // 退避阶梯用尽且守卫重试仍全白（上分支已 throw）——此路径仅在 guardFinal 有内容
            // 但后续逻辑再次判空时不可达；保留显式收敛，绝不静默返回全白。
            throw IabError(
                IAB_BLANK_CAPTURE,
                "Headless off-screen capture produced a blank (all-white) page even after retries " +
                    "with longer paint waits — the page may not have finished rendering (or is genuinely empty). " +
                    "Re-navigate / reload the page, wait for progress=100, then retry; a blank result is " +
                    "never silently returned as a successful screenshot.",
            )
        } finally {
            // ── 8. 销毁宿主 Dialog + 渲染 WebView（主线程；OPT2：dismiss 而非 wm.removeView，
            //         宿主动画禁用 → 即刻消失；先 dismiss 让合成器窗口解除，再摘除/销毁 WebView）──
            runCatching {
                withContext(Dispatchers.Main) {
                    if (dialog.isShowing) dialog.dismiss()
                }
            }
            withContext(Dispatchers.Main) {
                detachFromParent(renderWebView)
                renderWebView.destroy()
            }
        }
    }

    /**
     * r30 双哨兵判定：在 [bitmap] 的顶（[top]=true）或底（[top]=false）[IAB_SENTINEL_BAND_PX] 带
     * 内扫描近 `#010203` 像素（各通道容差 [IAB_SENTINEL_TOLERANCE]）。命中 = 该端的哨兵被
     * 光栅化出来了。只扫左上角 2×2 扩展区（带内 x∈[0,32)、对应 y 带），不做全带扫描——
     * 哨兵注入点固定（left:0 / bottom:0），全带扫描只为防子像素漂移，带 32px 已足够。
     */
    private fun hasSentinel(bitmap: Bitmap, top: Boolean): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return false
        val bandStart = if (top) 0 else (h - IAB_SENTINEL_BAND_PX).coerceAtLeast(0)
        val bandEnd = if (top) IAB_SENTINEL_BAND_PX.coerceAtMost(h) else h
        var y = bandStart
        while (y < bandEnd) {
            var x = 0
            while (x < IAB_SENTINEL_BAND_PX.coerceAtMost(w)) {
                val p = bitmap.getPixel(x, y)
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)
                if (kotlin.math.abs(r - 0x01) <= IAB_SENTINEL_TOLERANCE &&
                    kotlin.math.abs(g - 0x02) <= IAB_SENTINEL_TOLERANCE &&
                    kotlin.math.abs(b - 0x03) <= IAB_SENTINEL_TOLERANCE
                ) return true
                x++
            }
            y++
        }
        return false
    }

    /**
     * r28 2b 引入、r29/r30 保留：空白捕获判定——采样活标签重光栅出的 [bitmap] 在
     * [IAB_BLANK_SAMPLE_STRIDE_PX] 网格上的像素，统计**非白**占比；占比 < [IAB_BLANK_CONTENT_RATIO]
     * （0.5%）即判"全白页"（渲染未完成 / 页面无内容）。返回 true = 有内容（可安全继续出图）；
     * false = 全白（须重试一次；仍白则抛 [IAB_BLANK_CAPTURE]）。全像素扫描 O(W·H) 太重，故走 32px
     * 网格（W×H 每 32 行/列取一点，稳且快）。
     */
    private fun pdfPageHasContent(bitmap: Bitmap?): Boolean {
        if (bitmap == null || bitmap.isRecycled) return false
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return true // 0 尺寸上游已用 IAB_SCREENSHOT_FAILED 兜底
        var total = 0
        var nonWhite = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                total++
                if (bitmap.getPixel(x, y) != IAB_BLANK_WHITE) nonWhite++
                x += IAB_BLANK_SAMPLE_STRIDE_PX
            }
            y += IAB_BLANK_SAMPLE_STRIDE_PX
        }
        if (total == 0) return true
        return (nonWhite.toFloat() / total) >= IAB_BLANK_CONTENT_RATIO
    }

    /**
     * 统一「整页 → PNG」管线（r24 Item 6 起；r33 起单一**可见 Dialog 宿主**（OPT2）渲染，
     * 宽度由 [viewMode] 决定）。
     * `screenshot` 的唯一实现路径（`print_pdf` 已于 r28 废弃，见 [com.niki914.zafiro.chat.agentic.buildin.impl.BrowserBuiltin]）。
     *
     * r33 机制（可见 Dialog 宿主（OPT2），取代 r31 的离屏 overlay）：截屏由 **agent** 消费，
     * 不再绑定用户可见的 IAB 窗口状态（r30 的活标签双路径 `captureAttachedLiveTab`/
     * `captureHiddenIabTab` 已随 r31 彻底删除，宿主形状在 r33 OPT2 中复活为**渲染** WebView 专用
     * 可见 Dialog，活标签本身仍全程不动）——本管线只在下面做三件纯管线工作：
     *
     * 1. 取 [tabForSuspend] 标签与 [SharedImageCodec]（拿不到 → 显式 [IAB_UNAVAILABLE]）。
     * 2. 调 [captureHeadless]（宽度 [viewMode] ∈ {"mobile"→393, "desktop"→1280} 的
     *    **可见 Dialog 宿主**（OPT2）渲染，内部已完成：DOM/storage/cookie 播种 + 该宽度下
     *    重测 scrollHeight + 双哨兵/[pdfPageHasContent] 全白守卫 + 退避阶梯；仍全白 →
     *    内部抛 [IAB_BLANK_CAPTURE]；无 resumed Activity（宿主 Dialog 无处挂）→
     *    内部抛 [IAB_SCREENSHOT_FAILED]「UI 回到前台后重试」）。
     * 3. [encodeIabPng] 把位图编成内存 PNG 字节 → [IngestResult]。
     *
     * **全程硬超时：** 整个管线包在 `withTimeoutOrNull(IAB_CAPTURE_TIMEOUT_MS = 30s)` 内；
     * 超时 / OOM / 内核卡死 → 立即返回显式 [IAB_SCREENSHOT_FAILED]，绝不挂起、绝不回退旧帧。
     *
     * **生命周期（r33 与 r30 同形、比 r31 更严）：** 管线需要 Zafiro 进程活着（Chromium 内核 /
     * [SharedImageCodec] 上下文），且**需要一个 resumed Activity**——宿主 Dialog 必须挂在
     * 真 Activity 上 Chromium 合成器才产帧（OPT2 的代价；~150ms 可见闪烁）。UI 进程在前台
     * 即可（不必 IAB 在屏 / 在开）；进程被杀时 `contextOrUnavailable()` /
     * `SharedImageCodec.get()` 收敛为可读的 [IAB_UNAVAILABLE] 错误；无 resumed Activity 时
     * `ActivityProvider.current()` 返回 null → [IAB_SCREENSHOT_FAILED]。
     *
     * 全程 try/finally 回收中间 [Bitmap]，绝不泄漏；
     * 超时、栅格化出空位图、或 PNG 编码失败 → 抛 [IAB_SCREENSHOT_FAILED]，
     * 绝不产出 0 字节文件，也绝不回退"上一张图"。
     */
    private suspend fun captureFullPagePng(tabId: String?, viewMode: String): IngestResult {
        val codec = SharedImageCodec.get()
            ?: throw IabError(IAB_UNAVAILABLE, "Image codec is not available (context not ready).")
        contextOrUnavailable()
        val tab = tabForSuspend(tabId)

        var sourceBitmap: Bitmap? = null
        // r32：整体捕获（Qwen 裁决 Q3）—— 域内 [IabError]（IAB_BLANK_CAPTURE 等）原样保留；
        //  任何**非** IabError 的异常（未预期的 IllegalStateException / OOM / 平台崩溃）收敛为
        //  IAB_SCREENSHOT_FAILED 并返回 IngestResult.Err，绝不以未捕获异常杀死进程。
        val result = withTimeoutOrNull(IAB_CAPTURE_TIMEOUT_MS) {
            val r: IngestResult = try {
                // 可见 Dialog 宿主渲染（宽度由 [viewMode] 决定）：内部含全白守卫 + 退避阶梯。
                sourceBitmap = captureHeadless(tab, viewMode)

                // ── 栅格侧（IO）：位图 → 内存 PNG → ingest ──
                val out = withContext(Dispatchers.IO) {
                    encodeIabPng(sourceBitmap!!, owned = true)
                }
                    ?: throw IabError(IAB_SCREENSHOT_FAILED, "PNG encode of the rendered page failed.")

                codec.ingestBytes(out, "image/png")
            } catch (e: IabError) {
                // 域内错误（IAB_BLANK_CAPTURE / IAB_SCREENSHOT_FAILED / IAB_UNAVAILABLE …）原样传播
                throw e
            } catch (e: Exception) {
                // 任何未预期的平台异常 → 收敛为显式 IAB_SCREENSHOT_FAILED，绝不杀死进程
                throw IabError(
                    IAB_SCREENSHOT_FAILED,
                    "Headless capture failed unexpectedly: ${e::class.simpleName}: ${e.message}"
                )
            } finally {
                runCatching { sourceBitmap?.recycle() }
            }
            r
        }
        if (result == null) {
            // 硬超时（30s）/ OOM / 内核卡死：显式错误，绝不挂起、绝不回退旧帧（r26 Item 2/3）。
            throw IabError(
                IAB_SCREENSHOT_FAILED,
                "Full-page capture timed out after ${IAB_CAPTURE_TIMEOUT_MS / 1000}s (heavy page or low memory). " +
                    "The page may still be loading or the device is out of memory; wait and retry.",
            )
        }
        return result
    }
    /**
     * [captureFullPagePng] 的编码叶子（r24 Item 6）：把一张已栅格化的 [Bitmap] 编成内存 PNG 字节，
     * 不走落盘（PNG 由 [com.niki914.zafiro.chat.agentic.image.ImageCodec.ingestBytes] 统一进 [SharedImageCodec]）。
     * `owned = true` → 编码后回收该位图；`false` → 调用方仍持有位图、不回收（调用方负责）。
     * 编码失败返回 `null`（由调用方以 [IAB_SCREENSHOT_FAILED] 兜底），绝不产出 0 字节。
     */
    private fun encodeIabPng(bitmap: Bitmap, owned: Boolean): ByteArray? {
        var bytes: ByteArray? = null
        runCatching {
            bytes = ByteArrayOutputStream().use { baos ->
                if (bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)) baos.toByteArray() else null
            }
        }
        // 仅当调用方声明持有且编码成功时才回收：失败/单页分支的位图生命周期由调用方（或拼接
        // 分支 catch 里的 stitched.recycle()）管理，这里不重复回收、不漏回收。
        if (owned && bytes != null) runCatching { bitmap.recycle() }
        return bytes
    }

    // ------------------------------------------------------------- file upload

    /**
     * 文件上传（ZCode IAB 的 file-upload 通道）：
     * 1. 校验 [filePath] 在 App 私有目录（filesDir / cacheDir）内，越界 → [IAB_FILE_FORBIDDEN]；
     * 2. 复制进 cacheDir/iab_upload/（与 [androidx.core.content.FileProvider] 的 iab_upload cache-path 对应）；
     * 3. JS 点击 input[type=file] → onShowFileChooser 回填 Uri → 内核接收后 input.files.length ≥ 1。
     */
    suspend fun fileUpload(tabId: String?, inputSelector: String, filePath: String): IabUploadResult {
        val ctx = contextOrUnavailable()
        val tab = tabForSuspend(tabId)
        val uri = withContext(Dispatchers.IO) {
            val src = File(filePath)
            if (!src.exists() || !src.isFile) throw IabError(IAB_FILE_MISSING, "File not found or not a regular file: $filePath")
            val appDirs = listOf(ctx.filesDir.absolutePath, ctx.cacheDir.absolutePath)
            if (!IabDownloads.isWithinAppDirs(src.absolutePath, appDirs)) {
                throw IabError(IAB_FILE_FORBIDDEN, "IAB file upload only accepts files inside app private storage: ${src.absolutePath}")
            }
            val destDir = File(ctx.cacheDir, "iab_upload").apply { mkdirs() }
            val dest = File(destDir, "up_${System.currentTimeMillis()}_${IabDownloads.sanitizeName(src.name)}")
            src.copyTo(dest, overwrite = true)
            runCatching { FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", dest) }
                .getOrElse { Uri.fromFile(dest) }
        }
        withContext(Dispatchers.Main) { tab.pendingUpload = arrayOf(uri) }
        val clicked = evaluate(tab.id, IabJs.uploadClick(inputSelector))
        when {
            clicked == null -> return IabUploadResult("CHOOSER_SKIPPED", "JS engine error when clicking the file input; check the selector.")
            clicked.contains("\"ok\":false") -> return IabUploadResult("CHOOSER_SKIPPED", clicked)
        }
        withContext(Dispatchers.Default) { delay(2500) }
        val countJson = evaluate(tab.id, IabJs.uploadFileCount(inputSelector)) ?: "{}"
        val files = runCatching {
            Json.parseToJsonElement(countJson).jsonObject["files"]?.jsonPrimitive?.content?.toIntOrNull()
        }.getOrNull() ?: -1
        return if (files >= 1) {
            IabUploadResult("ACCEPTED", "WebView accepted the file (input.files.length=$files).")
        } else {
            withContext(Dispatchers.Main) { tab.pendingUpload = null }
            IabUploadResult("CHOOSER_SKIPPED", "The native file chooser did not accept a file (selector may not be a visible input[type=file]). Raw: $countJson")
        }
    }

    // ------------------------------------------------------------- downloads

    /**
     * r22c Bug3：从标签当前 URL 抓取整页（用户主动"下载当前页"）。
     *
     * 与 [WebView] 的 setDownloadListener 下载钩子（资源下载，触发于页面内的
     * download 属性 / content-disposition 响应）不同——这是主动把标签当前 url
     * 走一次后台 HttpURLConnection 落盘，复用 [handleDownloadStart] 的同一套
     * 环形缓冲（SAVING→DONE/FAILED）+ 文件名 + 超时逻辑，保证"下载历史"里
     * 立刻能看见这条新记录。
     *
     * @param tabId null = 激活标签。
     * @return 刚登记进环形缓冲的那条 [IabDownload]（初始 status = "SAVING"，
     *   后台落盘成功后同实例流转为 "DONE"/"FAILED"，[downloads] 与 UI 读的是同一引用）。
     */
    suspend fun downloadCurrentPage(tabId: String? = null): IabDownload {
        contextOrUnavailable()
        return withContext(Dispatchers.Main) {
            val tab = tabForBlocking(tabId)
            // 标签自身的 url（navigate 时已写入）；回落到 webView.url（导航中可能为 null）。
            val url = tab.url.ifEmpty { tab.webView.url ?: "" }
            require(url.isNotEmpty()) { "No URL to download on tab ${tab.id}" }
            // handleDownloadStart：void 注册（downloads.add 同实例进环形缓冲）+ 后台 launch
            // 落盘。注册完成后头部即刚登记的那条；UI 读 [downloads] 时拿到同一引用，
            // 后台流转 DONE/FAILED 后同实例被看到，不会丢。
            handleDownloadStart(
                tab = tab,
                url = url,
                userAgent = defaultUserAgent,
                contentDisposition = null,
                mimeType = null,
            )
            downloads.snapshot(1).firstOrNull()
                ?: throw IabError(IAB_DOWNLOAD_FAILED, "Download registration failed for: $url")
        }
    }

    /** 主线程回调（由 WebView.setDownloadListener 触发）：登记 + 后台落盘。 */
    fun handleDownloadStart(tab: IabTab, url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        val ctx = context
        if (ctx == null) return
        val baseName = "${System.currentTimeMillis()}_${IabDownloads.suggestedName(url, contentDisposition, mimeType)}"
        // r24 Item 5：设了 SAF 树则优先写进树内；任何一步失败都静默回退到可写文件路径，
        // 绝不因目标存储问题中断下载（flawless + seamless 要求）。
        val treeUri = downloadDirTree
        val defaultDir = File(ctx.filesDir, "downloads")
        val dir = downloadDir?.let { requested ->
            if (requested.exists() || requested.mkdirs()) requested else defaultDir
        } ?: defaultDir

        val entry = IabDownload(
            id = "dl${System.currentTimeMillis() % 100000}_${seq}",
            url = url,
            path = "",
            sizeBytes = 0,
            status = "SAVING",
            startedAtMs = System.currentTimeMillis(),
        )
        downloads.add(entry)
        downloadScope.launch {
            try {
                val conn = URL(url).openConnection() as? HttpURLConnection
                    ?: throw IabError(IAB_DOWNLOAD_FAILED, "Unsupported protocol for: $url")
                conn.setRequestProperty("User-Agent", userAgent ?: defaultUserAgent ?: "Mozilla/5.0")
                conn.connectTimeout = 30_000
                conn.readTimeout = 120_000
                conn.instanceFollowRedirects = true
                if (treeUri != null && writeIntoTree(ctx, treeUri, baseName, conn, entry)) {
                    return@launch
                }
                // Fallback: local filesystem path.
                dir.mkdirs()
                val target = File(dir, baseName)
                entry.path = target.absolutePath
                conn.inputStream.use { input ->
                    target.outputStream().use { out -> input.copyTo(out, 128 * 1024) }
                }
                entry.sizeBytes = target.length()
                entry.status = "DONE"
            } catch (e: Exception) {
                entry.status = "FAILED"
                entry.error = e.message ?: e::class.simpleName
                runCatching { File(entry.path).delete() }
                Logger.w(TAG, "IAB download failed: $url (${e.message})")
            }
        }
    }

    /**
     * r24 Item 5：把 [conn] 的响应流写入 SAF 树 [treeUri]（OpenDocumentTree 选中的文件夹）。
     * 通过 [DocumentFile.createFile] 在树内创建子文件，名称冲突（返回 null）时追加序号重试，
     * 再经 `ContentResolver.openOutputStream(childUri)` 落盘。成功返回 true；任一步失败
     * 返回 false（调用方回退到本地文件路径，绝不因目标存储问题中断下载）。
     */
    private fun writeIntoTree(
        ctx: Context,
        treeUri: Uri,
        baseName: String,
        conn: HttpURLConnection,
        entry: IabDownload,
    ): Boolean {
        val tree = runCatching { DocumentFile.fromTreeUri(ctx, treeUri) }.getOrNull() ?: return false
        if (!tree.isDirectory) return false
        val resolver = ctx.contentResolver
        var created: DocumentFile? = null
        for (attempt in 0 until 3) {
            val candidate = if (attempt == 0) baseName else {
                val dot = baseName.lastIndexOf('.')
                if (dot > 0) baseName.substring(0, dot) + "_$attempt" + baseName.substring(dot)
                else baseName + "_$attempt"
            }
            val createdFile = runCatching { tree.createFile("*/*", candidate) }.getOrNull()
            if (createdFile != null) { created = createdFile; break }
        }
        val target = created ?: return false
        val targetUri = target.uri
        entry.path = targetUri.toString()
        conn.inputStream.use { input ->
            val out = resolver.openOutputStream(targetUri)
                ?: throw IabError(IAB_DOWNLOAD_FAILED, "Cannot open output stream into chosen folder")
            out.use { stream ->
                entry.sizeBytes = input.copyTo(stream, 128 * 1024)
            }
        }
        entry.status = "DONE"
        return true
    }

    suspend fun downloads(limit: Int = 20): List<IabDownload> = downloads.snapshot(limit)

    /** r23 Item 4：清空下载日志环形缓冲（仅清记录，不删除已落盘文件）。 */
    fun clearDownloads() {
        downloads.clear()
    }

    /**
     * 取指定标签（null = 激活标签）的网络请求环形日志，序列化为 JSON 数组字符串。
     * 供 browser 工具的 network action 直接返回（不经 JS，ring 在 Kotlin 侧）。
     */
    suspend fun networkOf(tabId: String?, limit: Int = 50): String {
        contextOrUnavailable()
        return withContext(Dispatchers.Main) {
            val tab = tabForBlocking(tabId)
            val list = tab.networkLog.snapshot(limit)
            StringBuilder("[")
                .append(
                    list.joinToString(",") { e ->
                        buildString {
                            append("{\"at\":").append(e.atMs)
                            append(",\"method\":").append(jsonStr(e.method))
                            append(",\"url\":").append(jsonStr(e.url))
                            e.mime?.let { append(",\"mime\":").append(jsonStr(it)) }
                            e.note?.let { append(",\"note\":").append(jsonStr(it)) }
                            append("}")
                        }
                    }
                )
                .append("]")
                .toString()
        }
    }

    /**
     * 取指定标签（null = 激活标签）的原生控制台环形日志（[IabTab.consoleLog]，由
     * [IabWebChromeClient.onConsoleMessage] 写入），序列化为 JSON 数组字符串。
     * 这是 r17/r18「设置 IAB 控制台有数据」的原始通道；与 JS hook 的 [IabJs.consoleRead]
     * 相互独立，[com.niki914.zafiro.chat.agentic.buildin.impl.BrowserBuiltin] 的 console
     * 动作会把两路合并后返回。
     */
    suspend fun consoleOf(tabId: String?, limit: Int = 50): String {
        contextOrUnavailable()
        return withContext(Dispatchers.Main) {
            val tab = tabForBlocking(tabId)
            val list = tab.consoleLog.snapshot(limit)
            StringBuilder("[")
                .append(list.joinToString(",") { e -> jsonStr(e) })
                .append("]")
                .toString()
        }
    }

    /** 最小 JSON 字符串转义（ring 条目均为内部可控字段，转义引号/反斜杠即可）。 */
    private fun jsonStr(s: String): String {
        val b = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> b.append("\\\"")
                '\\' -> b.append("\\\\")
                '\n' -> b.append("\\n")
                '\r' -> b.append("\\r")
                '\t' -> b.append("\\t")
                else -> if (c.code < 0x20) b.append(String.format("\\u%04x", c.code)) else b.append(c)
            }
        }
        return b.append('"').toString()
    }

    // ------------------------------------------------------------- cookies / clipboard

    suspend fun cookiesGet(url: String): String = withContext(Dispatchers.IO) {
        contextOrUnavailable()
        CookieManager.getInstance().getCookie(url) ?: ""
    }

    suspend fun cookiesSet(url: String, cookies: String): String = withContext(Dispatchers.IO) {
        contextOrUnavailable()
        CookieManager.getInstance().apply {
            setCookie(url, cookies)
            flush()
        }
        "cookie set for $url"
    }

    suspend fun cookiesClear(url: String): String = withContext(Dispatchers.IO) {
        contextOrUnavailable()
        CookieManager.getInstance().apply {
            setCookie(url, "zafiro_clear=1; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT")
            flush()
        }
        "cookies cleared for $url"
    }

    suspend fun clipboardRead(): String {
        contextOrUnavailable()
        return withContext(Dispatchers.Main) {
            val ctx = contextOrUnavailable()
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager)
                ?.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()?.trim().orEmpty()
        }
    }

    suspend fun clipboardWrite(text: String) {
        contextOrUnavailable()
        withContext(Dispatchers.Main) {
            val ctx = contextOrUnavailable()
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager)
                ?.setPrimaryClip(android.content.ClipData.newPlainText("zafiro", text))
        }
    }

    // ------------------------------------------------------------- settings

    suspend fun setUserAgent(userAgent: String?): String {
        contextOrUnavailable()
        return withContext(Dispatchers.Main) {
            val target = userAgent?.takeIf { it.isNotBlank() } ?: defaultUserAgent
            customUserAgent = target
            synchronized(lock) {
                tabs.forEach { runCatching { it.webView.settings.userAgentString = target } }
            }
            "user agent set"
        }
    }

    /** 页面缩放：现代 SDK 的 WebSettings 已移除 setZoomScale，改用最接近的 setTextZoom（50–300%）。 */
    suspend fun setZoom(scale: Float): String {
        contextOrUnavailable()
        return withContext(Dispatchers.Main) {
            val percent = (scale.coerceIn(0.25f, 3f) * 100).toInt().coerceIn(50, 300)
            synchronized(lock) {
                tabs.forEach { runCatching { it.webView.settings.setTextZoom(percent) } }
            }
            "text zoom set to $percent%"
        }
    }

    suspend fun setDesktopMode(enabled: Boolean): String {
        contextOrUnavailable()
        return withContext(Dispatchers.Main) {
            desktopMode = enabled
            val ua = if (enabled) DESKTOP_UA else defaultUserAgent
            customUserAgent = ua
            synchronized(lock) {
                tabs.forEach { t ->
                    runCatching { t.webView.settings.userAgentString = ua }
                    runCatching { t.webView.evaluateJavascript(IabJs.desktopViewport(enabled)) { } }
                }
            }
            "desktop mode ${if (enabled) "on" else "off"}"
        }
    }

    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

    // ------------------------------------------------------------- local preview

    /** 打开本地文件（App 私有目录内）/ URL / HTML 字符串（data:text/html;base64）。 */
    suspend fun openLocal(tabId: String?, file: String?, html: String?): IabTabInfo {
        val ctx = contextOrUnavailable()
        return withContext(Dispatchers.Main) {
            val target: String = when {
                html != null && html.isNotBlank() ->
                    "data:text/html;base64," + android.util.Base64.encodeToString(html.toByteArray(), android.util.Base64.NO_WRAP)

                file != null && file.isNotBlank() -> when {
                    file.startsWith("http://") || file.startsWith("https://") ||
                        file.startsWith("file://") || file.startsWith("data:") -> file

                    else -> {
                        val f = File(file).let { if (it.isAbsolute) it else File(ctx.filesDir, file) }
                        if (!f.exists()) throw IabError(IAB_FILE_MISSING, "Local file not found: ${f.absolutePath}")
                        val appDirs = listOf(ctx.filesDir.absolutePath, ctx.cacheDir.absolutePath)
                        if (!IabDownloads.isWithinAppDirs(f.absolutePath, appDirs)) {
                            throw IabError(IAB_FILE_FORBIDDEN, "IAB local preview only opens files inside app storage: ${f.absolutePath}")
                        }
                        "file://${f.absolutePath}"
                    }
                }

                else -> throw IabError(IAB_BAD_URL, "Provide 'file' or 'html'.")
            }
            val tab = tabForBlocking(tabId)
            loadUrlBlocking(tab, target)
            tab.snapshot()
        }
    }

    // ------------------------------------------------------------- UI 附着（AndroidView 宿主）

    /**
     * 主线程：把指定（或激活）标签的 WebView 挂进宿主容器（标签状态保留，仅换父容器）。
     *
     * 幂等 + 防闪烁：WebView 已挂在同一容器内时直接 no-op——任何「先 removeView 再 addView」
     * 都会让 Chromium 重绘一帧白屏；UI 侧（设置页 / 聊天窗格）的 DisposableEffect 会因
     * activeTabId / progress 变化反复重入本方法，无守卫时窗口表现为「忽闪忽灭」。
     * 换宿主容器（设置页 ↔ 聊天窗格）时正常 remove-then-add，实例与标签状态不丢。
     */
    @MainThread
    fun attachUi(holder: ViewGroup, tabId: String?) {
        val tab = tabForBlocking(tabId)
        attachUiInternal(holder, tab)
    }

    /**
     * 主线程：宿主容器销毁时解绑该容器内的 WebView（WebView 本体与标签状态保留）。
     * 只清本容器自己的子 View，不误伤其他容器（聊天窗格与设置页互不牵连）。
     */
    @MainThread
    fun detachUi(holder: ViewGroup) {
        for (i in holder.childCount - 1 downTo 0) {
            holder.removeViewAt(i)
        }
    }

    private fun attachUiInternal(holder: ViewGroup, tab: IabTab) {
        // 幂等 + 防闪烁：WebView 已挂在同一容器内时直接 no-op。以 WebView 的实际父容器为准
        // （每个宿主容器 addView 的唯一子 View 就是该标签的 WebView），不再依赖 IabTab 上的
        // 宿主指针字段。任何「先 removeView 再 addView」都会让 Chromium 重绘一帧白屏。
        if ((tab.webView.parent as? ViewGroup) === holder) return
        val webView = tab.webView
        (webView.parent as? ViewGroup)?.removeView(webView)
        holder.addView(webView, ViewGroup.LayoutParams(-1, -1))
    }

    private fun detachFromParent(webView: WebView) {
        (webView.parent as? ViewGroup)?.removeView(webView)
    }

    // ------------------------------------------------------------- clients

    private class IabWebViewClient(private val manager: IabManager) : WebViewClient() {

        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            super.onPageStarted(view, url, favicon)
            val tab = manager.tabForWebView(view) ?: return
            tab.url = url.orEmpty()
            tab.title = ""
            tab.progress = 0
            tab.networkLog.add(IabNetworkEntry(System.currentTimeMillis(), "PAGE", url.orEmpty(), null, "started"))
            manager.injectConsoleHook(tab)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            super.onPageFinished(view, url)
            val tab = manager.tabForWebView(view) ?: return
            tab.url = view.url.orEmpty()
            tab.title = view.title.orEmpty()
            tab.progress = 100
            tab.networkLog.add(IabNetworkEntry(System.currentTimeMillis(), "PAGE", url.orEmpty(), null, "finished"))
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            // 只记录、不拦截（返回 null = 正常加载）
            manager.tabForWebView(view)?.networkLog?.add(
                IabNetworkEntry(
                    atMs = System.currentTimeMillis(),
                    method = request.method,
                    url = request.url.toString(),
                    mime = request.requestHeaders?.get("Content-Type"),
                    note = null,
                )
            )
            return null
        }

        override fun onReceivedSslError(
            view: WebView,
            handler: android.webkit.SslErrorHandler,
            error: android.net.http.SslError,
        ) {
            manager.tabForWebView(view)?.networkLog?.add(
                IabNetworkEntry(System.currentTimeMillis(), "SSL", error.url.orEmpty(), null, "ssl error: ${error.primaryError}")
            )
            handler.cancel() // 安全默认：证书错误不放行
        }
    }

    private class IabWebChromeClient(private val manager: IabManager) : WebChromeClient() {

        // 标题回调在 WebChromeClient（现代 SDK 的 WebViewClient 已无 onReceivedTitle）
        override fun onReceivedTitle(view: WebView, title: String?) {
            manager.tabForWebView(view)?.title = title.orEmpty()
        }

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            manager.tabForWebView(view)?.progress = newProgress
        }

        /**
         * r21 Item 2（F3b）：Android 原生控制台通道。页面的 console.* 调用（含 JS hook
         * 安装前/SPA 重绑后丢失的那部分）会通过这里落到 [IabTab.consoleLog] 环形缓冲——
         * 这是 r17/r18「设置 IAB 控制台有数据」的原始通道，此前本类缺失该覆写导致
         * consoleLog 成为死环。返回 true 抑制 WebChromeClient 的默认实现。
         */
        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            // ConsoleMessage 不携带 WebView 实例（javap 证明：只有 lineNumber/message/messageLevel/sourceId），
            // 无法反查到具体标签；控制台读取面（设置 IAB 面板）始终是 active tab，故写入 active tab 的环形缓冲。
            val tab = manager.activeTabForWebView() ?: return true
            val level = when (message.messageLevel()) {
                ConsoleMessage.MessageLevel.WARNING -> "[warn]"
                ConsoleMessage.MessageLevel.ERROR -> "[error]"
                else -> "[log]"
            }
            tab.consoleLog.add("$level ${message.message()}")
            return true
        }

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams?,
        ): Boolean {
            val tab = manager.tabForWebView(webView) ?: return false
            val armed = tab.pendingUpload ?: return false
            tab.pendingUpload = null
            runCatching { filePathCallback.onReceiveValue(armed) }
            tab.networkLog.add(IabNetworkEntry(System.currentTimeMillis(), "UPLOAD", armed.joinToString(",") { it.toString() }, null, "file upload"))
            return true
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            if (manager.autoGrantMedia) {
                runCatching { request.grant(request.resources) }
                manager.activeTabForWebView()?.networkLog?.add(
                    IabNetworkEntry(System.currentTimeMillis(), "PERM", request.resources.joinToString(","), null, "granted")
                )
            } else {
                request.deny()
            }
        }
    }

    // ------------------------------------------------------------- 给 client 用的辅助（主线程）

    /** 按 WebView 实例反查标签（主线程调用）。 */
    internal fun tabForWebView(view: WebView): IabTab? = synchronized(lock) {
        tabs.firstOrNull { it.webView === view }
    }

    internal fun activeTabForWebView(): IabTab? = synchronized(lock) {
        tabs.firstOrNull { it.active }
    }

    /** 全量销毁（进程退出 / 测试重置用；一般不需要调用）。 */
    @MainThread
    fun destroyAll() {
        synchronized(lock) {
            tabs.forEach {
                detachFromParent(it.webView); it.webView.destroy()
            }
            tabs.clear()
        }
        seq = 0
    }
}
