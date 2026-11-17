// 保护：网页预览 URL 分类与本地私有文件到虚拟同源 HTTPS 映射规则，避免协议误判或路径拼接畸形
package com.niki914.zafiro.app.ui.viewer

import com.niki914.zafiro.app.ui.chat.composable.text.isWebOrHtmlPreviewable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WebPreviewTargetResolverTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun recognizesHttpAndHttpsAsPreviewable() {
        assertTrue(isWebOrHtmlPreviewable("https://example.com/index.html"))
        assertTrue(isWebOrHtmlPreviewable("http://example.com"))
        assertTrue(isWebOrHtmlPreviewable("  https://google.com  "))
    }

    @Test
    fun recognizesLocalHtmlFileAsPreviewable() {
        assertTrue(isWebOrHtmlPreviewable("file:///sdcard/page.html"))
        assertTrue(isWebOrHtmlPreviewable("/data/user/0/com.niki914.zafiro/files/index.htm"))
    }

    @Test
    fun rejectsNonWebSchemesAndNonHtmlLocalFiles() {
        assertFalse(isWebOrHtmlPreviewable("tel:10086"))
        assertFalse(isWebOrHtmlPreviewable("mailto:someone@example.com"))
        assertFalse(isWebOrHtmlPreviewable("/data/user/0/com.niki914.zafiro/files/image.png"))
        assertFalse(isWebOrHtmlPreviewable("bilibili://video/123"))
    }

    @Test
    fun mapsFilesDirFileToAppAssetsHttps() {
        val root = tempFolder.newFolder("files")
        val htmlFile = File(root, "reports/dashboard.html").apply {
            parentFile?.mkdirs()
            writeText("<h1>Hello</h1>")
        }

        val mapped = resolveWebTarget(htmlFile.absolutePath, root)

        assertEquals("https://appassets.androidplatform.net/files/reports/dashboard.html", mapped)
    }

    @Test
    fun mapsFileUriInsideFilesDirToAppAssetsHttps() {
        val root = tempFolder.newFolder("files")
        val htmlFile = File(root, "index.html").apply {
            writeText("<h1>Home</h1>")
        }

        val mapped = resolveWebTarget("file://${htmlFile.absolutePath}", root)

        assertEquals("https://appassets.androidplatform.net/files/index.html", mapped)
    }

    @Test
    fun preservesRemoteHttpUrlUnmodified() {
        val root = tempFolder.newFolder("files")
        val target = "https://example.com/api?a=1&b=2"

        val mapped = resolveWebTarget(target, root)

        assertEquals(target, mapped)
    }

    @Test
    fun preservesExternalPathAsFileUriWhenOutsideFilesDir() {
        val root = tempFolder.newFolder("files")
        val outsideDir = tempFolder.newFolder("external")
        val outsideFile = File(outsideDir, "outside.html").apply {
            writeText("<h1>Outside</h1>")
        }

        val mapped = resolveWebTarget(outsideFile.absolutePath, root)

        assertEquals("file://${outsideFile.canonicalPath}", mapped)
    }
}
