package com.niki914.uikit.infra.scrollbar

import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquidScrollbarGeometryTest {

    private val density = Density(density = 2.0f) // 1dp = 2px
    private val viewportHeightPx = 1000f
    private val trackHeightPx = 800f

    @Test
    fun `hidden when content fits within viewport without overscroll`() {
        val geometry = calculateThumbGeometry(
            viewportHeightPx = viewportHeightPx,
            contentHeightPx = 800f,
            scrollOffsetPx = 0f,
            maxScrollPx = 0f,
            overscrollOffsetPx = 0f,
            trackHeightPx = trackHeightPx,
            density = density,
        )

        assertFalse(geometry.isVisible)
    }

    @Test
    fun `in-bounds scroll correctly positions thumb between bounds`() {
        val contentHeightPx = 2000f
        val maxScrollPx = 1000f
        // ratio = 1000 / 2000 = 0.5 -> baseLength = 800 * 0.5 = 400px
        val expectedBaseLen = 400f

        // 1. 位于顶部 (scrollOffset = 0)
        val topGeometry = calculateThumbGeometry(
            viewportHeightPx = viewportHeightPx,
            contentHeightPx = contentHeightPx,
            scrollOffsetPx = 0f,
            maxScrollPx = maxScrollPx,
            overscrollOffsetPx = 0f,
            trackHeightPx = trackHeightPx,
            density = density,
        )
        assertTrue(topGeometry.isVisible)
        assertEquals(0f, topGeometry.topPx, 0.01f)
        assertEquals(expectedBaseLen, topGeometry.lengthPx, 0.01f)

        // 2. 位于正中间 (scrollOffset = 500)
        val midGeometry = calculateThumbGeometry(
            viewportHeightPx = viewportHeightPx,
            contentHeightPx = contentHeightPx,
            scrollOffsetPx = 500f,
            maxScrollPx = maxScrollPx,
            overscrollOffsetPx = 0f,
            trackHeightPx = trackHeightPx,
            density = density,
        )
        val expectedMidTop = 0.5f * (trackHeightPx - expectedBaseLen) // 200px
        assertEquals(expectedMidTop, midGeometry.topPx, 0.01f)
        assertEquals(expectedBaseLen, midGeometry.lengthPx, 0.01f)

        // 3. 位于底部 (scrollOffset = 1000)
        val bottomGeometry = calculateThumbGeometry(
            viewportHeightPx = viewportHeightPx,
            contentHeightPx = contentHeightPx,
            scrollOffsetPx = 1000f,
            maxScrollPx = maxScrollPx,
            overscrollOffsetPx = 0f,
            trackHeightPx = trackHeightPx,
            density = density,
        )
        val expectedBottomTop = trackHeightPx - expectedBaseLen // 400px
        assertEquals(expectedBottomTop, bottomGeometry.topPx, 0.01f)
        assertEquals(expectedBaseLen, bottomGeometry.lengthPx, 0.01f)
    }

    @Test
    fun `top overscroll pins top to zero and compresses bottom end upward`() {
        val contentHeightPx = 2000f
        val maxScrollPx = 1000f
        val baseLengthPx = 400f
        val overscrollPx = 100f

        val geometry = calculateThumbGeometry(
            viewportHeightPx = viewportHeightPx,
            contentHeightPx = contentHeightPx,
            scrollOffsetPx = 0f,
            maxScrollPx = maxScrollPx,
            overscrollOffsetPx = overscrollPx, // 触顶下拉越界 100px
            trackHeightPx = trackHeightPx,
            density = density,
        )

        assertTrue(geometry.isVisible)
        // 顶部必须死锁在 0
        assertEquals(0f, geometry.topPx, 0.01f)
        // 底端收缩：400 - 100 * 0.38 = 362px
        val expectedLen = baseLengthPx - overscrollPx * 0.38f
        assertEquals(expectedLen, geometry.lengthPx, 0.01f)
    }

    @Test
    fun `top overscroll clamps at min thumb length under extreme stretch`() {
        val contentHeightPx = 2000f
        val minLenPx = 18f * 2f // 36px (18dp * density 2.0)
        val extremeOverscrollPx = 2000f

        val geometry = calculateThumbGeometry(
            viewportHeightPx = viewportHeightPx,
            contentHeightPx = contentHeightPx,
            scrollOffsetPx = 0f,
            maxScrollPx = 1000f,
            overscrollOffsetPx = extremeOverscrollPx,
            trackHeightPx = trackHeightPx,
            density = density,
        )

        assertEquals(0f, geometry.topPx, 0.01f)
        assertEquals(minLenPx, geometry.lengthPx, 0.01f)
    }

    @Test
    fun `bottom overscroll pins bottom to track edge and compresses top end downward`() {
        val contentHeightPx = 2000f
        val maxScrollPx = 1000f
        val baseLengthPx = 400f
        val overscrollPx = -100f // 触底上拉越界 100px

        val geometry = calculateThumbGeometry(
            viewportHeightPx = viewportHeightPx,
            contentHeightPx = contentHeightPx,
            scrollOffsetPx = maxScrollPx,
            maxScrollPx = maxScrollPx,
            overscrollOffsetPx = overscrollPx,
            trackHeightPx = trackHeightPx,
            density = density,
        )

        assertTrue(geometry.isVisible)
        val expectedLen = baseLengthPx - 100f * 0.38f // 362px
        assertEquals(expectedLen, geometry.lengthPx, 0.01f)
        // 底端贴合轨道底端：top = trackHeightPx - lengthPx
        val expectedTop = trackHeightPx - expectedLen
        assertEquals(expectedTop, geometry.topPx, 0.01f)
    }
}
