package com.niki914.nexus.agentic.chat

import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltinToolRegistryTest {
    @Test
    fun defaultRegistry_includesLoadSkill() {
        val registry = BuiltinToolRegistry.default()
        val tool = registry.find("load_skill")

        assertNotNull(tool)
        assertEquals(true, tool!!.defaultEnabled)
        val names = registry.all().map { it.name }
        assertTrue(names.contains("create_custom_tool"))
        assertTrue(names.contains("gesture"))
        assertTrue(names.contains("key_event"))
        assertTrue(names.contains("launch_app"))
        assertTrue(names.contains("memorize"))
        assertTrue(names.contains("node_action"))
        assertTrue(names.contains("notify"))
        assertTrue(names.contains("open_uri"))
        assertTrue(names.contains("read_custom_tool"))
        assertTrue(names.contains("screen_content"))
        assertTrue(names.contains("terminal"))
        assertTrue(names.contains("ssh_terminal"))
        assertTrue(names.contains("search_apps"))
        assertTrue(names.contains("load_skill"))
    }
}
