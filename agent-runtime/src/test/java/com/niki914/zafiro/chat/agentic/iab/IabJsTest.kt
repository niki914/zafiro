package com.niki914.zafiro.chat.agentic.iab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [IabJs] 纯字符串载荷的 JVM 单测（无 Android 依赖）。
 * 变异检查点：
 * - jsQuote 不转义单引号/反斜换行 → fill/click 载荷会破坏 JS（这里逐字符断言）；
 * - evaluate 信封丢了 await 包装 / try-catch → 异常不再折进 {ok:false}；
 * - 各交互载荷丢了 change 事件 / 原生 setter → React 表单不再感知输入。
 */
class IabJsTest {

    @Test
    fun jsQuote_escapesSingleQuote() {
        assertEquals("'a\\'b'", IabJs.jsQuote("a'b"))
    }

    @Test
    fun jsQuote_escapesBackslash() {
        assertEquals("'a\\\\b'", IabJs.jsQuote("a\\b"))
    }

    @Test
    fun jsQuote_escapesNewlineAndCarriageReturn() {
        assertEquals("'a\\nb\\rc'", IabJs.jsQuote("a\nb\rc"))
    }

    @Test
    fun jsQuote_escapesControlCharsAsUnicode() {
        assertEquals("'\\u0001'", IabJs.jsQuote(String(charArrayOf('\u0001'))))
    }

    @Test
    fun jsQuote_plainStringUnchanged() {
        assertEquals("'input#name'", IabJs.jsQuote("input#name"))
    }

    @Test
    fun evaluateEnvelopePlainIsSynchronous() {
        val js = IabJs.evaluateEnvelope("1 + 1", awaitPromise = false)
        assertTrue(js.startsWith("(function() {"))
        assertFalse(js.contains("(async () => {"))
        assertTrue(js.contains("const __val = (1 + 1);"))
        assertTrue(js.contains("return JSON.stringify({ ok: true, value: __conv(__val) });"))
        assertTrue(js.contains("return JSON.stringify({ ok: false, error: String((e && e.message) ? e.message : e) });"))
    }

    @Test
    fun evaluateEnvelopeAwaitUsesPendingGlobal() {
        val js = IabJs.evaluateEnvelope("fetch('/x')", awaitPromise = true)
        assertTrue(js.startsWith("(function() {"))
        assertTrue(js.contains("__ZAFIRO_EVAL_PENDING__:"))
        assertTrue(js.contains("window.__zafiroEvalResults = window.__zafiroEvalResults || {};"))
        assertTrue(js.contains("const __val = await (async () => { fetch('/x') })();"))
        assertFalse(js.startsWith("(async () => {"))
    }

    @Test
    fun evaluateEnvelope_inlinesExpression() {
        val js = IabJs.evaluateEnvelope("document.title", awaitPromise = false)
        assertTrue("expression must be inlined: $js", js.contains("document.title"))
        assertTrue("must return JSON envelope", js.contains("JSON.stringify"))
        assertTrue("must catch runtime errors", js.contains("catch (e)"))
        assertFalse("sync mode must not double-wrap in async fn", js.contains("(async () => {"))
    }

    @Test
    fun evaluateEnvelope_awaitPromiseWritesPendingResult() {
        val js = IabJs.evaluateEnvelope("fetch('/api').then(r => r.json())", awaitPromise = true)
        assertTrue("await mode must return the pending marker synchronously: $js", js.contains("'__ZAFIRO_EVAL_PENDING__:'"))
        assertTrue(js.contains("fetch('/api').then(r => r.json())"))
        assertTrue("result must land in the global pending map", js.contains("window.__zafiroEvalResults"))
        assertTrue(js.contains("__results[__id] = JSON.stringify({ ok: true, value: __conv(__val) });"))
        assertFalse("must not be one big async IIFE (its settled Promise serializes to \"{}\")", js.startsWith("(async () => {"))
    }

