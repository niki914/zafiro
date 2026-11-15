package com.tricreta.zafiro.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CloudSttTest {
    @Test fun endpointAcceptsBaseOrFullUrl() {
        assertEquals("https://api.groq.com/openai/v1/audio/transcriptions", CloudStt.endpoint(CloudStt.GROQ_URL))
        assertEquals("https://x/v1/audio/transcriptions", CloudStt.endpoint("https://x/v1/"))
        assertEquals("https://x/v1/audio/transcriptions", CloudStt.endpoint("https://x/v1/audio/transcriptions"))
    }

    @Test fun multipartHasModelFileAndClosingBoundary() {
        val body = String(CloudStt.multipart("B", "whisper-1", "en", "a.m4a", "audio/mp4", byteArrayOf(1, 2, 3)), Charsets.ISO_8859_1)
        assertTrue(body.contains("name=\"model\"\r\n\r\nwhisper-1\r\n"))
        assertTrue(body.contains("name=\"language\"\r\n\r\nen\r\n"))
        assertTrue(body.contains("name=\"file\"; filename=\"a.m4a\"\r\nContent-Type: audio/mp4\r\n\r\n"))
        assertTrue(body.endsWith("\r\n--B--\r\n"))
        assertTrue(!String(CloudStt.multipart("B", "m", null, "a", "x", ByteArray(0))).contains("name=\"language\""))
    }

    @Test fun errorsAreExplainedInPlainWords() {
        assertTrue(CloudStt.explain(401, "").contains("API key"))
        assertTrue(CloudStt.explain(429, "").contains("rate"))
        assertTrue(CloudStt.explain(500, "boom").contains("boom"))
    }
}
