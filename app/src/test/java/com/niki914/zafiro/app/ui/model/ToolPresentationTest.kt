// 保护：ToolPresentation.prettyPrintIfJson 对合法 JSON 对象/数组进行缩进排版，对非 JSON、标量或超限字符串安全原样回退
package com.niki914.zafiro.app.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolPresentationTest {

    @Test
    fun prettyPrintIfJson_formatsJsonObject() {
        val raw = """{"ok":true,"code":"OK","message":"success"}"""
        val expected = """{
  "ok": true,
  "code": "OK",
  "message": "success"
}"""
        val result = ToolPresentation.prettyPrintIfJson(raw)
        assertEquals(expected, result)
    }

    @Test
    fun prettyPrintIfJson_formatsJsonArray() {
        val raw = """[{"name":"app1"},{"name":"app2"}]"""
        val expected = """[
  {
    "name": "app1"
  },
  {
    "name": "app2"
  }
]"""
        val result = ToolPresentation.prettyPrintIfJson(raw)
        assertEquals(expected, result)
    }

    @Test
    fun prettyPrintIfJson_returnsOriginalOnPlainText() {
        val raw = "Hello world\nThis is not json"
        assertEquals(raw, ToolPresentation.prettyPrintIfJson(raw))
    }

    @Test
    fun prettyPrintIfJson_returnsOriginalOnMalformedJson() {
        val raw = """{"unclosed":"""
        assertEquals(raw, ToolPresentation.prettyPrintIfJson(raw))
    }

    @Test
    fun prettyPrintIfJson_returnsOriginalOnJsonPrimitive() {
        val raw = """"just a string""""
        assertEquals(raw, ToolPresentation.prettyPrintIfJson(raw))
    }

    @Test
    fun prettyPrintIfJson_returnsOriginalWhenExceedingMaxLength() {
        val raw = """{"data":"${"x".repeat(100)}"}"""
        val result = ToolPresentation.prettyPrintIfJson(raw, maxChars = 50)
        assertEquals(raw, result)
    }

    @Test
    fun prettyPrintIfJson_handlesNullAndBlank() {
        assertNull(ToolPresentation.prettyPrintIfJson(null))
        assertEquals("", ToolPresentation.prettyPrintIfJson(""))
        assertEquals("   ", ToolPresentation.prettyPrintIfJson("   "))
    }
}
