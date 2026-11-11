package com.niki914.zafiro.chat.agentic.iab

import android.webkit.WebView

/**
 * IAB 标签页记录 + 环形日志缓冲。
 *
 * 一个 [IabTab] 独占一个系统 WebView（Chromium 内核）；标签上限见
 * [IabManager.MAX_TABS]，超限时按 LRU 驱逐非激活标签（WebView destroy，cookie 仍留在
 * WebView 数据目录中，重新 navigate 即恢复会话态）。
 */
class IabTab(
    val id: String,
    val webView: WebView,
    val createdAtMs: Long,
) {
    var url: String = ""
    var title: String = ""
    var progress: Int = 0
    var active: Boolean = false
    var lastActiveMs: Long = createdAtMs
    /** 文件上传等待槽：JS click input[type=file] 后 onShowFileChooser 回填的 Uri。 */
    var pendingUpload: Array<android.net.Uri>? = null

    val consoleLog = IabRingBuffer<String>(500)
    val networkLog = IabRingBuffer<IabNetworkEntry>(200)

    fun touch() {
        lastActiveMs = System.currentTimeMillis()
    }

    /** 纯数据快照（供工具结果 / 设置页），不含 WebView 引用。 */
    fun snapshot(): IabTabInfo = IabTabInfo(
        id = id,
        url = url,
        title = title,
        progress = progress,
        active = active,
        createdAtMs = createdAtMs,
        lastActiveMs = lastActiveMs,
    )
}

/** 验证墙（CAPTCHA / 人机校验）探测结果（[IabManager.captchaProbe] 的 UI/工具侧契约）。 */
sealed interface IabCaptchaProbe {
    /** 未命中（无验证墙 / 冷启动 / 引擎异常）。 */
    data object None : IabCaptchaProbe

    /**
     * 命中：页面正显示一个人机校验。
     * @property kind 来源类别（recaptcha / hcaptcha / geetest / turnstile / keyword / iframe_captcha / ""）
     * @property hints 供用户阅读的行动提示（"需要在页面里拖动滑块"等）
     * @property selector 可点击的验证控件选择器（best-effort，可能为空）
     * @property inputCount 页面里可见的验证输入框数量
     * @property inputLabel 首个验证输入框的 label / id（便于引导用户）
     */
    data class Detected(
        val kind: String,
        val hints: List<String>,
        val selector: String,
        val inputCount: Int,
        val inputLabel: String,
    ) : IabCaptchaProbe
}

data class IabTabInfo(
    val id: String,
    val url: String,
    val title: String,
    val progress: Int,
    val active: Boolean,
    val createdAtMs: Long,
    val lastActiveMs: Long,
)

/** 网络请求级日志（请求发生时记录；页面级错误折进 [note]）。 */
data class IabNetworkEntry(
    val atMs: Long,
    val method: String,
    val url: String,
    val mime: String?,
    val note: String?,
)

/** 下载捕获记录（WebView.setDownloadListener → 后台落盘到 filesDir/downloads/）。 */
data class IabDownload(
    val id: String,
    val url: String,
    /** 落盘目标路径（下载流转中回填；SAVING 态先占位 ""，写前赋值，见 [IabManager.handleDownloadStart]）。 */
    var path: String,
    var sizeBytes: Long,
    var status: String, // SAVING / DONE / FAILED（落盘流转中更新）
    val startedAtMs: Long,
    var error: String? = null,
)

/**
 * 线程安全环形缓冲（容量固定，满则丢最旧）。
 * 纯 JVM 类型——日志/网络/下载三个 ring 都是它，便于单测。
 */
class IabRingBuffer<T>(val capacity: Int) {
    init {
        require(capacity > 0) { "capacity must be > 0" }
    }

    private val items = ArrayDeque<T>()
    private val lock = Any()

    fun add(item: T) {
        synchronized(lock) {
            while (items.size >= capacity) items.removeFirst()
            items.addLast(item)
        }
    }

    /** 最近 [limit] 条快照（limit 为 null 取满容量）。 */
    fun snapshot(limit: Int? = null): List<T> = synchronized(lock) {
        val take = limit?.coerceIn(0, capacity) ?: capacity
        items.takeLast(take).toList()
    }

    val size: Int
        get() = synchronized(lock) { items.size }

    /** r23 Item 4：清空环形缓冲（控制台/下载日志面板的 Clear 按钮用）。 */
    fun clear() {
        synchronized(lock) { items.clear() }
    }
}
