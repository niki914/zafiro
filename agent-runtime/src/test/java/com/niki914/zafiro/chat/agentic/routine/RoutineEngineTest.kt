package com.niki914.zafiro.chat.agentic.routine

import com.niki914.zafiro.api.screen.ActResult
import com.niki914.zafiro.api.screen.ImageFrame
import com.niki914.zafiro.api.screen.NodeInfo
import com.niki914.zafiro.api.screen.Rect
import com.niki914.zafiro.api.screen.Screen
import com.niki914.zafiro.api.screen.ScreenAction
import com.niki914.zafiro.api.screen.ScreenId
import com.niki914.zafiro.api.screen.ScreenKind
import com.niki914.zafiro.api.screen.ScreenSnapshot
import com.niki914.zafiro.api.screen.SemanticType
import com.niki914.zafiro.api.screen.ShowResult
import com.niki914.zafiro.api.screen.ShowTarget
import com.niki914.zafiro.api.screen.Target
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A tiny fake app: pages with buttons. A button either navigates, does nothing ("dead"), or works only on the second tap ("flaky"). */
private class FakeApp : Screen {
    override val id = ScreenId("phone")
    override val kind = ScreenKind.Phone
    var page = "home"
    var typed = ""
    var flakyTaps = 0
    var opened: String? = null
    val log = mutableListOf<String>()

    data class Btn(val text: String, val to: String?, val dead: Boolean = false, val flaky: Boolean = false)
    val pages = mutableMapOf(
        "home" to listOf(Btn("Notes", "notes"), Btn("Dead button", null, dead = true), Btn("Flaky", "notes", flaky = true)),
        "notes" to listOf(Btn("New note", "composer"), Btn("Feed item: a long enough post text to be collected", null, dead = true)),
        "composer" to listOf(Btn("Post", "posted")),
        "posted" to listOf(Btn("Note posted", null, dead = true)),
    )

    private fun nodes(): List<NodeInfo> {
        val list = pages.getValue(page).mapIndexed { i, b -> node(i, b.text, clickable = true) }.toMutableList()
        if (page == "composer") list += node(50, typed.ifEmpty { "What's on your mind" }, editable = true)
        return list
    }

    private fun node(i: Int, text: String, clickable: Boolean = false, editable: Boolean = false) = NodeInfo(
        i, SemanticType.TEXT, text, "", Rect(0, i * 10, 100, i * 10 + 9), clickable, false, editable, false, false, emptyList(),
    )

    override suspend fun snapshot(): ScreenSnapshot {
        val n = nodes()
        return ScreenSnapshot(id, "v${log.size}", 1080, 2400, null, n, "page=$page typed=$typed " + n.joinToString { it.text })
    }

    override suspend fun image(): ImageFrame = error("unused")

    override suspend fun act(action: ScreenAction): ActResult {
        log += action.toString()
        when (action) {
            is ScreenAction.Tap -> {
                val idx = ((action.target as? Target.Node)?.token?.index) ?: -1
                val b = pages.getValue(page).getOrNull(idx)
                if (b != null && !b.dead && b.to != null) {
                    if (b.flaky && ++flakyTaps < 2) Unit else page = b.to
                }
            }
            is ScreenAction.SetText -> typed = action.text
            else -> Unit
        }
        return ActResult.Done(snapshot(), null)
    }

    override suspend fun show(target: ShowTarget): ShowResult {
        opened = (target as? ShowTarget.PackageName)?.packageName
        return ShowResult.Shown()
    }
}

class RoutineEngineTest {
    private fun engine(app: FakeApp) = RoutineEngine(app, sleep = {})

    private val postNote = RoutineParser.parse(
        """{"id":"t.post","steps":[
          {"op":"open_app","package":"com.example"},
          {"op":"tap","labels":["Notes"],"expect_any":["New note"]},
          {"op":"tap","labels":["New note"],"expect_any":["What's on your mind"]},
          {"op":"type","labels":["mind"],"text":"{text}"},
          {"op":"tap","labels":["Post"],"expect_any":["Note posted"]}]}""",
    )

    @Test
    fun runsAWholeRoutineInOneCall() = runTest {
        val app = FakeApp()
        val r = engine(app).run(postNote.id, postNote.steps, mapOf("text" to "Hello world note"))
        assertTrue(r.reason, r.ok)
        assertEquals(5, r.stepsDone)
        assertEquals("com.example", app.opened)
        assertEquals("Hello world note", app.typed.let { app.typed })
        assertTrue(r.screenChanged)
        assertTrue(r.evidence.contains("Note posted"))
    }

    @Test
    fun deadButtonFailsAfterOneRetryInsteadOfLooping() = runTest {
        val app = FakeApp()
        val steps = RoutineParser.parse("""{"id":"d","steps":[{"op":"tap","labels":["Dead button"]}]}""").steps
        val r = engine(app).run("d", steps, emptyMap())
        assertFalse(r.ok)
        assertEquals(1, r.failedStep)
        assertTrue(r.reason, r.reason.contains("did not change"))
        assertEquals("exactly two taps: first try and one retry", 2, app.log.count { it.startsWith("Tap") })
    }

    @Test
    fun flakyButtonSucceedsOnTheRetry() = runTest {
        val app = FakeApp()
        val steps = RoutineParser.parse("""{"id":"f","steps":[{"op":"tap","labels":["Flaky"],"expect_any":["New note"]}]}""").steps
        val r = engine(app).run("f", steps, emptyMap())
        assertTrue(r.reason, r.ok)
        assertEquals("notes", app.page)
    }

    @Test
    fun missingLabelFailsClearlyAndOptionalStepsAreSkipped() = runTest {
        val app = FakeApp()
        val steps = RoutineParser.parse(
            """{"id":"m","steps":[{"op":"tap","labels":["Not there"],"optional":true},{"op":"tap","labels":["Nope"]}]}""",
        ).steps
        val r = engine(app).run("m", steps, emptyMap())
        assertFalse(r.ok)
        assertEquals(2, r.failedStep)
        assertTrue(r.reason.contains("Nope"))
    }

    @Test
    fun collectGathersLongTextsOnly() = runTest {
        val app = FakeApp().also { it.page = "notes" }
        val steps = RoutineParser.parse("""{"id":"c","steps":[{"op":"collect","count":1}]}""").steps
        val r = engine(app).run("c", steps, emptyMap())
        assertEquals(listOf("Feed item: a long enough post text to be collected"), r.collected)
    }

    @Test
    fun parserRejectsBadRoutinesWithAReason() {
        listOf(
            "not json", """{"id":"x","steps":[]}""", """{"steps":[{"op":"tap","labels":["a"]}]}""",
            """{"id":"x","steps":[{"op":"fly"}]}""", """{"id":"x","steps":[{"op":"tap"}]}""", """{"id":"x","steps":[{"op":"open_app"}]}""",
        ).forEach { raw ->
            try { RoutineParser.parse(raw); error("accepted: $raw") } catch (e: RoutineParser.Invalid) { assertTrue(e.message!!.isNotBlank()) }
        }
    }
}
