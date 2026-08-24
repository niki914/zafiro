package com.niki914.nexus.agentic.chat

import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class BuiltinToolRegistryTest {
    @Test
    fun defaultRegistry_includesLoadSkill() {
        val registry = BuiltinToolRegistry.default()
        val tool = registry.find("load_skill")

        assertNotNull(tool)
        assertEquals(true, tool!!.defaultEnabled)
    }
}
