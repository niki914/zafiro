package com.niki914.zafiro.chat.agentic.iab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [IabDownloads] 文件名清洗 / 建议名 / 私有目录边界的 JVM 单测（防路径穿越是重点）。 */
class IabDownloadsTest {

    @Test
    fun sanitizeName_stripsPathTraversal() {
        assertEquals("passwd", IabDownloads.sanitizeName("../../etc/passwd"))
        assertEquals("download", IabDownloads.sanitizeName(".."))
        assertEquals("download", IabDownloads.sanitizeName("..."))
        assertEquals("report", IabDownloads.sanitizeName("report"))
    }

    @Test
    fun sanitizeName_keepsSafeCharacters() {
        assertEquals("a-b_c.d", IabDownloads.sanitizeName("a-b_c.d"))
        // 空格与非法字符 → 下划线
        assertEquals("my_file_x", IabDownloads.sanitizeName("my file x"))
    }

    @Test
    fun sanitizeName_truncatesOverlong() {
        val long = "x".repeat(500)
        assertEquals(128, IabDownloads.sanitizeName(long).length)
    }

    @Test
    fun suggestedName_prefersContentDispositionFilename() {
        val name = IabDownloads.suggestedName(
            url = "https://example.com/dl?id=3",
            contentDisposition = "attachment; filename=\"report final.pdf\"",
            mimeType = "application/pdf",
        )
        assertEquals("report_final.pdf", name)
    }

    @Test
    fun suggestedName_prefersContentDispositionStar() {
        val name = IabDownloads.suggestedName(
            url = "https://example.com/dl",
            contentDisposition = "attachment; filename*=UTF-8''na%C3%AFve.csv",
            mimeType = "text/csv",
        )
        // RFC 5987 filename*=UTF-8'...' value "na%C3%AFve.csv" percent-encodes
        // "naïve.csv" (ï = U+00EF, UTF-8 bytes C3 AF). URLDecoder(..., "UTF-8")
        // decodes it back to clean UTF-8 and sanitizeName keeps ï as a letter, so
        // the suggested name is exactly "naïve.csv". The expected literal uses a
        // \u00EF escape (pure ASCII in the source) so it compiles identically no
        // matter which charset the CI Kotlin daemon reads this .kt file with; the
        // decoded value is charset-independent either way (it comes from the JVM).
        assertEquals("na\u00EFve.csv", name)
    }

    @Test
    fun suggestedName_fallsBackToUrlLastSegment() {
        assertEquals("data.json", IabDownloads.suggestedName("https://example.com/a/b/data.json", null, null))
    }

    @Test
    fun suggestedName_extFromMimeWhenMissing() {
        assertEquals("blob.png", IabDownloads.suggestedName("https://example.com/blob", null, "image/png"))
    }

    @Test
    fun extFor_knownMimes() {
        assertEquals("pdf", IabDownloads.extFor("application/pdf"))
        assertEquals("apk", IabDownloads.extFor("application/vnd.android.package-archive"))
        assertNull(IabDownloads.extFor("application/x-something-unknown"))
        assertNull(IabDownloads.extFor(null))
    }

    @Test
    fun isWithinAppDirs_acceptsOwnDirs() {
        assertTrue(IabDownloads.isWithinAppDirs("/data/app/x/files/downloads/a.txt", listOf("/data/app/x/files")))
        assertTrue(IabDownloads.isWithinAppDirs("/data/app/x/cache/iab/a.bin", listOf("/data/app/x/files", "/data/app/x/cache")))
    }

    @Test
    fun isWithinAppDirs_rejectsSiblingsAndPrefixTricks() {
        assertFalse(IabDownloads.isWithinAppDirs("/data/app/x/files_evil/a", listOf("/data/app/x/files")))
        assertFalse(IabDownloads.isWithinAppDirs("/sdcard/Download/a", listOf("/data/app/x/files")))
        assertFalse(IabDownloads.isWithinAppDirs("/data/app/x", listOf("/data/app/x/files")))
    }

    @Test
    fun normalizePath_relativeResolvesAgainstBase() {
        assertEquals("/app/files/rel.txt", IabDownloads.normalizePath("rel.txt", "/app/files"))
        assertEquals("/abs/x", IabDownloads.normalizePath("/abs/x", "/app/files"))
    }
}