    @Test
    fun evaluateEnvelope_quotesExpressionWithQuotesSafely() {
        // 表达式自身带引号不应破坏信封（表达式内联，不需要再包一层引号——
        // 这里验证信封语法骨架完整：有配对的 IIFE 与 return）
        val js = IabJs.evaluateEnvelope("""localStorage.getItem("k")""", awaitPromise = false)
        assertTrue(js.startsWith("(function() {"))
        assertTrue(js.endsWith("})()"))
        assertTrue(js.contains("localStorage.getItem(\"k\")"))
    }

    @Test
    fun consoleHook_isIdempotentAndInstallsRing() {
        val js = IabJs.consoleHook()
        assertTrue(js.contains("__zafiroConsoleHook"))
        assertTrue("hook must expose a readable ring", js.contains("__zafiroConsole"))
        assertTrue(js.contains("500"))
    }

    @Test
    fun consoleRead_slicesLastN() {
        val js = IabJs.consoleRead(50)
        assertTrue(js.contains("slice(-50)"))
        val clamped = IabJs.consoleRead(9999)
        assertTrue("limit must clamp to 500", clamped.contains("slice(-500)"))
        val min = IabJs.consoleRead(0)
        assertTrue(min.contains("slice(-1)"))
    }

    @Test
    fun querySelector_usesEscapedSelectorVariable() {
        val js = IabJs.querySelector("input[name='x'] > #y", 3)
        // 选择器必须先转义进局部变量，再在 JS 里用变量（防止引号破坏 JS）
        assertTrue(js.contains("var sel = " + IabJs.jsQuote("input[name='x'] > #y")))
        assertFalse("raw selector must not be inlined unquoted", js.contains("document.querySelectorAll(input[name='x']"))
        assertTrue(js.contains("slice(0, 3)"))
    }

    @Test
    fun fill_usesPrototypeSetterAndDispatchesEvents() {
        val js = IabJs.fill("#name", "va'l\\ue")
        assertTrue("must use the React-compatible prototype setter", js.contains("getOwnPropertyDescriptor"))
        assertTrue("must dispatch input event", js.contains("new Event(\"input\""))
        assertTrue("must dispatch change event", js.contains("new Event(\"change\""))
        // 值里的单引号与反斜杠必须被转义
        assertTrue(js.contains(IabJs.jsQuote("va'l\\ue")))
    }

    @Test
    fun keypress_singleLetterAddsKeypressEvent() {
        val js = IabJs.keypress("a")
        assertTrue(js.contains("keypress"))
    }

    @Test
    fun keypress_specialKeyOmitsKeypressEvent() {
        val js = IabJs.keypress("Enter")
        assertFalse("Enter must not fire a keypress event", js.contains("new KeyboardEvent(\"keypress\""))
        assertTrue("Enter on button must click", js.contains("el.click()"))
    }

    @Test
    fun clickAt_usesElementFromPoint() {
        val js = IabJs.clickAt(10, 20)
        assertTrue(js.contains("var x = 10; var y = 20;"))
        assertTrue(js.contains("document.elementFromPoint(x, y)"))
        assertTrue(js.contains("pointerdown"))
        assertTrue(js.contains("click"))
    }

    @Test
    fun scrollAndSubmitAreSelfContained() {
        assertTrue(IabJs.scroll(5, 6).contains("window.scrollTo(5, 6)"))
        val submit = IabJs.submitForm("#f")
        assertTrue(submit.contains("requestSubmit"))
        assertTrue(submit.contains("form.submit"))
    }

    @Test
    fun desktopViewport_onInsertsMeta_offRemovesIt() {
        assertTrue(IabJs.desktopViewport(true).contains("width=1280"))
        assertTrue(IabJs.desktopViewport(false).contains("removeChild"))
    }

    @Test
    fun uploadHelpers_targetFileInputs() {
        val click = IabJs.uploadClick("input[type=file]")
        assertTrue(click.contains("el.click()"))
        assertTrue(click.contains("input[type=file]".let { "file" }))
        val count = IabJs.uploadFileCount("#up")
        assertTrue(count.contains("el.files"))
    }
}
