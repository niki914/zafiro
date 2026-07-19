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
            moreCount = 0,
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
            moreCount = 0,
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
}
