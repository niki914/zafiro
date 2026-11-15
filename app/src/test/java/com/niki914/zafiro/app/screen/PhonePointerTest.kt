// 保护：主屏点击要等指针飞到才动手；滚动和滑动要在到达起点后与真实操作同时进行。
// 藏着的指针在目标点出现。节点找不到时不起飞。
package com.niki914.zafiro.app.screen

import com.niki914.zafiro.api.screen.ScreenAction
import com.niki914.zafiro.api.screen.SemanticToken
import com.niki914.zafiro.api.screen.Target
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

class PhonePointerTest {

    @Test
    fun visiblePointerFinishesTheFlyBeforeTheAction() = runTest {
        val fake = FakeMotion(showing = true, blockFly = true)
        val play = PhonePointerPlay { fake }
        var acted = false
        val job = launch {
            play.afterArrival(8f, 9f) { acted = true }
        }
        testScheduler.runCurrent()
        assertEquals(listOf("fly:8.0,9.0"), fake.events)
        assertFalse(acted)
        fake.releaseFly()
        job.join()
        assertTrue(acted)
    }

    @Test
    fun hiddenPointerAppearsAtTheTarget() = runTest {
        val fake = FakeMotion(showing = false)
        val play = PhonePointerPlay { fake }
        var acted = false
        play.afterArrival(3f, 4f) { acted = true }
        assertEquals(listOf("show:3.0,4.0"), fake.events)
        assertTrue(acted)
    }

    @Test
    fun swipeStartsTheStrokeWithTheActionAndWaitsForBoth() = runTest {
        val fake = FakeMotion(showing = true)
        val play = PhonePointerPlay { fake }
        var returned = false
        val job = launch {
            play.glide(1f, 2f, 1f, 20f, 300L) {
                assertTrue(fake.events.contains("stroke:1.0,2.0,1.0,20.0,300"))
                "done"
            }
            returned = true
        }
        testScheduler.runCurrent()
        assertEquals(
            listOf("fly:1.0,2.0", "stroke:1.0,2.0,1.0,20.0,300"),
            fake.events,
        )
        assertFalse(returned)
        fake.releaseStroke()
        job.join()
        assertTrue(returned)
    }

    @Test
    fun missingPointerStillRunsTheAction() = runTest {
        val play = PhonePointerPlay { null }
        var taps = 0
        var swipes = 0
        play.afterArrival(1f, 2f) { taps += 1 }
        play.glide(1f, 2f, 3f, 4f, 80L) { swipes += 1 }
        assertEquals(1, taps)
        assertEquals(1, swipes)
    }

    @Test
    fun scrollIsAVerticalStrokeAndAMissingNodeDoesNotMove() {
        val node = Target.Node(SemanticToken("v", 1))
        assertEquals(
            PhoneCue.Glide(10, 400, 10, 200, 300L),
            phoneCue(ScreenAction.Scroll(node, forward = true)) { 10 to 400 },
        )
        assertEquals(
            PhoneCue.Glide(10, 50, 10, 0, 300L),
            phoneCue(ScreenAction.Scroll(node, forward = true)) { 10 to 50 },
        )
        assertEquals(
            PhoneCue.Glide(10, 400, 10, 600, 300L),
            phoneCue(ScreenAction.Scroll(node, forward = false)) { 10 to 400 },
        )
    }

    @Test
    fun missingNodeIsRejectedBeforeThePointerMoves() {
        val node = Target.Node(SemanticToken("v", 1))
        assertEquals(PhoneCue.Reject("NODE_STALE"), phoneCue(ScreenAction.Tap(node)) { null })
        assertEquals(
            PhoneCue.Reject("NODE_STALE"),
            phoneCue(ScreenAction.Scroll(node, forward = true)) { null },
        )
    }

    @Test
    fun swipeKeepsItsOwnPathAndAPointSetTextDoesNotFly() {
        assertEquals(
            PhoneCue.Glide(1, 2, 9, 8, 80L),
            phoneCue(ScreenAction.Swipe(1, 2, 9, 8, 80L)) { error("swipe has its own points") },
        )
        assertEquals(
            PhoneCue.Skip,
            phoneCue(ScreenAction.SetText(Target.Point(1, 2), "hi")) {
                error("a point cannot take text")
            },
        )
        assertEquals(
            PhoneCue.Skip,
            phoneCue(ScreenAction.Key(4)) { error("keys have no pointer") },
        )
    }

    private class FakeMotion(
        showing: Boolean,
        private val blockFly: Boolean = false,
    ) : PointerMotion {
        val events = mutableListOf<String>()
        private var showingNow = showing
        private var flyGate: Continuation<Unit>? = null
        private var strokeGate: Continuation<Unit>? = null

        override val isShowing: Boolean get() = showingNow

        override fun show(x: Float, y: Float) {
            events += "show:$x,$y"
            showingNow = true
        }

        override suspend fun flyTo(x: Float, y: Float) {
            events += "fly:$x,$y"
            if (blockFly) {
                suspendCancellableCoroutine { flyGate = it }
            }
        }

        override suspend fun stroke(
            x1: Float,
            y1: Float,
            x2: Float,
            y2: Float,
            durationMs: Long,
        ) {
            events += "stroke:$x1,$y1,$x2,$y2,$durationMs"
            suspendCancellableCoroutine { strokeGate = it }
        }

        override fun hide() {
            events += "hide"
            showingNow = false
        }

        fun releaseFly() {
            flyGate?.resume(Unit)
        }

        fun releaseStroke() {
            strokeGate?.resume(Unit)
        }
    }
}
