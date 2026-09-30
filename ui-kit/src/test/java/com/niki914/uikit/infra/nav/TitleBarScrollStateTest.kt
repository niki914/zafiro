package com.niki914.uikit.infra.nav

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleBarScrollStateTest {

    private val thresholdPx = 96f

    @Test
    fun `stays expanded while offset is under the threshold`() {
        val state = TitleBarScrollState()

        state.addScroll(scrolledDownPx = 95f, thresholdPx = thresholdPx)

        assertFalse(state.collapsed)
    }

    @Test
    fun `stays expanded at exactly the threshold`() {
        val state = TitleBarScrollState()

        state.addScroll(scrolledDownPx = 96f, thresholdPx = thresholdPx)

        assertFalse(state.collapsed)
    }

    @Test
    fun `collapses once the accumulated offset passes the threshold`() {
        val state = TitleBarScrollState()

        state.addScroll(scrolledDownPx = 60f, thresholdPx = thresholdPx)
        state.addScroll(scrolledDownPx = 60f, thresholdPx = thresholdPx)

        assertTrue(state.collapsed)
    }

    @Test
    fun `expands again when scrolled back to the top`() {
        val state = TitleBarScrollState()

        state.addScroll(scrolledDownPx = 200f, thresholdPx = thresholdPx)
        assertTrue(state.collapsed)

        state.addScroll(scrolledDownPx = -200f, thresholdPx = thresholdPx)

        assertFalse(state.collapsed)
    }

    @Test
    fun `clamps at zero so overscrolling past the top leaves no negative offset`() {
        val state = TitleBarScrollState()

        state.addScroll(scrolledDownPx = 200f, thresholdPx = thresholdPx)
        state.addScroll(scrolledDownPx = -500f, thresholdPx = thresholdPx)
        assertFalse(state.collapsed)

        // 若溢出的负值未被钳制，这次滚动会被抵消掉而保持展开。
        state.addScroll(scrolledDownPx = 97f, thresholdPx = thresholdPx)

        assertTrue(state.collapsed)
    }

    @Test
    fun `zero delta never changes the collapsed state`() {
        val state = TitleBarScrollState()

        state.addScroll(scrolledDownPx = 200f, thresholdPx = thresholdPx)
        state.addScroll(scrolledDownPx = 0f, thresholdPx = thresholdPx)

        assertTrue(state.collapsed)
    }

    @Test
    fun `accumulated deltas never override a page-reported expanded signal`() {
        val state = TitleBarScrollState()

        // Home 场景：列表停在顶部，但工具结果框自带的滚动子树不断上报增量。
        state.setFromPage(isCollapsed = false)
        state.addScroll(scrolledDownPx = 5000f, thresholdPx = 0f)

        assertFalse(state.collapsed)
    }

    @Test
    fun `accumulated deltas never override a page-reported collapsed signal`() {
        val state = TitleBarScrollState()

        // Home 场景：列表停在中部，但程序化定位不会产生增量，不能把折叠态收回去。
        state.setFromPage(isCollapsed = true)
        state.addScroll(scrolledDownPx = -5000f, thresholdPx = 0f)

        assertTrue(state.collapsed)
    }
}
