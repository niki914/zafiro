package com.niki914.zafiro.chat.agentic.buildin.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebTextTest {
    @Test fun extractRemovesScriptsAndKeepsReadableText() {
        val html = "<html><head><title>My &amp; Page</title><style>p{}</style></head><body><nav>Menu</nav><h1>Hello</h1><p>One <b>bold</b> line</p><script>var x=1</script><ul><li>A</li><li>B</li></ul></body></html>"
        assertEquals("My & Page", WebText.title(html))
        val t = WebText.extract(html)
        assertTrue(t, t.contains("Hello") && t.contains("One bold line") && t.contains("A\nB"))
        assertFalse(t.contains("Menu")); assertFalse(t.contains("var x"))
        assertTrue(WebText.extract("<p>" + "x".repeat(50) + "</p>", 10).endsWith("[truncated]"))
    }

    @Test fun duckResultsUnwrapRedirectsAndPairSnippets() {
        val html = """<a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fa%3Fx%3D1&rut=zz">Example <b>A</b></a>
            <a class="result__snippet" href="x">About <b>A</b> thing</a>
            <a class="result__a" href="https://other.org/b">Other</a><a class="result__snippet" href="y">Second</a>"""
        val r = WebText.duckResults(html)
        assertEquals(2, r.size)
        assertEquals(WebText.Hit("Example A", "https://example.com/a?x=1", "About A thing"), r[0])
        assertEquals("https://other.org/b", r[1].url)
    }

    @Test fun feedItemsReadRssAndAtom() {
        val rss = """<rss><channel><item><title><![CDATA[Post 1]]></title><link>https://a/1</link><pubDate>Mon, 02 Nov 2026</pubDate><description>&lt;p&gt;Hi there&lt;/p&gt;</description></item></channel></rss>"""
        val i = WebText.feedItems(rss).single()
        assertEquals("Post 1", i.title); assertEquals("https://a/1", i.link); assertEquals("Hi there", i.summary)
        val atom = """<feed><entry><title>T</title><link href="https://b/2"/><updated>2026-11-02</updated><summary>S</summary></entry></feed>"""
        assertEquals(WebText.FeedItem("T", "https://b/2", "2026-11-02", "S"), WebText.feedItems(atom).single())
    }
}
