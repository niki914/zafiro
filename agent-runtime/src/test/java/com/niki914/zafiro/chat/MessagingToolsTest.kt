package com.niki914.zafiro.chat

import com.niki914.zafiro.chat.agentic.buildin.impl.ComposeMessageBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.HttpRequestPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MessagingToolsTest {
    @Test
    fun httpPolicyBlocksLocalAndPrivateTargets() {
        for (url in listOf(
            "https://127.0.0.1/", "https://localhost/", "https://10.0.0.5/x", "https://192.168.1.1/", "https://172.16.0.1/",
            "https://169.254.169.254/latest/meta-data", "https://[::1]/", "https://0.0.0.0/",
        )) assertNotNull(url, HttpRequestPolicy.check(url))
    }

    @Test
    fun httpPolicyRequiresHttpsAndAValidUrl() {
        assertNotNull(HttpRequestPolicy.check("http://8.8.8.8/"))
        assertNotNull(HttpRequestPolicy.check("ftp://8.8.8.8/"))
        assertNotNull(HttpRequestPolicy.check("not a url"))
        assertNotNull(HttpRequestPolicy.check("https:///nohost"))
    }

    @Test
    fun httpPolicyAllowsPublicHttps() {
        assertNull(HttpRequestPolicy.check("https://8.8.8.8/dns-query"))
    }

    @Test
    fun emailDraftUriIsEncoded() {
        assertEquals(
            "mailto:a@b.com?subject=Hi%20there&body=Line%201%0ALine%202%20%26%20more",
            ComposeMessageBuiltin.buildUri("email", "a@b.com", "Hi there", "Line 1\nLine 2 & more"),
        )
    }

    @Test
    fun smsDraftUriStripsSpaces() {
        assertEquals("sms:+254712345678?body=Hello", ComposeMessageBuiltin.buildUri("sms", "+254 712 345678", "", "Hello"))
    }

    @Test
    fun badDraftsAreRejected() {
        assertNull(ComposeMessageBuiltin.buildUri("email", "not-an-email", "s", "b"))
        assertNull(ComposeMessageBuiltin.buildUri("sms", "abc", "", "b"))
        assertNull(ComposeMessageBuiltin.buildUri("email", "a@b.com", "s", "  "))
        assertNull(ComposeMessageBuiltin.buildUri("fax", "123", "", "b"))
    }
}
