package com.niki914.zafiro.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseParserTest {
    @Test fun newerComparesEachPart() {
        assertTrue(ReleaseParser.isNewer("v1.10.0-auto.1", "1.9.9"))
        assertFalse(ReleaseParser.isNewer("1.9.0", "1.9.0"))
        assertFalse(ReleaseParser.isNewer("v1.2.9", "1.3.0"))
    }

    @Test fun parsesNotesAndApkAndSkipsDrafts() {
        val body = """[{"tag_name":"v9.9.9","draft":true},{"tag_name":"v1.18.0-auto.3","body":" ## What's new\n- x ","html_url":"https://g/r","prerelease":false,
            "assets":[{"name":"notes.txt","browser_download_url":"https://g/n"},{"name":"app.apk","browser_download_url":"https://g/app.apk"}]}]"""
        val r = ReleaseParser.parse(body, "1.17.0")!!
        assertEquals("1.18.0", r.version); assertTrue(r.newer); assertEquals("## What's new\n- x", r.notes); assertEquals("https://g/app.apk", r.apkUrl)
        assertFalse(ReleaseParser.parse(body, "1.18.0")!!.newer)
        assertNull(ReleaseParser.parse("nope", "1.0.0")); assertNull(ReleaseParser.parse("""{"tag_name":"latest"}""", "1.0.0"))
    }
}
