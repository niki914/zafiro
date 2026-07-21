package com.niki914.nexus.store

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IpcJsonMutatorTest {

    @Test
    fun mutate_blankJsonCreatesNestedObject() {
        val result = IpcJsonMutator.mutate(
            json = "   ",
            path = "mcp.servers",
            valueJson = "[]"
        )

        val root = JSONObject(result)
        assertNotNull(root.getJSONObject("mcp").getJSONArray("servers"))
        assertEquals(0, root.getJSONObject("mcp").getJSONArray("servers").length())
    }

    @Test
    fun mutate_invalidJsonFallsBackToEmptyObject() {
        val result = IpcJsonMutator.mutate(
            json = "not-json",
            path = "enabled",
            valueJson = "true"
        )

        assertEquals(true, JSONObject(result).getBoolean("enabled"))
    }

    @Test
    fun mutate_writesArrayObjectBooleanNumberAndStringValues() {
        var json = "{}"
        json = IpcJsonMutator.mutate(json, "values.array", """[1,"two"]""")
        json = IpcJsonMutator.mutate(json, "values.object", """{"name":"nexus"}""")
        json = IpcJsonMutator.mutate(json, "values.boolean", "false")
        json = IpcJsonMutator.mutate(json, "values.number", "42")
        json = IpcJsonMutator.mutate(json, "values.string", """"hello"""")

        val values = JSONObject(json).getJSONObject("values")
        assertEquals(2, values.getJSONArray("array").length())
        assertEquals("nexus", values.getJSONObject("object").getString("name"))
        assertEquals(false, values.getBoolean("boolean"))
        assertEquals(42, values.getInt("number"))
        assertEquals("hello", values.getString("string"))
    }

    @Test
    fun mutate_blankPathThrowsIllegalArgumentException() {
        val error = try {
            IpcJsonMutator.mutate("{}", " .  . ", "true")
            null
        } catch (e: IllegalArgumentException) {
            e
        }

        assertNotNull(error)
        assertEquals("key must not be blank", error!!.message)
    }

    @Test
    fun mutate_invalidValueJsonWritesRawString() {
        val result = IpcJsonMutator.mutate(
            json = "{}",
            path = "headers.Authorization",
            valueJson = "Bearer token"
        )

        assertEquals(
            "Bearer token",
            JSONObject(result).getJSONObject("headers").getString("Authorization")
        )
    }

    @Test
    fun mutate_nullValueWritesJsonNull() {
        val result = IpcJsonMutator.mutate(
            json = "{}",
            path = "optional.value",
            valueJson = "null"
        )

        assertTrue(JSONObject(result).getJSONObject("optional").isNull("value"))
    }
}
