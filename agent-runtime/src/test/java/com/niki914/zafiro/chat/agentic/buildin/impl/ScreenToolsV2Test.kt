package com.niki914.zafiro.chat.agentic.buildin.impl

import com.niki914.zafiro.api.screen.ActResult
import com.niki914.zafiro.api.screen.AppHit
import com.niki914.zafiro.api.screen.CloseResult
import com.niki914.zafiro.api.screen.ImageFrame
import com.niki914.zafiro.api.screen.NodeInfo
import com.niki914.zafiro.api.screen.OpenKind
import com.niki914.zafiro.api.screen.OpenResult
import com.niki914.zafiro.api.screen.Rect
import com.niki914.zafiro.api.screen.Screen
import com.niki914.zafiro.api.screen.ScreenAction
import com.niki914.zafiro.api.screen.ScreenId
import com.niki914.zafiro.api.screen.ScreenInfo
import com.niki914.zafiro.api.screen.ScreenKind
import com.niki914.zafiro.api.screen.ScreenSnapshot
import com.niki914.zafiro.api.screen.Screens
import com.niki914.zafiro.api.screen.SemanticToken
import com.niki914.zafiro.api.screen.SemanticType
import com.niki914.zafiro.api.screen.ShowResult
import com.niki914.zafiro.api.screen.ShowTarget
import com.niki914.zafiro.api.screen.Target
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRegistry
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.RawJsonBuiltinTool
import com.niki914.zafiro.chat.agentic.buildin.TextToolResult
import com.niki914.zafiro.chat.agentic.buildin.TextToolResultCodec
import com.niki914.zafiro.service.ServiceRegistry
import com.niki914.zafiro.service.installService
import com.niki914.xposed.api.util.ContextProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// 保护：V2 工具把 screen / token / 坐标 / uri / kind 交到 Screens，漏传时模型会打到错误的屏。
class ScreenToolsV2Test {
    private val phone = ScreenId("phone")
    private lateinit var screen: RecordingScreen
    private lateinit var screens: RecordingScreens

    @Before
    fun setUp() {
        ServiceRegistry.clearForTest()
        ContextProvider.clearForTest()
        LastPhoneCapture.clear()
        screen = RecordingScreen(phone, listOf(node(3, "微信"), node(4, "设置")))
        screens = RecordingScreens(screen)
        installService<Screens>(screens)
    }

    @After
    fun tearDown() {
        ServiceRegistry.clearForTest()
        ContextProvider.clearForTest()
    }

    @Test
    fun readReturnsThatScreensYaml() = runTest {
        val result = invokeOperation("""{"screen":"phone","operation":"read"}""")

        assertEquals(TextToolResult.Status.Success, result.status)
        assertEquals("tree", result.payload)
        assertEquals(1, screen.snapshots)
        assertNull(screen.action)
    }

    @Test
    fun tapTokenCallsActWithThatNode() = runTest {
        invokeOperation("""{"screen":"phone","operation":"tap","token":"abc_2"}""")

        assertEquals(
            ScreenAction.Tap(Target.Node(SemanticToken("abc", 2))),
            screen.action,
        )
    }

    @Test
    fun tapPointCallsActWithThoseCoordinates() = runTest {
        invokeOperation("""{"screen":"phone","operation":"tap","x":12,"y":34}""")

        assertEquals(ScreenAction.Tap(Target.Point(12, 34)), screen.action)
    }

    @Test
    fun historyBackIsForwarded() = runTest {
        invokeOperation("""{"screen":"phone","operation":"history_back"}""")

        assertEquals(ScreenAction.History(forward = false), screen.action)
    }

    @Test
    fun searchFiltersSnapshotAndDoesNotAct() = runTest {
        val result = invokeOperation(
            """{"screen":"phone","operation":"search","keywords":["微信"]}""",
        )

        assertEquals(TextToolResult.Status.Success, result.status)
        assertTrue(result.payload.contains("3\t微信"))
        assertTrue(!result.payload.contains("设置"))
        assertNull(screen.action)
    }

    @Test
    fun unknownScreenDoesNotCallTheBackend() = runTest {
        val result = invokeOperation("""{"screen":"missing","operation":"read"}""")

        assertEquals(SCREEN_UNKNOWN, result.code)
        assertEquals(0, screen.snapshots)
    }

    @Test
    fun openUriIsPassedThrough() = runTest {
        val result = invokeText("open", """{"screen":"phone","uri":"https://example.com"}""")

        assertEquals(TextToolResult.Status.Success, result.status)
        assertEquals(ShowTarget.Uri("https://example.com"), screen.shown)
    }

