package com.niki914.zafiro.chat

import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.impl.PyMetaToolsBuiltin
import com.niki914.zafiro.settings.RuntimeEnvironment
import com.niki914.zafiro.settings.model.RuntimeCustomPyTool
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PyMetaToolsBuiltinTest {

    @After
    fun tearDown() {
        RuntimeEnvironment.clearForTest()
    }

    /** 双身份 fake exec：introspection 请求返回签名 JSON，其余按 runner 语义回 stdout。 */
    private val exec: suspend (code: String, timeoutMs: Long) -> String = { code, _ ->
        if (code.contains("inspect.signature")) INTROSPECTION_OK else "hello"
    }

    private fun gateway() = RuntimeEnvironment.requireBridge().settings as FakeRuntimeSettingsGateway

    private fun newTool() = PyMetaToolsBuiltin(
        exec = exec,
        reservedNames = setOf("terminal"),
    )

    @Test
    fun write_addsPrefixAndCachesReflectedSchema() = runTest {
        installRuntimeSettingsGatewayForTest()

        val result = newTool().invoke(
            BuiltinToolRequest("py_meta_tools", """{"action":"write","name":"web_search","code":"def main(query: str):\\n    print('x')"}""")
        )

        assertTrue(result.ok)
        val saved = gateway().customPyTools.single()
        assertEquals("py_web_search", saved.name)
        assertEquals("Search things.", saved.description)
        assertTrue(saved.schemaJson.contains("\"query\""))
        assertEquals("py_web_search", result.data["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun write_rejectsIntrospectionFailureWithFixableError() = runTest {
        installRuntimeSettingsGatewayForTest(FakeRuntimeSettingsGateway())

        val tool = PyMetaToolsBuiltin(
            exec = { code, _ ->
                if (code.contains("inspect.signature")) {
                    """{"error":"UNANNOTATED_PARAMS","message":"Parameters need basic type annotations (str/int/float/bool): query"}"""
                } else "ok"
            },
            reservedNames = setOf("terminal"),
        )

        val result = tool.invoke(
            BuiltinToolRequest("py_meta_tools", """{"action":"write","name":"py_bad","code":"def main(query):\\n    pass"}""")
        )

        assertFalse(result.ok)
        assertEquals("UNANNOTATED_PARAMS", result.code)
        assertTrue(gateway().customPyTools.isEmpty())
    }

    @Test
    fun write_rejectsReservedName() = runTest {
        installRuntimeSettingsGatewayForTest(FakeRuntimeSettingsGateway())

        val result = newTool().invoke(
            BuiltinToolRequest("py_meta_tools", """{"action":"write","name":"terminal","code":"def main():\\n    pass"}""")
        )

        assertFalse(result.ok)
        assertEquals("RESERVED_NAME", result.code)
    }

    @Test
    fun list_returnsEntriesWithoutCode() = runTest {
        installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway(
                customPyTools = listOf(
                    RuntimeCustomPyTool(name = "py_a", code = "SECRET_CODE", description = "A"),
                )
            )
        )

        val result = newTool().invoke(
            BuiltinToolRequest("py_meta_tools", """{"action":"list"}""")
        )

        assertTrue(result.ok)
        val raw = result.toJsonString()
        assertTrue(raw.contains("py_a"))
        assertFalse(raw.contains("SECRET_CODE"))
    }

    @Test
    fun read_returnsFullCode() = runTest {
        installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway(
                customPyTools = listOf(
                    RuntimeCustomPyTool(name = "py_a", code = "SECRET_CODE", description = "A"),
                )
            )
        )

        val result = newTool().invoke(
            BuiltinToolRequest("py_meta_tools", """{"action":"read","name":"py_a"}""")
        )

        assertTrue(result.ok)
        assertTrue(result.toJsonString().contains("SECRET_CODE"))
    }

    @Test
    fun delete_removesEntry() = runTest {
        installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway(
                customPyTools = listOf(
                    RuntimeCustomPyTool(name = "py_a", code = "x"),
                )
            )
        )

        val result = newTool().invoke(
            BuiltinToolRequest("py_meta_tools", """{"action":"delete","name":"py_a"}""")
        )

        assertTrue(result.ok)
        assertTrue(gateway().customPyTools.isEmpty())
    }

    @Test
    fun test_runsExistingToolCodeThroughHarness() = runTest {
        installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway(
                customPyTools = listOf(
                    RuntimeCustomPyTool(name = "py_a", code = "def main():\\n    print('hello')"),
                )
            )
        )

        var ranCode = ""
        val tool = PyMetaToolsBuiltin(
            exec = { code, _ ->
                ranCode = code
                "hello"
            },
            reservedNames = setOf("terminal"),
        )

        val result = tool.invoke(
            BuiltinToolRequest("py_meta_tools", """{"action":"test","name":"py_a","args":{}}""")
        )

        assertTrue(result.ok)
        assertTrue(ranCode.contains("main(**_args)"))
        assertTrue(ranCode.contains("b64decode"))
        assertNotNull(result.data["stdout"])
    }

    @Test
    fun test_rejectsAmbiguousNameAndCode() = runTest {
        installRuntimeSettingsGatewayForTest(FakeRuntimeSettingsGateway())

        val result = newTool().invoke(
            BuiltinToolRequest(
                "py_meta_tools",
                """{"action":"test","name":"py_a","code":"def main():\\n    pass","args":{}}"""
            )
        )

        assertFalse(result.ok)
        assertEquals("AMBIGUOUS_TARGET", result.code)
    }

    @Test
    fun write_preservesExistingEnabledWhenOmitted() = runTest {
        installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway(
                customPyTools = listOf(
                    RuntimeCustomPyTool(name = "py_a", code = "old", enabled = false),
                )
            )
        )

        newTool().invoke(
            BuiltinToolRequest("py_meta_tools", """{"action":"write","name":"py_a","code":"def main():\\n    pass"}""")
        )

        assertFalse(gateway().customPyTools.single { it.name == "py_a" }.enabled)
    }

    private companion object {
        val INTROSPECTION_OK = """{"description":"Search things.","schema":{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}}"""
    }
}
