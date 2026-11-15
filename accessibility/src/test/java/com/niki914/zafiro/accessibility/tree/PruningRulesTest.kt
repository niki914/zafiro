package com.niki914.zafiro.accessibility.tree

import com.niki914.zafiro.api.screen.NodeInfo
import com.niki914.zafiro.api.screen.Rect
import com.niki914.zafiro.api.screen.SemanticType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PruningRulesTest {

    @Test
    fun mapSemanticTypeMatchesExactAndSuffix() {
        assertEquals(
            SemanticType.BUTTON,
            PruningRules.mapSemanticType("android.widget.Button", null),
        )
        assertEquals(
            SemanticType.INPUT,
            PruningRules.mapSemanticType("android.widget.EditText", null),
        )
        assertEquals(
            SemanticType.CHECKBOX,
            PruningRules.mapSemanticType("android.widget.CheckBox", null),
        )
        assertEquals(
            SemanticType.DIALOG,
            PruningRules.mapSemanticType("com.example.CustomDialogView", null),
        )
        assertEquals(
            SemanticType.LIST_ITEM,
            PruningRules.mapSemanticType("android.view.ViewGroup", SemanticType.LIST),
        )
        assertEquals(
            SemanticType.CONTAINER,
            PruningRules.mapSemanticType("android.widget.FrameLayout", null),
        )
    }

    @Test
    fun isCompletelyOffScreenDetectsBoundsCorrectly() {
        val screenHeight = 2400
        assertTrue(PruningRules.isCompletelyOffScreen(Rect(0, -100, 1080, 0), screenHeight))
        assertTrue(PruningRules.isCompletelyOffScreen(Rect(0, 2400, 1080, 2600), screenHeight))
        assertFalse(PruningRules.isCompletelyOffScreen(Rect(0, 100, 1080, 500), screenHeight))
        assertFalse(PruningRules.isCompletelyOffScreen(Rect(0, -50, 1080, 50), screenHeight))
    }

    @Test
    fun isEmptyShellDetectsEmptyNodes() {
        val emptyNode = NodeInfo(
            index = 0,
            semanticType = SemanticType.CONTAINER,
            text = "",
            contentDesc = "",
            bounds = Rect(0, 0, 100, 100),
            isClickable = false,
            isLongClickable = false,
            isEditable = false,
            isScrollable = false,
            isChecked = false,
            children = emptyList(),
        )
        assertTrue(PruningRules.isEmptyShell(emptyNode))

        val clickableNode = emptyNode.copy(isClickable = true)
        assertFalse(PruningRules.isEmptyShell(clickableNode))

        val textNode = emptyNode.copy(text = "Hello")
        assertFalse(PruningRules.isEmptyShell(textNode))
    }

    @Test
    fun posOfCalculatesPositionSectors() {
        val screenW = 1080
        val screenH = 2400

        // Top left
        assertEquals("top-left", PruningRules.posOf(Rect(50, 50, 150, 150), screenW, screenH))
        // Center
        assertEquals("center", PruningRules.posOf(Rect(450, 1000, 600, 1200), screenW, screenH))
        // Bottom right
        assertEquals("bottom-right", PruningRules.posOf(Rect(800, 2000, 1000, 2200), screenW, screenH))
    }

    @Test
    fun normalizeTextEscapesWhitespaceAndTruncates() {
        assertEquals("line1\\nline2", PruningRules.normalizeText("line1\nline2"))
        val longText = "a".repeat(40)
        assertEquals("a".repeat(30) + "…", PruningRules.normalizeText(longText))
    }
}
