package com.niki914.zafiro.chat.agentic.buildin.impl

import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.iab.IabJs
import com.niki914.zafiro.chat.agentic.iab.IabManager
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BrowserBuiltin] 的参数契约与冷启动门控的 JVM 单测（不依赖 Android 运行时）。
 * 门控断言是变异检查点：若有人删掉 [IabManager.isReady] 前置判断，
 * 这些用例会挂起或抛 MainDispatcher 异常（而不是干净的 IAB_UNAVAILABLE）。
 */
class BrowserBuiltinTest {

    private val tool = BrowserBuiltin()

    private fun invoke(json: String) = runBlocking {
        tool.invoke(BuiltinToolRequest(name = "browser", argumentsJson = json))
    }

    @Test
    fun missingActionFailsCleanly() {
        val r = invoke("""{"url":"https://example.com"}""")
        assertFalse(r.ok)
        assertEquals("MISSING_ACTION", r.code)
        assertTrue(r.fieldErrors.containsKey("action"))
    }

    @Test
    fun unknownActionFailsCleanly() {
        val r = invoke("""{"action":"teleport"}""")
        assertFalse(r.ok)
        assertEquals("UNSUPPORTED_ACTION", r.code)
    }

    @Test
    fun malformedArgumentsFailCleanly() {
        val r = invoke("not json at all")
        assertFalse(r.ok)
        assertEquals("INVALID_ARGUMENTS", r.code)
    }

    @Test
    fun coldStartReturnsUnavailableWithoutHanging() {
        // JVM 单测没有 Android 上下文 → isReady() 必须为 false，且调用立即返回（不挂起、不抛）。
        assertTrue("JVM test environment must not have IAB context", !IabManager.isReady())
        val r = invoke("""{"action":"navigate","url":"https://example.com"}""")
        assertFalse(r.ok)
        assertEquals(IabManager.IAB_UNAVAILABLE, r.code)
        assertEquals("IAB_UNAVAILABLE", r.code)
    }

