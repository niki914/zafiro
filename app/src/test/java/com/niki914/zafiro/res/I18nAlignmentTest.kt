package com.niki914.zafiro.res

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 多语言 strings 键对齐守护：所有 locale 的键集合必须与默认（values）完全一致。
 * 防止新增文案只写了一个 locale，其它语言静默回落（或直接缺资源崩溃）。
 * 纯 JVM 测试：直接解析 src/main/res 下的 xml，不需要 Robolectric。
 */
class I18nAlignmentTest {

    private val localeFiles = listOf(
        "values/strings.xml",
        "values-en/strings.xml",
        "values-b+zh+Hant/strings.xml",
        "values-es/strings.xml",
        "values-ja/strings.xml",
    )

    private fun stringNames(relativePath: String): Set<String> {
        val file = File(appResDir(), relativePath)
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(file)
        val nodes = document.getElementsByTagName("string")
        val names = mutableSetOf<String>()
        for (index in 0 until nodes.length) {
            names += nodes.item(index).attributes.getNamedItem("name").nodeValue
        }
        return names
    }

    @Test
    fun `all locales declare the same string keys`() {
        val keySets = localeFiles.associateWith(::stringNames)
        val reference = keySets.getValue(localeFiles.first())
        localeFiles.drop(1).forEach { path ->
            val keySet = keySets.getValue(path)
            val missing = reference - keySet
            val extra = keySet - reference
            assertTrue("$path is missing keys: ${missing.sorted()}", missing.isEmpty())
            assertTrue("$path has unexpected keys: ${extra.sorted()}", extra.isEmpty())
            assertEquals(reference, keySet)
        }
    }

    private companion object {
        fun appResDir(): File {
            var dir: File? = File(System.getProperty("user.dir", "."))
            repeat(5) {
                val candidate = File(dir, "app/src/main/res")
                if (candidate.isDirectory) return candidate
                dir = dir?.parentFile
            }
            error(
                "app/src/main/res not found upward from " +
                        System.getProperty("user.dir"),
            )
        }
    }
}
