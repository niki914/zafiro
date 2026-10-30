// 保护：ToolPresentation.prettyPrintIfJson 缩进排版；subtitleOf 意图优先裁决与参数降级，command 工具防裸露
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

    @Test
    fun subtitleOf_prefersExplicitIntentOverToolFallback() {
        val args = """{"query":"微信","intent":"检查微信是否安装"}"""
        val result = ToolPresentation.subtitleOf("find_installed_apps", args)
        assertEquals("检查微信是否安装", result)
    }

    @Test
    fun subtitleOf_extractsExplicitIntentForCommandTool() {
        val args = """{"command":"ls -lh","intent":"查看文件大小"}"""
        val result = ToolPresentation.subtitleOf("shell", args)
        assertEquals("查看文件大小", result)
    }

    @Test
    fun subtitleOf_supportsChineseIntentKey() {
        val args = """{"code":"print(1)","意图":"运行测试脚本"}"""
        val result = ToolPresentation.subtitleOf("execute_python", args)
        assertEquals("运行测试脚本", result)
    }

    @Test
    fun subtitleOf_commandToolWithoutIntentReturnsNull() {
        val shellArgs = """{"command":"ls -lh /sdcard"}"""
        assertNull(ToolPresentation.subtitleOf("shell", shellArgs))

        val pythonArgs = """{"code":"import os\nprint(os.listdir('.'))"}"""
        assertNull(ToolPresentation.subtitleOf("execute_python", pythonArgs))
    }

    @Test
    fun subtitleOf_nonCommandToolWithoutIntentFallsBackToArgument() {
        val findAppsArgs = """{"query":"微信"}"""
        assertEquals("微信", ToolPresentation.subtitleOf("find_installed_apps", findAppsArgs))

        val loadSkillArgs = """{"id":"github-pr"}"""
        assertEquals("github-pr", ToolPresentation.subtitleOf("load_skill", loadSkillArgs))

        val launchAppArgs = """{"name":"QQ"}"""
        assertEquals("QQ", ToolPresentation.subtitleOf("launch_app", launchAppArgs))
    }

    @Test
    fun subtitleOf_handlesNullAndMalformedJsonSafely() {
        assertNull(ToolPresentation.subtitleOf("shell", null))
        assertNull(ToolPresentation.subtitleOf("shell", ""))
        assertNull(ToolPresentation.subtitleOf("shell", "{malformed json"))
        assertNull(ToolPresentation.subtitleOf("unknown_tool", """{"unsupported_key":"value"}"""))
    }
}
