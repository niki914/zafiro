package com.niki914.zafiro.chat

import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolSettingsManager
import com.niki914.zafiro.settings.RuntimeEnvironment
import com.niki914.zafiro.settings.model.RuntimeBuiltinToolSetting
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltinToolSettingsManagerTest {
    private val manager = BuiltinToolSettingsManager()

    @After
    fun tearDown() {
        RuntimeEnvironment.clearForTest()
    }

    @Test
    fun load_readsThroughRuntimeSettingsGateway() = runTest {
        val gateway =
            installRuntimeSettingsGatewayForTest()

        val items = manager.load()

        assertEquals(
            listOf("load_skill", "memory", "notify", "pytools", "terminal"),
            items.map { it.name }.sorted()
        )
        assertTrue(items.all { it.enabled })
        assertEquals(0, gateway.writeCount)
    }

    @Test
    fun setEnabled_writesThroughRuntimeSettingsGateway() = runTest {
        val gateway =
            installRuntimeSettingsGatewayForTest()

        val result = manager.setEnabled(
            name = "pytools",
            enabled = true,
        )

        assertTrue(result.ok)
        assertEquals("OK", result.code)
        assertTrue(result.data["available_next_turn"]!!.jsonPrimitive.boolean)
        assertEquals("pytools", result.data["name"]!!.jsonPrimitive.content)
        assertTrue(result.data["enabled"]!!.jsonPrimitive.boolean)
        assertEquals(1, gateway.writeCount)
        assertTrue(
            gateway.builtinTools
                .single { it.name == "pytools" }
                .enabled
        )
    }

    @Test
    fun setEnabled_rejectsUnknownBuiltinWithoutWriting() = runTest {
        val gateway =
            installRuntimeSettingsGatewayForTest()

        val result = manager.setEnabled(
            name = "unknown_tool",
            enabled = true,
        )

        assertFalse(result.ok)
        assertEquals("UNKNOWN_BUILTIN_TOOL", result.code)
        assertEquals(0, gateway.writeCount)
    }

    @Test
    fun setEnabled_acceptsTerminalBuiltin() = runTest {
        val gateway =
            installRuntimeSettingsGatewayForTest()

        val result = manager.setEnabled(
            name = "terminal",
            enabled = false,
        )

        assertTrue(result.ok)
        assertEquals("terminal", result.data["name"]!!.jsonPrimitive.content)
        assertFalse(result.data["enabled"]!!.jsonPrimitive.boolean)
        assertFalse(gateway.builtinTools.single { it.name == "terminal" }.enabled)
    }

    @Test
    fun setEnabled_preservesOtherBuiltinSettings() = runTest {
        val gateway =
            installRuntimeSettingsGatewayForTest(
                FakeRuntimeSettingsGateway(
                    builtinTools = listOf(
                        RuntimeBuiltinToolSetting(
                            "pytools",
                            "Manage persistent Python tools.",
                            enabled = false
                        ),
                        RuntimeBuiltinToolSetting(
                            "legacy_builtin",
                            "Legacy builtin.",
                            enabled = true
                        ),
                    )
                )
            )

        manager.setEnabled(
            name = "pytools",
            enabled = true,
        )

        assertTrue(
            gateway.builtinTools
                .single { it.name == "pytools" }
                .enabled
        )
        assertTrue(
            gateway.builtinTools
                .single { it.name == "legacy_builtin" }
                .enabled
        )
    }
}
