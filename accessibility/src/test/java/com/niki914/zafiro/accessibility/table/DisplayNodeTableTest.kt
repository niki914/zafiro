package com.niki914.zafiro.accessibility.table

import com.niki914.zafiro.api.screen.NodeInfo
import com.niki914.zafiro.api.screen.Rect
import com.niki914.zafiro.api.screen.SemanticToken
import com.niki914.zafiro.api.screen.SemanticType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayNodeTableTest {

    private fun createDummyNode(index: Int, text: String): NodeInfo {
        return NodeInfo(
            index = index,
            semanticType = SemanticType.BUTTON,
            text = text,
            contentDesc = "",
            bounds = Rect(0, 0, 100, 50),
            isClickable = true,
            isLongClickable = false,
            isEditable = false,
            isScrollable = false,
            isChecked = false,
            children = emptyList(),
        )
    }

    @Test
    fun rememberGeneratesVersionAndResolvesMatchingToken() {
        val table = DisplayNodeTable()
        val nodes = listOf(createDummyNode(0, "Confirm"), createDummyNode(1, "Cancel"))

        val version = table.remember(nodes)
        assertEquals(12, version.length)
        assertEquals(2, table.nodeCount)

        val resolved = table.resolve(SemanticToken(version, 0))
        assertNotNull(resolved)
        assertEquals("Confirm", resolved?.text)

        val resolvedCancel = table.resolve(SemanticToken(version, 1))
        assertNotNull(resolvedCancel)
        assertEquals("Cancel", resolvedCancel?.text)
    }

    @Test
    fun resolveReturnsNullWhenVersionMismatches() {
        val table = DisplayNodeTable()
        val nodes = listOf(createDummyNode(0, "Submit"))
        val version = table.remember(nodes)

        val staleToken = SemanticToken("old_version", 0)
        assertNull(table.resolve(staleToken))

        val matchingToken = SemanticToken(version, 0)
        assertNotNull(table.resolve(matchingToken))
    }

    @Test
    fun separateTablesDoNotInterfere() {
        val mainScreenTable = DisplayNodeTable()
        val virtualScreenTable = DisplayNodeTable()

        val mainNodes = listOf(createDummyNode(0, "Main Button"))
        val vdNodes = listOf(createDummyNode(0, "VD Button"))

        val mainVersion = mainScreenTable.remember(mainNodes)
        val vdVersion = virtualScreenTable.remember(vdNodes)

        assertNotEquals(mainVersion, vdVersion)

        // 主屏解析主屏 Token 成功，解析虚拟屏 Token 失败
        assertEquals("Main Button", mainScreenTable.resolve(SemanticToken(mainVersion, 0))?.text)
        assertNull(mainScreenTable.resolve(SemanticToken(vdVersion, 0)))

        // 虚拟屏解析虚拟屏 Token 成功，解析主屏 Token 失败
        assertEquals("VD Button", virtualScreenTable.resolve(SemanticToken(vdVersion, 0))?.text)
        assertNull(virtualScreenTable.resolve(SemanticToken(mainVersion, 0)))
    }
}
