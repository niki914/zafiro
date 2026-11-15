package com.niki914.zafiro.app.ui.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FileTextTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun zip(name: String, vararg entries: Pair<String, String>): File = File(tmp.root, name).also { f ->
        ZipOutputStream(f.outputStream()).use { z -> entries.forEach { (n, c) -> z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() } }
    }

    @Test fun docxTextKeepsParagraphs() {
        val f = zip("a.docx", "word/document.xml" to "<w:document><w:p><w:r><w:t>Hello &amp; welcome</w:t></w:r></w:p><w:p><w:r><w:t>Second</w:t></w:r></w:p></w:document>")
        assertEquals("Hello & welcome\nSecond", FileText.office(f))
    }

    @Test fun notAnOfficeFileIsNull() {
        assertNull(FileText.office(zip("x.zip", "readme.txt" to "hi")))
        assertEquals(listOf("readme.txt  (1 KB)"), FileText.archiveListing(zip("y.zip", "readme.txt" to "hi")))
    }

    @Test fun csvHandlesQuotesAndTabs() {
        assertEquals(listOf(listOf("a", "b,c", "d")), FileText.csvRows("a,\"b,c\",d"))
        assertEquals(listOf(listOf("x", "y")), FileText.csvRows("x\ty"))
    }

    @Test fun textShowsAsTextAndBinaryAsHex() {
        assertEquals("hello", FileText.bytesPreview(File(tmp.root, "t.bin").also { it.writeText("hello") }))
        val bin = File(tmp.root, "b.bin").also { it.writeBytes(ByteArray(64) { (it * 7).toByte().let { b -> if (b.toInt() in 32..126) 0 else b } }) }
        assertTrue(FileText.bytesPreview(bin).contains("binary file")); assertNotNull(FileText.bytesPreview(bin))
    }
}