    @Test
    fun twoOpenIdentifiersAreRejectedBeforeOpen() = runTest {
        val result = invokeText(
            "open",
            """{"screen":"phone","app_name":"微信","uri":"https://example.com"}""",
        )

        assertEquals(SCREEN_INVALID_ARGUMENTS, result.code)
        assertNull(screen.shown)
    }

    @Test
    fun ambiguousAppNameReturnsCandidates() = runTest {
        val result = invokeText("open", """{"screen":"phone","app_name":"微信"}""")

        assertEquals(SCREEN_AMBIGUOUS_APP, result.code)
        assertTrue(result.payload.contains("com.tencent.mm\t微信"))
        assertTrue(result.payload.contains("com.tencent.mm.clone\t微信"))
        assertEquals(ShowTarget.AppName("微信"), screen.shown)
    }

    @Test
    fun openForwardsKind() = runTest {
        val result = invokeText("screens", """{"operation":"open","kind":"virtual_display"}""")

        assertEquals(OpenKind.VirtualDisplay, screens.opened)
        assertTrue(result.payload.startsWith("vd-1\t"))
    }

    @Test
    fun missingServiceFailsTheCall() = runTest {
        ServiceRegistry.clearForTest()

        val result = invokeOperation("""{"screen":"phone","operation":"read"}""")

        assertEquals(SCREEN_SERVICE_UNAVAILABLE, result.code)
        assertEquals(0, screen.snapshots)
    }

    @Test
    fun screenshotAsksThatScreenForItsFrame() = runTest {
        val tool = BuiltinToolRegistry.default().find("screenshot")
            ?: error("screenshot is not registered")
        val result = tool.invoke(
            BuiltinToolRequest("screenshot", """{"screen":"phone"}"""),
        )

        assertEquals(1, screen.images)
        assertEquals("CODEC_UNAVAILABLE", result.code)
    }

    private suspend fun invokeOperation(json: String): TextToolResult {
        return invokeText("screen_operation", json)
    }

    private suspend fun invokeText(name: String, json: String): TextToolResult {
        val tool = BuiltinToolRegistry.default().find(name) as RawJsonBuiltinTool
        return tool.invokeRawJson(BuiltinToolRequest(name, json)).decoded()
    }

    private fun String.decoded(): TextToolResult {
        return TextToolResultCodec.decode(this)
            ?: error("tool result did not decode")
    }

    private fun node(index: Int, text: String): NodeInfo {
        return NodeInfo(
            index = index,
            semanticType = SemanticType.BUTTON,
            text = text,
            contentDesc = "",
            bounds = Rect(0, 0, 1, 1),
            isClickable = true,
            isLongClickable = false,
            isEditable = false,
            isScrollable = false,
            isChecked = false,
            children = emptyList(),
        )
    }

    private class RecordingScreen(
        override val id: ScreenId,
        private val nodes: List<NodeInfo>,
    ) : Screen {
        override val kind: ScreenKind = ScreenKind.Phone
        var snapshots: Int = 0
        var images: Int = 0
        var action: ScreenAction? = null
        var shown: ShowTarget? = null

        private val snap = ScreenSnapshot(
            screen = id,
            version = "v1",
            width = 100,
            height = 200,
            url = null,
            nodes = nodes,
            yaml = "tree",
        )

        override suspend fun snapshot(): ScreenSnapshot {
            snapshots += 1
            return snap
        }

        override suspend fun image(): ImageFrame {
            images += 1
            return ImageFrame(id, 100, 200, byteArrayOf(1))
        }

        override suspend fun act(action: ScreenAction): ActResult {
            this.action = action
            return ActResult.Done(snap, tabs = null)
        }

        override suspend fun show(target: ShowTarget): ShowResult {
            shown = target
            if (target is ShowTarget.AppName) {
                return ShowResult.Ambiguous(
                    target.name,
                    listOf(
                        AppHit("com.tencent.mm", target.name),
                        AppHit("com.tencent.mm.clone", target.name),
                    ),
                )
            }
            return ShowResult.Shown("opened")
        }
    }

    private class RecordingScreens(private val screen: RecordingScreen) : Screens {
        var opened: OpenKind? = null

        override fun list(): List<ScreenInfo> {
            return listOf(ScreenInfo(screen.id, screen.kind, 100, 200))
        }

        override fun get(id: ScreenId): Screen? = if (id == screen.id) screen else null

        override suspend fun open(kind: OpenKind): OpenResult {
            opened = kind
            return OpenResult.Opened(
                ScreenInfo(ScreenId("vd-1"), ScreenKind.VirtualDisplay, 1080, 2400),
            )
        }

        override suspend fun close(id: ScreenId): CloseResult = CloseResult.Closed
    }
}
