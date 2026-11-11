package com.niki914.zafiro.chat.agentic.buildin.impl

import com.niki914.zafiro.chat.agentic.buildin.BuiltinTool
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolResult
import com.niki914.zafiro.chat.agentic.iab.IabJs
import com.niki914.zafiro.chat.agentic.iab.IabManager
import com.niki914.zafiro.chat.agentic.iab.IabManager.IabError
import com.niki914.zafiro.chat.agentic.image.IngestResult
import com.niki914.zafiro.chat.agentic.toImageToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * browser 内置工具：进程内 In-App Browser（系统 WebView / Chromium 内核）的统一操作面。
 *
 * 一个工具 + action 判别（对齐 ZCode IAB 的能力面）：
 * 导航（navigate/back/forward/reload/stop/new_tab/close_tab/switch_tab/tabs）、
 * 读取（evaluate/page_text/page_html/query_selector/state/console/network）、
 * 交互（click/click_text/click_at/fill/set_checked/select_option/keypress/scroll/submit_form）、
 * 文件（file_upload/downloads/open_local；screenshot 走统一无头渲染管线，把活标签当前
 * 整页 DOM + 其 cookies/localStorage/sessionStorage 快照注入一个独立的渲染器栅格成 PNG，
 * 以固定视口宽度（view_mode：mobile=393px / desktop=1280px，默认 mobile）+ 2× DPI + 整页高度出图，
 * 渲染器托管在 resumed Activity 上的真可见 Dialog（r33 OPT2：宿主需真可见 Chromium 才产帧，
 * ~150ms 可见闪烁，无交互 / 不抢焦点；IAB 窗口开合 / 收起 / 关闭均无关，不重新导航 URL），
 * 经 IngestResult 返回图像引用；只有无 resumed Activity（宿主 Dialog 无处挂）/ 可见性门禁不通过 /
 * 位图编码真失败、或输出为空白页（渲染未完成）时返回 IAB_SCREENSHOT_FAILED / IAB_BLANK_CAPTURE）、
 * 环境（screenshot（无头整页 DOM 快照栅格化，固定宽度 view_mode mobile=393 / desktop=1280 + 2× DPI，
 * IAB 关闭 / 收起 / 从未打开都能出图；渲染器宿主的 Dialog 真可见 ~150ms，非离屏）、cookie get/set/clear、
 * clipboard read/write、set_ua、set_zoom（文本缩放）、emulate_desktop、set_media_grant）。
 *
 * 依赖装配：[IabManager] 为进程单例（上下文来自 App 启动的 ContextProvider），本工具零参构造，
 * 与 ScreenshotBuiltin/ViewImageBuiltin 同一模式。冷启动未就绪时返回 IAB_UNAVAILABLE（不挂起、不抛）。
 */