    @Test
    fun schemaIsValidJsonAndEnumMatchesKnownActions() {
        val schemaJson = Json.parseToJsonElement(tool.inputSchemaJson!!)
        val properties = schemaJson.jsonObject["properties"]!!.jsonObject
        val enum = properties["action"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        // schema 的 enum 必须与分发表一一对应（少一个 action → 模型无法调用；多一个 → 必落 else 分支报错）
        assertTrue(enum.contains("navigate"))
        assertTrue(enum.contains("screenshot"))
        assertTrue(enum.contains("file_upload"))
        assertTrue(enum.contains("emulate_desktop"))
        assertEquals(listOf("action"), schemaJson.jsonObject["required"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun toolIsRegisteredInDefaultRegistry() {
        // 回归：新工具必须出现在 BuiltinToolRegistry.default()（否则模型永远看不到它）
        val registry = com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRegistry.default()
        val found = registry.find("browser")
        assertNotNull("browser tool missing from default registry", found)
        assertEquals("browser", found!!.name)
        // description 必须覆盖主要 action 名（模型靠 description 学用法）
        val d = found.description
        assertTrue(d.contains("navigate"))
        assertTrue(d.contains("screenshot"))
    }

    @Test
    fun defaultEnabledIsTrue() {
        assertTrue(tool.defaultEnabled)
    }

    // --- schema enum has 43 actions (was 40; r28 unified screenshot/print_pdf to one 'screenshot';
    //     r29 keeps that single action and swapped its implementation from a PDF intermediate to an
    //     off-screen headless DOM-snapshot rasterizer — no schema/enum change) ---
    @Test
    fun schemaEnumGrewTo43WithPointerActions() {
        val schemaJson = Json.parseToJsonElement(tool.inputSchemaJson!!)
        val properties = schemaJson.jsonObject["properties"]!!.jsonObject
        val enum = properties["action"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(43, enum.size)
        for (a in listOf("drag", "swipe", "draw", "canvas_touch")) {
            assertTrue("enum must contain $a", enum.contains(a))
        }
        // new pointer fields present in the schema
        for (f in listOf("to_x", "to_y", "direction", "distance_px", "steps", "points", "type")) {
            assertTrue("schema property '$f' must exist", properties.containsKey(f))
        }
        // r31: view_mode is a FIELD on the screenshot action (not a 44th action), so the enum stays
        // 43; the field itself is a mobile/desktop width selector.
        assertTrue("schema property 'view_mode' must exist", properties.containsKey("view_mode"))
        val viewModeEnum = properties["view_mode"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("mobile", "desktop"), viewModeEnum)
    }

    // Point 6b: each new pointer payload builder emits a self-contained IIFE + JSON envelope
    // with the right event constructors for its kind.
    @Test
    fun newPointerPayloadsAreWellFormed() {
        for (js in listOf(
            IabJs.pointerDrag(10, 20, 300, 400, 12),
            IabJs.swipe("left", 50, 60, 180, 8),
            IabJs.draw(listOf(listOf(1.0, 2.0, 0.5), listOf(3.0, 4.0, 0.9))),
            IabJs.canvasTouch("start", listOf(listOf(5, 6), listOf(7, 8))),
        )) {
            assertTrue(js.startsWith("(function(){"))
            assertTrue(js.contains("JSON.stringify({ ok: true"))
            assertTrue(js.contains("JSON.stringify({ ok: false"))
            assertTrue(js.contains("elementFromPoint"))
        }
        // per-kind markers: chain builders dispatch PointerEvents, canvasTouch dispatches TouchEvents
        for (js in listOf(IabJs.pointerDrag(0, 0, 100, 100, 4), IabJs.swipe("up", 0, 0, 100, 4), IabJs.draw(listOf(listOf(0.0, 0.0, 0.5))))) {
            assertTrue(js.contains("pointerdown"))
            assertTrue(js.contains("PointerEvent"))
        }
        val canvas = IabJs.canvasTouch("start", listOf(listOf(0, 0)))
        assertTrue(canvas.contains("touchstart"))
        assertTrue(canvas.contains("TouchEvent"))
        // untrusted-only scope marker: cold-start drag reaches IabManager and fails clean
        assertTrue(IabJs.clickAt(0, 0).contains("interactive"))
    }

    // Point 6a: clickAt now performs a geometry hit-test and reports rect/interactive/ariaRole.
    @Test
    fun clickAtJsReturnsRectAndInteractive() {
        val js = IabJs.clickAt(100, 200)
        assertTrue(js.contains("elementFromPoint"))
        assertTrue(js.contains("interactive"))
        assertTrue(js.contains("ariaRole"))
        assertTrue(js.contains("getBoundingClientRect"))
    }

    // Point 6b: dispatch contract for the 4 new actions — valid payloads reach IabManager and
    // fail clean with IAB_UNAVAILABLE on the JVM; missing fields fail with MISSING_FIELD before
    // any IAB call.
    @Test
    fun newPointerActionsDispatchAndValidateFields() {
        for (payload in listOf(
            """{"action":"drag","x":10,"y":20,"to_x":300,"to_y":400,"steps":12}""",
            """{"action":"swipe","direction":"left","x":50,"y":60,"distance_px":180}""",
            """{"action":"draw","points":"[{\"x\":1,\"y\":2,\"pressure\":0.5}]"}""",
            """{"action":"canvas_touch","type":"start","points":"[{\"x\":5,\"y\":6}]"}""",
        )) {
            val r = invoke(payload)
            assertFalse("expected IAB_UNAVAILABLE for $payload", r.ok)
            assertEquals(IabManager.IAB_UNAVAILABLE, r.code)
        }
        // canvas_touch: missing 'type' is rejected before reaching IAB
        val noType = invoke("""{"action":"canvas_touch","points":"[{\"x\":5,\"y\":6}]"}""")
        assertFalse(noType.ok)
        assertEquals("MISSING_FIELD", noType.code)
        // draw: points that are not a JSON array are rejected cleanly (IabError, not a crash)
        val badPoints = invoke("""{"action":"draw","points":"not an array"}""")
        assertFalse(badPoints.ok)
        assertEquals("MISSING_FIELD", badPoints.code)
        // canvas_touch: same clean rejection for non-array points
        val badCanvasPoints = invoke("""{"action":"canvas_touch","type":"start","points":"{oops"}""")
        assertFalse(badCanvasPoints.ok)
        assertEquals("MISSING_FIELD", badCanvasPoints.code)
    }
}
