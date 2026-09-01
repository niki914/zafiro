package com.niki914.zafiro.chat

import com.niki914.zafiro.chat.agentic.python.CustomPyToolExecutor
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomPyToolExecutorTest {

    private val tool = com.niki914.zafiro.chat.LocalTool.Py(
        name = "py_echo",
        description = "echo",
        code = "def main(text):\\n    print(text)",
        inputSchemaJson = null,
    )

    @Test
    fun execute_wrapsStdoutInOkJson() = runTest {
        val executor = CustomPyToolExecutor(exec = { code, _ ->
            assertTrue(code.contains("main(**_args)"))
            "hello"
        })

        val json = Json.parseToJsonElement(executor.execute(tool, "{\"text\":\"x\"}")).jsonObject

        assertTrue(json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("py_echo", json["tool"]!!.jsonPrimitive.content)
        assertEquals("hello", json["stdout"]!!.jsonPrimitive.content)
    }

    @Test
    fun execute_blankArgumentsTreatedAsEmptyObject() = runTest {
        val executor = CustomPyToolExecutor(exec = { _, _ -> "ok" })

        val json = Json.parseToJsonElement(executor.execute(tool, "")).jsonObject

        assertTrue(json["ok"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun execute_runtimeError_mapsToPythonErrorFailure() = runTest {
        val executor = CustomPyToolExecutor(exec = { _, _ -> throw IllegalStateException("boom") })

        val json = Json.parseToJsonElement(executor.execute(tool, "{}")).jsonObject

        assertFalse(json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("PYTHON_ERROR", json["code"]!!.jsonPrimitive.content)
        assertEquals("boom", json["message"]!!.jsonPrimitive.content)
    }

    @Test
    fun execute_timeout_mapsToTimeoutFailure() = runTest {
        val executor = CustomPyToolExecutor(exec = { _, _ ->
            withTimeout(1) { kotlinx.coroutines.delay(5_000) }
            ""
        })

        val json = Json.parseToJsonElement(executor.execute(tool, "{}")).jsonObject

        assertFalse(json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("TIMEOUT", json["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun execute_invalidArgumentsJsonFallsBackToEmptyObject() = runTest {
        var received = ""
        val spyExecutor = CustomPyToolExecutor(exec = { code, _ ->
            received = code
            "ok"
        })

        spyExecutor.execute(tool, "not-json{")
        // "{}" base64 = e30=
        assertTrue(received.contains("e30="))
    }
}