class BrowserBuiltin : BuiltinTool() {
    override val name: String = "browser"
    override val description: String = """
In-app browser (IAB): browse, read, interact with and operate web pages inside Zafiro without leaving the app.
Actions:
- navigate {url, tab?}, new_tab {url?}, close_tab {tab}, switch_tab {tab}, tabs, back/forward/reload/stop {tab?}
- evaluate {expression, tab?, await_promise?}  (arbitrary JS; result is JSON-normalized; DOM nodes become descriptors)
- page_text {tab?, limit?}, page_html {tab?, limit?}, query_selector {selector, limit?, tab?}
- click {selector, tab?}, click_text {text, tab?}, click_at {x, y, tab?}
- drag {x, y, to_x, to_y, steps?, tab?}, swipe {direction, x, y, distance_px, steps?, tab?}
  (untrusted pointer-stream gestures: some web apps/CAPTCHA widgets ignore them — see IAB_POINTER_UNTRUSTED hint)
- fill {selector, value, tab?}, set_checked {selector, checked, tab?}, select_option {selector, value, tab?}
- keypress {keys, tab?}  ("a", "Enter", "Tab"; comma-separated for sequences)
- scroll {x, y, tab?}, submit_form {selector, tab?}
- file_upload {selector, file, tab?}  (file must be inside app storage; e.g. a path from py_download_file)
- downloads {limit?}  (files captured from in-page download links; paths are readable by view_image / execute_python)
- open_local {file?, html?, tab?}  (preview a local file or raw HTML in the browser)
- screenshot {tab?, view_mode?}  (rasterizes the FULL current in-app browser page of the tab headlessly
   — a fresh renderer seeded from the live DOM + the tab's cookies / localStorage /
   sessionStorage, so IAB open, closed, or never opened all produce a page image, never re-navigates
   the URL; the renderer is hosted in a genuinely-visible Dialog on the app's Activity for the ~150ms
   of the raster (visible so Chromium actually paints; non-interactive, non-focusable, no overlay
   permission needed); view_mode picks a FIXED layout width: "mobile" (default, 393px) or "desktop" (1280px),
   re-laid-out and re-rasterized at that width with the matching user-agent at 2x DPI, full document
   height; returns an image reference)
- state {tab?}, verify_check {tab?}, console {limit?, tab?}, network {limit?, tab?}
- cookies_get {url}, cookies_set {url, cookies}, cookies_clear {url}
- clipboard_read, clipboard_write {text}
- set_ua {user_agent?}, set_zoom {zoom} (text zoom), emulate_desktop {enabled}, set_media_grant {enabled}
- verify_check {tab?}  (detect whether the page is showing a CAPTCHA / human-verification gate;
   returns {detected, kind, hints, selector, inputCount}; call it when state shows progress=100
   but the content looks blocked)
Tips: call tabs/navigate first, then poll state until progress=100 before reading content;
for a clean wide-page screenshot use screenshot {view_mode: "desktop"} (renders at 1280px instead of
the default 393px mobile layout); when a page looks like a mobile layout, emulate_desktop {enabled: true}
then reload to change the LIVE tab's user-agent/viewport (emulate_desktop does NOT change a screenshot's
render width — view_mode does that).
Verification / recheck strategy (do NOT wait limitlessly): if progress stays <100 for a few polls,
call state and check the url/title; if a gate or "verify" page is suspected, call verify_check.
- If verify_check.detected=true: the page needs a HUMAN to complete a CAPTCHA — you cannot solve
  it yourself. Say plainly that manual verification is required and STOP re-checking; re-check at
  most once, no sooner than 20-30s later, and only if the user says they have completed it.
- If progress stalls with no captcha: bounded retry — reload or re-navigate the SAME url up to 2
  extra times, spacing checks ~15s apart; if it still stalls, report the stall (with url + title)
  and stop. Never loop forever.
    """.trimIndent()
    override val defaultEnabled: Boolean = true
    override val inputSchemaJson: String? = SCHEMA

    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
        val args = try {
            Json.parseToJsonElement(request.argumentsJson).jsonObject
        } catch (e: Exception) {
            return BuiltinToolResult.failure(
                code = "INVALID_ARGUMENTS",
                message = "Failed to parse arguments JSON: ${e.message}",
            )
        }
        val action = (args["action"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (action.isEmpty()) {
            return BuiltinToolResult.failure(
                code = "MISSING_ACTION",
                message = "Field 'action' is required.",
                fieldErrors = mapOf("action" to "required, one of: ${ACTIONS.joinToString(", ")}"),
            )
        }
        if (action !in ACTIONS) {
            return BuiltinToolResult.failure(
                code = "UNSUPPORTED_ACTION",
                message = "Unknown action '$action'.",
                fieldErrors = mapOf("action" to "must be one of: ${ACTIONS.joinToString(", ")}"),
            )
        }
        // 冷启动未就绪时，IabManager 的各入口经 contextOrUnavailable 抛 IabError(IAB_UNAVAILABLE)，
        // 在这里统一映射为工具失败结果（带 hint），模型可安全重试。
        try {
            return dispatch(action, args)
        } catch (e: IabError) {
            return BuiltinToolResult.failure(code = e.code, message = e.message, hint = HINTS[e.code] ?: "")
        }
    }

    private suspend fun dispatch(action: String, a: JsonObject): BuiltinToolResult {
        fun s(key: String): String? = (a[key] as? JsonPrimitive)?.contentOrNull

        val tab = s("tab")
        val url = s("url")
        val expression = s("expression")
        val selector = s("selector")
        val text = s("text")
        val value = s("value")
        val file = s("file")
        val keys = s("keys")
        val html = s("html")
        val cookies = s("cookies")
        val userAgent = s("user_agent")
        when (action) {
            "navigate" -> {
                val u = url ?: throw IabError("MISSING_FIELD", "Field 'url' is required for navigate.")
                val info = IabManager.navigate(tab, u)
                return ok("Navigated to ${info.url}", tabData(info))
            }

            "new_tab" -> {
                val info = IabManager.newTab(url)
                return ok("Opened tab ${info.id}", tabData(info))
            }

            "close_tab" -> {
                val t = tab ?: throw IabError("MISSING_FIELD", "Field 'tab' is required for close_tab.")
                IabManager.closeTab(t)
                return ok("Closed tab $t", JsonObject(emptyMap()))
            }

            "switch_tab" -> {
                val t = tab ?: throw IabError("MISSING_FIELD", "Field 'tab' is required for switch_tab.")
                return ok("Switched to tab $t", tabData(IabManager.activateTab(t)))
            }

            "tabs" -> {
                val list = IabManager.tabs()
                val arr = buildJsonArray { list.forEach { add(obj(it)) } }
                return ok("Tabs: ${list.map { it.id }}", JsonObject(mapOf("tabs" to arr)))
            }

            "back" -> return ok("Went back", tabData(IabManager.back(tab)))
            "forward" -> return ok("Went forward", tabData(IabManager.forward(tab)))
            "reload" -> return ok("Reloaded", tabData(IabManager.reload(tab)))
            "stop" -> return ok("Stopped loading", tabData(IabManager.stop(tab)))

            "evaluate" -> {
                val expr = expression ?: throw IabError("MISSING_FIELD", "Field 'expression' is required for evaluate.")
                val raw = IabManager.evaluate(tab, IabJs.evaluateEnvelope(expr, awaitPromiseOf(a))) ?: run {
                    throw IabError(IabManager.IAB_JS_ENGINE_ERROR, "JS evaluation returned no value (engine error or tab closed).")
                }
                return ok("Evaluated.", JsonObject(mapOf("text" to JsonPrimitive(raw))))
            }

            "page_text" -> {
                val limit = a["limit"]?.jsonPrimitiveOrNull()?.content?.toIntOrNull() ?: 20_000
                val raw = IabManager.evaluate(tab, IabJs.pageText(limit)) ?: "null"
                return ok("Read page text.", JsonObject(mapOf("text" to JsonPrimitive(raw))))
            }

            "page_html" -> {
                val limit = a["limit"]?.jsonPrimitiveOrNull()?.content?.toIntOrNull() ?: 50_000
                val raw = IabManager.evaluate(tab, IabJs.pageHtml(limit)) ?: "null"
                return ok("Read page HTML.", JsonObject(mapOf("text" to JsonPrimitive(raw))))
            }

            "query_selector" -> {
                val sel = selector ?: throw IabError("MISSING_FIELD", "Field 'selector' is required for query_selector.")
                val limit = a["limit"]?.jsonPrimitiveOrNull()?.content?.toIntOrNull() ?: 20
                val raw = IabManager.evaluate(tab, IabJs.querySelector(sel, limit)) ?: "null"
                return ok("Queried selector.", JsonObject(mapOf("text" to JsonPrimitive(raw))))
            }

            "click" -> {
                val sel = selector ?: throw IabError("MISSING_FIELD", "Field 'selector' is required for click.")
                return ok("Clicked.", jsonOf(IabManager.evaluate(tab, IabJs.click(sel)) ?: "null", "text"))
            }

            "click_text" -> {
                val t = text ?: throw IabError("MISSING_FIELD", "Field 'text' is required for click_text.")
                return ok("Clicked by text.", jsonOf(IabManager.evaluate(tab, IabJs.clickText(t)) ?: "null", "text"))
            }

            "click_at" -> {
                val x = a["x"]?.jsonPrimitiveOrNull()?.content?.toIntOrNull() ?: 0
                val y = a["y"]?.jsonPrimitiveOrNull()?.content?.toIntOrNull() ?: 0
                return ok("Clicked at ($x,$y).", jsonOf(IabManager.evaluate(tab, IabJs.clickAt(x, y)) ?: "null", "text"))
            }

            "drag" -> {
                val x = a["x"]?.jsonPrimitiveOrNull()?.content?.toIntOrNull() ?: 0
                val y = a["y"]?.jsonPrimitiveOrNull()?.content?.toIntOrNull() ?: 0
                val toX = a["to_x"]?.jsonPrimitiveOrNull()?.content?.toIntOrNull() ?: 0
                val toY = a["to_y"]?.jsonPrimitiveOrNull()?.content?.toIntOrNull() ?: 0
                val steps = a["steps"]?.jsonPrimitiveOrNull()?.content?.toIntOrNull() ?: 16
                return ok("Dragged ($x,$y)->($toX,$toY).", jsonOf(IabManager.evaluate(tab, IabJs.pointerDrag(x, y, toX, toY, steps)) ?: "null", "text"))
            }

            "swipe" -> {
                val direction = s("direction") ?: throw IabError("MISSING_FIELD", "Field 'direction' is required for swipe.")
                val x = a["x"]?.jsonPrimitiveOrNull()?.content?.toIntOrNull() ?: 0
                val y = a["y"]?.jsonPrimitiveOrNull()?.content?.toIntOrNull() ?: 0
                val distancePx = a["distance_px"]?.jsonPrimitiveOrNull()?.content?.toIntOrNull() ?: 200
                val steps = a["steps"]?.jsonPrimitiveOrNull()?.content?.toIntOrNull() ?: 16
                return ok("Swiped $direction by ${distancePx}px.", jsonOf(IabManager.evaluate(tab, IabJs.swipe(direction, x, y, distancePx, steps)) ?: "null", "text"))
            }

            "draw" -> {
                val raw = s("points") ?: throw IabError("MISSING_FIELD", "Field 'points' is required for draw (JSON array of {x,y,pressure}).")
                val parsed = runCatching { Json.parseToJsonElement(raw).jsonArray }
                    .getOrElse { throw IabError("MISSING_FIELD", "Field 'points' must be a JSON array of {x,y,pressure} objects: ${it.message}") }
                val points = parsed.map { p ->
                    val o = p.jsonObject
                    listOf(
                        o["x"]?.jsonPrimitiveOrNull()?.content?.toDoubleOrNull() ?: 0.0,
                        o["y"]?.jsonPrimitiveOrNull()?.content?.toDoubleOrNull() ?: 0.0,
                        (o["pressure"]?.jsonPrimitiveOrNull()?.content?.toDoubleOrNull() ?: 0.5).coerceIn(0.0, 1.0),
                    )
                }
                if (points.isEmpty()) throw IabError("MISSING_FIELD", "Field 'points' must contain at least one point.")
                return ok("Drew ${points.size} points.", jsonOf(IabManager.evaluate(tab, IabJs.draw(points)) ?: "null", "text"))
            }

            "canvas_touch" -> {
                val type = s("type") ?: throw IabError("MISSING_FIELD", "Field 'type' is required for canvas_touch (start/move/end/cancel).")
                val raw = s("points") ?: throw IabError("MISSING_FIELD", "Field 'points' is required for canvas_touch (JSON array of {x,y}).")
                val parsed = runCatching { Json.parseToJsonElement(raw).jsonArray }
                    .getOrElse { throw IabError("MISSING_FIELD", "Field 'points' must be a JSON array of {x,y} objects: ${it.message}") }
                val points = parsed.map { p ->
                    val o = p.jsonObject
                    listOf(
                        o["x"]?.jsonPrimitiveOrNull()?.content?.toIntOrNull() ?: 0,
                        o["y"]?.jsonPrimitiveOrNull()?.content?.toIntOrNull() ?: 0,
                    )
                }
                if (points.isEmpty()) throw IabError("MISSING_FIELD", "Field 'points' must contain at least one point.")
                return ok("Canvas touch $type (${points.size} points).", jsonOf(IabManager.evaluate(tab, IabJs.canvasTouch(type, points)) ?: "null", "text"))
            }

            "fill" -> {
                val sel = selector ?: throw IabError("MISSING_FIELD", "Field 'selector' is required for fill.")
                val v = value ?: throw IabError("MISSING_FIELD", "Field 'value' is required for fill.")
                return ok("Filled.", jsonOf(IabManager.evaluate(tab, IabJs.fill(sel, v)) ?: "null", "text"))
            }

            "set_checked" -> {
                val sel = selector ?: throw IabError("MISSING_FIELD", "Field 'selector' is required for set_checked.")
                val checked = a["checked"]?.jsonPrimitiveOrNull()?.content?.toBooleanStrict() ?: false
                return ok("Set checked.", jsonOf(IabManager.evaluate(tab, IabJs.setChecked(sel, checked)) ?: "null", "text"))
            }

            "select_option" -> {
                val sel = selector ?: throw IabError("MISSING_FIELD", "Field 'selector' is required for select_option.")
                val v = value ?: throw IabError("MISSING_FIELD", "Field 'value' is required for select_option.")
                return ok("Selected.", jsonOf(IabManager.evaluate(tab, IabJs.selectOption(sel, v)) ?: "null", "text"))
            }

            "keypress" -> {
                val k = keys ?: throw IabError("MISSING_FIELD", "Field 'keys' is required for keypress.")
                // 序列按键逐个派发（每次 action 一个键）；逗号分隔取第一个
                val first = k.split(",").first().trim()
                if (first.isEmpty()) throw IabError("MISSING_FIELD", "Field 'keys' is empty.")
                return ok("Pressed key.", jsonOf(IabManager.evaluate(tab, IabJs.keypress(first)) ?: "null", "text"))
            }

            "scroll" -> {
                val x = a["x"]?.jsonPrimitiveOrNull()?.content?.toIntOrNull() ?: 0
                val y = a["y"]?.jsonPrimitiveOrNull()?.content?.toIntOrNull() ?: 0
                return ok("Scrolled.", jsonOf(IabManager.evaluate(tab, IabJs.scroll(x, y)) ?: "null", "text"))
            }

            "submit_form" -> {
                val sel = selector ?: throw IabError("MISSING_FIELD", "Field 'selector' is required for submit_form.")
                return ok("Submitted form.", jsonOf(IabManager.evaluate(tab, IabJs.submitForm(sel)) ?: "null", "text"))
            }

            "file_upload" -> {
                val sel = selector ?: throw IabError("MISSING_FIELD", "Field 'selector' is required for file_upload.")
                val f = file ?: throw IabError("MISSING_FIELD", "Field 'file' is required for file_upload.")
                val r = IabManager.fileUpload(tab, sel, f)
                return ok("File upload: ${r.status}", JsonObject(mapOf("text" to JsonPrimitive(r.detail))))
            }

            "open_local" -> {
                val info = IabManager.openLocal(tab, file, html)
                return ok("Opened local content.", tabData(info))
            }

            "screenshot" -> {
                // r33 OPT2：single headless render. A NEW render WebView is seeded from the tab's
                // DOM snapshot + cookies + localStorage/sessionStorage, hosted in a genuinely-visible
                // Dialog on the resumed Activity (Chromium must see a visible window to paint;
                // ~150ms visible flash, non-interactive / non-focusable), and rasterized at a
                // FIXED per-mode width (view_mode: mobile=393px / desktop=1280px, default mobile)
                // at 2× DPI, full document height. IAB window-state irrelevant: open, collapsed,
                // or fully off.
                val viewMode = a["view_mode"]?.jsonPrimitiveOrNull()?.content ?: "mobile"
                val result = IabManager.screenshot(tab, viewMode)
                return when (result) {
                    is IngestResult.Ok -> result.toImageToolResult(message = "Browser screenshot captured (${viewMode}): ${result.image.path}")
                    is IngestResult.Err -> BuiltinToolResult.failure(
                        code = IabManager.IAB_SCREENSHOT_FAILED,
                        message = "Screenshot failed: ${result.error}",
                        hint = "The headless renderer's visible Dialog host could not be shown on a resumed Activity (or the PNG encode failed). Bring the app back to the foreground and retry; if the page looks unfinished, wait for it to settle and retry.",
                    )
                }
            }

            "downloads" -> {
                val list = IabManager.downloads(a["limit"]?.jsonPrimitiveOrNull()?.content?.toIntOrNull() ?: 20)
                val arr = buildJsonArray { list.forEach { add(objDownload(it)) } }
                return ok("Downloads: ${list.size}", JsonObject(mapOf("downloads" to arr)))
            }

            "state" -> {
                val ready = IabManager.evaluate(tab, "JSON.stringify({readyState: document.readyState, title: document.title, url: location.href})") ?: "{}"
                val all = IabManager.tabs()
                val current = all.firstOrNull { it.id == tab } ?: all.firstOrNull { it.active }
                val body = buildJsonObject {
                    put("state", JsonPrimitive(ready))
                    put("tab", current?.let { obj(it) } ?: JsonNull)
                }
                return ok("Page state.", body)
            }

            "verify_check" -> {
                // 验证墙探测（只读、不点击）：命中时把 hints/selector/inputCount 折进结果，
                // agent 据此判定"需要人手完成"并做有界复检，而不是无限等待。
                val raw = IabManager.evaluate(tab, IabJs.captchaProbe()) ?: "null"
                val detected = parseCaptchaDetected(raw)
                return ok(
                    if (detected) "Verification gate detected (kind may need a human)."
                    else "No verification gate detected.",
                    jsonOf(raw, "text"),
                )
            }

            "console" -> {
                val limit = a["limit"]?.jsonPrimitiveOrNull()?.content?.toIntOrNull() ?: 50
                // r21 Item 2 (F3b): the native onConsoleMessage ring (IabManager.consoleOf) is
                // the reliable channel (r17/r18 "worked" path) — the JS hook ring only catches
                // console.* called AFTER install and is lost on SPA reloads. Merge both, and
                // surface the hook-install result so the agent distinguishes "empty console,
                // hook OK" from "hook install failed, JS ring unavailable".
                val hook = IabManager.ensureConsoleHook(tab)
                val jsRaw = IabManager.evaluate(tab, IabJs.consoleRead(limit)) ?: "null"
                val nativeRaw = IabManager.consoleOf(tab, limit)
                val body = buildJsonObject {
                    put("hook", JsonPrimitive(hook))
                    put("native", JsonPrimitive(nativeRaw))
                    put("js", JsonPrimitive(jsRaw))
                    put("text", JsonPrimitive(mergeConsoleJson(nativeRaw, jsRaw, limit)))
                }
                return ok("Console log.", body)
            }

            "network" -> {
                val limit = a["limit"]?.jsonPrimitiveOrNull()?.content?.toIntOrNull() ?: 50
                val raw = IabManager.networkOf(tab, limit)
                return ok("Network log.", JsonObject(mapOf("text" to JsonPrimitive(raw))))
            }

            "cookies_get" -> {
                val u = url ?: throw IabError("MISSING_FIELD", "Field 'url' is required for cookies_get.")
                val c = IabManager.cookiesGet(u)
                return ok("Cookies for $u", JsonObject(mapOf("text" to JsonPrimitive(c))))
            }

            "cookies_set" -> {
                val u = url ?: throw IabError("MISSING_FIELD", "Field 'url' is required for cookies_set.")
                val c = cookies ?: throw IabError("MISSING_FIELD", "Field 'cookies' is required for cookies_set.")
                val r = IabManager.cookiesSet(u, c)
                return ok(r, JsonObject(emptyMap()))
            }

            "cookies_clear" -> {
                val u = url ?: throw IabError("MISSING_FIELD", "Field 'url' is required for cookies_clear.")
                val r = IabManager.cookiesClear(u)
                return ok(r, JsonObject(emptyMap()))
            }

            "clipboard_read" -> {
                val c = IabManager.clipboardRead()
                return ok(if (c.isEmpty()) "Clipboard is empty." else "Clipboard read.", JsonObject(mapOf("text" to JsonPrimitive(c))))
            }

            "clipboard_write" -> {
                val c = text ?: throw IabError("MISSING_FIELD", "Field 'text' is required for clipboard_write.")
                IabManager.clipboardWrite(c)
                return ok("Clipboard written (${c.length} chars).", JsonObject(emptyMap()))
            }

            "set_ua" -> {
                val r = IabManager.setUserAgent(userAgent)
                return ok(r, JsonObject(emptyMap()))
            }

            "set_zoom" -> {
                val z = a["zoom"]?.jsonPrimitiveOrNull()?.content?.toFloatOrNull() ?: 1.0f
                val r = IabManager.setZoom(z)
                return ok(r, JsonObject(emptyMap()))
            }

            "emulate_desktop" -> {
                val enabled = a["enabled"]?.jsonPrimitiveOrNull()?.content?.toBooleanStrict() ?: true
                val r = IabManager.setDesktopMode(enabled)
                return ok(r, JsonObject(emptyMap()))
            }

            "set_media_grant" -> {
                val enabled = a["enabled"]?.jsonPrimitiveOrNull()?.content?.toBooleanStrict() ?: true
                IabManager.autoGrantMedia = enabled
                return ok(if (enabled) "Media permission requests will be auto-granted." else "Media permission requests will be denied.", JsonObject(emptyMap()))
            }

            else -> {
                throw IabError("UNSUPPORTED_ACTION", "Unhandled action: $action")
            }
        }
    }

    private fun ok(message: String, data: JsonObject): BuiltinToolResult =
        BuiltinToolResult.success(message = message, data = data)

    private fun tabData(info: com.niki914.zafiro.chat.agentic.iab.IabTabInfo): JsonObject =
        buildJsonObject {
            put("tab", JsonPrimitive(info.id))
            put("url", JsonPrimitive(info.url))
            put("title", JsonPrimitive(info.title))
            put("progress", JsonPrimitive(info.progress))
        }

    private fun obj(tab: com.niki914.zafiro.chat.agentic.iab.IabTabInfo): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(tab.id))
        put("url", JsonPrimitive(tab.url))
        put("title", JsonPrimitive(tab.title))
        put("active", JsonPrimitive(tab.active))
    }

    private fun objDownload(dl: com.niki914.zafiro.chat.agentic.iab.IabDownload): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(dl.id))
        put("url", JsonPrimitive(dl.url))
        put("path", JsonPrimitive(dl.path))
        put("size", JsonPrimitive(dl.sizeBytes))
        put("status", JsonPrimitive(dl.status))
        dl.error?.let { put("error", JsonPrimitive(it)) }
    }

    private fun jsonOf(rawJson: String, key: String): JsonObject = JsonObject(mapOf(key to JsonPrimitive(rawJson)))

    /**
     * 合并两路控制台数据（r21 Item 2 / F3b）：原生 [IabManager.consoleOf] 的 JSON 数组
     * （由 onConsoleMessage 写入，无时间戳）+ JS hook 的 {count, lines:[...]} 对象
     * （lines 行带 "level: " 前缀，由 [IabJs.consoleRead] 生成）。去重后取最近 [limit] 条，
     * 格式化为单行文本返回；agent 侧仍可从同一次结果的 `native` / `js` 字段拿到原始 JSON。
     */
    private fun mergeConsoleJson(nativeArray: String, jsObject: String, limit: Int): String {
        val lines = LinkedHashSet<String>()
        // JS 环：lines[] 里的字符串（保留 "level: msg" 前缀）
        runCatching {
            val obj = Json.parseToJsonElement(jsObject).jsonObject
            val linesArr = obj["lines"]?.jsonArray ?: JsonArray(emptyList())
            linesArr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .forEach { if (it.isNotBlank()) lines.add(it) }
        }
        // 原生环：JSON 数组（每条为完整 "[level] msg" 字符串）
        runCatching {
            (Json.parseToJsonElement(nativeArray).jsonArray)
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .forEach { if (it.isNotBlank()) lines.add(it) }
        }
        return lines.toList().takeLast(limit.coerceIn(1, 500)).joinToString("\n")
    }

    /** verify_check 结果里 {ok:true, detected:bool, ...} 的解析（envelope 缺失时按未命中处理）。 */
    private fun parseCaptchaDetected(rawJson: String): Boolean =
        runCatching {
            Json.parseToJsonElement(rawJson)
                .jsonObject
                .get("detected") as? JsonPrimitive
        }.getOrNull()?.contentOrNull?.toBooleanStrict() ?: false

    private fun JsonElement?.jsonPrimitiveOrNull(): JsonPrimitive? = (this as? JsonPrimitive)
    private fun JsonElement?.jsonObjectOrNull(): JsonObject? = (this as? JsonObject)
    private fun String?.toBooleanStrict(): Boolean? = when (this?.lowercase()) { "true" -> true; "false" -> false; else -> null }
    private fun awaitPromiseOf(a: JsonObject): Boolean = (a["await_promise"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrict() ?: false

    companion object {
        private val ACTIONS = setOf(
            "navigate", "new_tab", "close_tab", "switch_tab", "tabs",
            "back", "forward", "reload", "stop",
            "evaluate", "page_text", "page_html", "query_selector",
            "click", "click_text", "click_at", "fill", "set_checked", "select_option", "keypress", "scroll", "submit_form",
            "drag", "swipe", "draw", "canvas_touch",
            "file_upload", "downloads", "open_local", "screenshot", "state", "verify_check", "console", "network",
            "cookies_get", "cookies_set", "cookies_clear",
            "clipboard_read", "clipboard_write",
            "set_ua", "set_zoom", "emulate_desktop", "set_media_grant",
        )

        private val HINTS = mapOf(
            IabManager.IAB_UNAVAILABLE to "App still initializing. Retry once.",
            IabManager.IAB_NO_TAB to "Call new_tab (or navigate) to open the first tab.",
            IabManager.IAB_BAD_TAB to "List tabs with action=tabs and use a valid id.",
            IabManager.IAB_FILE_FORBIDDEN to "Copy the file into app private storage (e.g. via execute_python) and use that path.",
            IabManager.IAB_FILE_MISSING to "Verify the path exists (downloads action lists captured files).",
            IabManager.IAB_BAD_URL to "Check the URL (no script pseudo-URLs; data: capped at 400k chars).",
            IabManager.IAB_SCREENSHOT_FAILED to "screenshot rasterizes the tab in a headless render (a NEW WebView seeded from the tab's DOM snapshot + cookies + storage) hosted in a genuinely-visible Dialog on the app's resumed Activity (~150ms visible flash; visible so Chromium actually paints; no overlay permission needed), then encodes the result to PNG at a fixed per-mode width (view_mode: mobile=393px / desktop=1280px, default mobile) at 2x DPI and full document height. This failure means no resumed Activity was available to host the Dialog (bring the app back to the foreground and retry), the host could not be shown / became visible, the seed/encode pipeline could not complete, or it hit the hard timeout / OOM.",
            IabManager.IAB_BLANK_CAPTURE to "screenshot produced a blank (all-white) capture even after a retry with a longer paint wait — the page may not have finished rendering, or it is genuinely empty. Re-navigate or reload the page and wait for progress=100 before retrying; an all-white result is never silently returned as a successful screenshot.",
            IabManager.IAB_JS_ENGINE_ERROR to "The page may have navigated away or crashed; reload and retry.",
            "IAB_POINTER_UNTRUSTED" to "The drag/swipe/draw primitives dispatch untrusted PointerEvents; some web apps (React synthetic events, some CAPTCHA widgets) ignore them. For a trusted-gesture version see the optional overlay/accessibility opt-in (not included in this build).",
        )

        private val SCHEMA = """
{
  "type": "object",
  "properties": {
    "action": {
      "type": "string",
      "enum": ${ACTIONS.map { "\"$it\"" }.joinToString(", ", prefix = "[", postfix = "]")},
      "description": "The IAB operation to perform."
    },
    "url": { "type": "string", "description": "Target URL (navigate / cookies_*)." },
    "tab": { "type": "string", "description": "Tab id (from tabs/new_tab). Omit = active tab." },
    "expression": { "type": "string", "description": "JS expression for evaluate (may use await when await_promise=true)." },
    "await_promise": { "type": "boolean", "description": "evaluate: wrap the expression in a promise-await." },
    "tab_only": { "type": "boolean", "description": "verify_check: pass {tab?} only — no other fields; reports whether a CAPTCHA/verification gate is visible." },
    "selector": { "type": "string", "description": "CSS selector (click/fill/query_selector/...)." },
    "text": { "type": "string", "description": "Visible text (click_text) or payload (clipboard_write)." },
    "value": { "type": "string", "description": "Input value (fill/select_option)." },
    "checked": { "type": "boolean", "description": "set_checked target state." },
    "keys": { "type": "string", "description": "Key name(s) for keypress (first key of a comma list is used)." },
    "x": { "type": "integer", "description": "X coordinate (click_at/scroll)." },
    "y": { "type": "integer", "description": "Y coordinate (click_at/scroll)." },
    "limit": { "type": "integer", "description": "Truncation cap (page_text/page_html/query_selector/console/network/downloads)." },
    "file": { "type": "string", "description": "File path for file_upload / open_local (app storage only)." },
    "html": { "type": "string", "description": "Raw HTML to render via open_local." },
    "cookies": { "type": "string", "description": "Raw cookie string for cookies_set." },
    "user_agent": { "type": "string", "description": "Custom UA for set_ua (null/omit = default)." },
    "zoom": { "type": "number", "description": "Text zoom factor 0.25-3.0 (maps to 50-300% text zoom on this SDK) for set_zoom." },
    "enabled": { "type": "boolean", "description": "Toggle for emulate_desktop / set_media_grant." },
    "to_x": { "type": "integer", "description": "drag: end X coordinate (pairs with x)." },
    "to_y": { "type": "integer", "description": "drag: end Y coordinate (pairs with y)." },
    "direction": { "type": "string", "enum": ["left", "right", "up", "down"], "description": "swipe: direction of the gesture." },
    "distance_px": { "type": "integer", "description": "swipe: travel distance in CSS pixels from the (x,y) anchor." },
    "steps": { "type": "integer", "description": "drag/swipe/draw/canvas_touch: number of intermediate pointer-move frames (default 16)." },
    "points": { "type": "string", "description": "draw: JSON array of {x,y,pressure} points, in order; canvas_touch: JSON array of {x,y} points." },
    "type": { "type": "string", "enum": ["start", "move", "end", "cancel"], "description": "canvas_touch: which touch phase to dispatch." },
    "view_mode": { "type": "string", "enum": ["mobile", "desktop"], "description": "screenshot: fixed render width — mobile=393px, desktop=1280px (default mobile). Independent of the live IAB window width; the page is re-laid-out and re-rasterized at this width with the matching user-agent." }
  },
  "required": ["action"]
}
        """.trimIndent()
    }
}
