package com.niki914.nexus.agentic.chat.agentic.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function tests for [PruningRules].
 *
 * No Android framework or mocking is required — every test exercises a
 * static method on [PruningRules] or [NodeModel] value types.
 */
class PruningRulesTest {

    @Test
    fun mapSemanticType_buttonClasses() {
        assertEquals(
            SemanticType.BUTTON,
            PruningRules.mapSemanticType("android.widget.Button", null),
        )
        assertEquals(
            SemanticType.BUTTON,
            PruningRules.mapSemanticType("com.google.android.material.button.MaterialButton", null),
        )
    }

    @Test
    fun mapSemanticType_unknownClass() {
        assertEquals(
            SemanticType.CONTAINER,
            PruningRules.mapSemanticType("com.example.CustomWidget", null),
        )
    }

    @Test
    fun mapSemanticType_listItemFromParent() {
        // A LinearLayout inside a LIST should map to LIST_ITEM.
        assertEquals(
            SemanticType.LIST_ITEM,
            PruningRules.mapSemanticType(
                "android.widget.LinearLayout", SemanticType.LIST
            ),
        )
    }

    @Test
    fun isCompletelyOffScreen_partialTop() {
        // bounds bottom is below the top edge and top is above screenHeight
        // -> partially visible, not completely off-screen.
        val bounds = Rect(0, 2300, 1080, 2500)
        assertFalse(PruningRules.isCompletelyOffScreen(bounds, 2400))
    }

    @Test
    fun isCompletelyOffScreen_above() {
        // Entire bounds above the visible area (bottom <= 0).
        val bounds = Rect(0, -200, 1080, -50)
        assertTrue(PruningRules.isCompletelyOffScreen(bounds, 2400))
    }

    @Test
    fun isCompletelyOffScreen_below() {
        // Entire bounds below the visible area (top >= screenHeight).
        val bounds = Rect(0, 2500, 1080, 2600)
        assertTrue(PruningRules.isCompletelyOffScreen(bounds, 2400))
    }

    @Test
    fun isEmptyShell_allFalse_noChildren() {
        val node = NodeInfo(
            index = 0,
            semanticType = SemanticType.CONTAINER,
            text = "",
            contentDesc = "",
            bounds = Rect(0, 0, 0, 0),
            isClickable = false,
            isLongClickable = false,
            isEditable = false,
            isScrollable = false,
            isChecked = false,
            children = emptyList(),
            moreSummary = emptyList(),
        )
        assertTrue(PruningRules.isEmptyShell(node))
    }

    @Test
    fun isEmptyShell_hasText() {
        val node = NodeInfo(
            index = 0,
            semanticType = SemanticType.CONTAINER,
            text = "hello",
            contentDesc = "",
            bounds = Rect(0, 0, 0, 0),
            isClickable = false,
            isLongClickable = false,
            isEditable = false,
            isScrollable = false,
            isChecked = false,
            children = emptyList(),
            moreSummary = emptyList(),
        )
        assertFalse(PruningRules.isEmptyShell(node))
    }

    @Test
    fun normalizeText_newline() {
        assertEquals("a\\nb", PruningRules.normalizeText("a\nb"))
    }

    @Test
    fun needsQuoting_various() {
        // Contains colon -> needs quoting.
        assertTrue(PruningRules.needsQuoting("key: value"))
        // Plain alphanumeric -> no quoting needed.
        assertFalse(PruningRules.needsQuoting("hello"))
        // Empty string -> no quoting needed.
        assertFalse(PruningRules.needsQuoting(""))
        // Contains whitespace -> needs quoting.
        assertTrue(PruningRules.needsQuoting("a b"))
    }

    @Test
    fun shouldCollapse_positive() {
        val node = NodeInfo(
            index = 0,
            semanticType = SemanticType.CONTAINER,
            text = "",
            contentDesc = "",
            bounds = Rect(0, 0, 0, 0),
            isClickable = false,
            isLongClickable = false,
            isEditable = false,
            isScrollable = false,
            isChecked = false,
            children = emptyList(),
            moreSummary = emptyList(),
        )
        assertTrue(PruningRules.shouldCollapse(node, 1))
    }

    @Test
    fun shouldCollapse_negative_hasText() {
        val node = NodeInfo(
            index = 0,
            semanticType = SemanticType.CONTAINER,
            text = "hello",
            contentDesc = "",
            bounds = Rect(0, 0, 0, 0),
            isClickable = false,
            isLongClickable = false,
            isEditable = false,
            isScrollable = false,
            isChecked = false,
            children = emptyList(),
            moreSummary = emptyList(),
        )
        assertFalse(PruningRules.shouldCollapse(node, 1))
    }

    @Test
    fun shouldCollapse_negative_depth0() {
        val node = NodeInfo(
            index = 0,
            semanticType = SemanticType.CONTAINER,
            text = "",
            contentDesc = "",
            bounds = Rect(0, 0, 0, 0),
            isClickable = false,
            isLongClickable = false,
            isEditable = false,
            isScrollable = false,
            isChecked = false,
            children = emptyList(),
            moreSummary = emptyList(),
        )
        assertFalse(PruningRules.shouldCollapse(node, 0))
    }

    @Test
    fun shouldCollapse_negative_clickable() {
        val node = NodeInfo(
            index = 0,
            semanticType = SemanticType.CONTAINER,
            text = "",
            contentDesc = "",
            bounds = Rect(0, 0, 0, 0),
            isClickable = true,
            isLongClickable = false,
            isEditable = false,
            isScrollable = false,
            isChecked = false,
            children = emptyList(),
            moreSummary = emptyList(),
        )
        assertFalse(PruningRules.shouldCollapse(node, 1))
    }

    @Test
    fun posOf_topLeft() {
        assertEquals("top-left", PruningRules.posOf(Rect(50, 100, 150, 300), 1080, 2400))
    }

    @Test
    fun posOf_topRight() {
        assertEquals("top-right", PruningRules.posOf(Rect(850, 100, 950, 300), 1080, 2400))
    }

    @Test
    fun posOf_bottomLeft() {
        assertEquals("bottom-left", PruningRules.posOf(Rect(50, 2100, 150, 2300), 1080, 2400))
    }

    @Test
    fun posOf_bottomRight() {
        assertEquals("bottom-right", PruningRules.posOf(Rect(850, 2100, 950, 2300), 1080, 2400))
    }

    @Test
    fun posOf_center() {
        assertEquals("center", PruningRules.posOf(Rect(500, 1100, 580, 1300), 1080, 2400))
    }

    @Test
    fun posOf_top() {
        assertEquals("top", PruningRules.posOf(Rect(500, 100, 580, 300), 1080, 2400))
    }

    @Test
    fun posOf_left() {
        assertEquals("left", PruningRules.posOf(Rect(50, 1100, 150, 1300), 1080, 2400))
    }

    @Test
    fun posOf_right() {
        assertEquals("right", PruningRules.posOf(Rect(850, 1100, 950, 1300), 1080, 2400))
    }

    @Test
    fun posOf_bottom() {
        assertEquals("bottom", PruningRules.posOf(Rect(500, 2100, 580, 2300), 1080, 2400))
    }

    @Test
    fun buildMoreSummary_withText() {
        assertEquals(
            "hello world",
            PruningRules.buildMoreSummary(
                NodeInfo(
                    index = 0,
                    semanticType = SemanticType.TEXT,
                    text = "hello world",
                    contentDesc = "",
                    bounds = Rect(0, 0, 0, 0),
                    isClickable = false,
                    isLongClickable = false,
                    isEditable = false,
                    isScrollable = false,
                    isChecked = false,
                    children = emptyList(),
                    moreSummary = emptyList(),
                )
            )
        )
    }

    @Test
    fun buildMoreSummary_fallbackToContentDesc() {
        assertEquals(
            "desc",
            PruningRules.buildMoreSummary(
                NodeInfo(
                    index = 0,
                    semanticType = SemanticType.TEXT,
                    text = "",
                    contentDesc = "desc",
                    bounds = Rect(0, 0, 0, 0),
                    isClickable = false,
                    isLongClickable = false,
                    isEditable = false,
                    isScrollable = false,
                    isChecked = false,
                    children = emptyList(),
                    moreSummary = emptyList(),
                )
            )
        )
    }

    @Test
    fun buildMoreSummary_empty() {
        assertEquals(
            "(empty)",
            PruningRules.buildMoreSummary(
                NodeInfo(
                    index = 0,
                    semanticType = SemanticType.TEXT,
                    text = "",
                    contentDesc = "",
                    bounds = Rect(0, 0, 0, 0),
                    isClickable = false,
                    isLongClickable = false,
                    isEditable = false,
                    isScrollable = false,
                    isChecked = false,
                    children = emptyList(),
                    moreSummary = emptyList(),
                )
            )
        )
    }

    @Test
    fun buildMoreSummary_truncated() {
        val result = PruningRules.buildMoreSummary(
            NodeInfo(
                index = 0,
                semanticType = SemanticType.TEXT,
                text = "this is a very long text that exceeds twenty chars",
                contentDesc = "",
                bounds = Rect(0, 0, 0, 0),
                isClickable = false,
                isLongClickable = false,
                isEditable = false,
                isScrollable = false,
                isChecked = false,
                children = emptyList(),
                moreSummary = emptyList(),
            )
        )
        assertTrue(result.startsWith("this is a very long "))
        assertTrue(result.contains("…"))
    }
}
