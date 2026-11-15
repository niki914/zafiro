package com.niki914.zafiro.chat.agentic.buildin.impl

import com.niki914.zafiro.chat.agentic.buildin.BuiltinTool
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/** Pure text extraction and parsing for the web tools (unit-tested; the tools only add the network call). */
object WebText {
    private val DROP = Regex("(?is)<(script|style|noscript|svg|head|nav|footer|form|iframe)\\b.*?</\\1>")
    private val BLOCK = Regex("(?i)</?(p|div|section|article|li|tr|br|h[1-6]|ul|ol|table|blockquote)\\b[^>]*>")
    private val TAG = Regex("(?s)<[^>]+>")
    private val TITLE = Regex("(?is)<title[^>]*>(.*?)</title>")

    fun title(html: String): String = TITLE.find(html)?.groupValues?.get(1)?.let { decode(it).trim() }.orEmpty()

    /** Readable text: scripts, styles and navigation removed, block tags turned into line breaks, entities decoded. */
    fun extract(html: String, maxChars: Int = 12_000): String {
        val text = decode(TAG.replace(BLOCK.replace(DROP.replace(html, " "), "\n"), " "))
            .lines().map { it.replace(Regex("[ \\t\\u00a0]+"), " ").trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        return if (text.length > maxChars) text.take(maxChars) + "\n[truncated]" else text
    }

    fun decode(s: String): String = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&apos;", "'").replace("&nbsp;", " ").replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { c -> String(Character.toChars(c)) } ?: m.value }

    data class Hit(val title: String, val url: String, val snippet: String)

    /** DuckDuckGo's HTML endpoint: result links are redirects (//duckduckgo.com/l/?uddg=<encoded target>). */
    fun duckResults(html: String, limit: Int = 8): List<Hit> {
        val link = Regex("(?is)<a[^>]+class=\"[^\"]*result__a[^\"]*\"[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>")
        val snip = Regex("(?is)<a[^>]+class=\"[^\"]*result__snippet[^\"]*\"[^>]*>(.*?)</a>|<div[^>]+class=\"[^\"]*result__snippet[^\"]*\"[^>]*>(.*?)</div>")
        val snippets = snip.findAll(html).map { decode(TAG.replace(it.groupValues[1].ifEmpty { it.groupValues[2] }, "")).trim() }.toList()
        return link.findAll(html).mapIndexedNotNull { i, m ->
            val url = unwrapRedirect(decode(m.groupValues[1]))
            if (!url.startsWith("http")) null else Hit(decode(TAG.replace(m.groupValues[2], "")).trim(), url, snippets.getOrElse(i) { "" })
        }.distinctBy { it.url }.take(limit).toList()
    }

    fun unwrapRedirect(href: String): String {
        val raw = if (href.startsWith("//")) "https:$href" else href
        val q = Regex("[?&]uddg=([^&]+)").find(raw)?.groupValues?.get(1) ?: return raw
        return runCatching { URLDecoder.decode(q, "UTF-8") }.getOrDefault(raw)
    }

    data class FeedItem(val title: String, val link: String, val date: String, val summary: String)

    /** RSS 2.0 and Atom, tolerant: reads <item>/<entry> blocks with simple tag extraction. */
    fun feedItems(xml: String, limit: Int = 15): List<FeedItem> {
        fun tag(block: String, name: String): String =
            Regex("(?is)<$name[^>]*>(.*?)</$name>").find(block)?.groupValues?.get(1)?.let { decode(it.replace("<![CDATA[", "").replace("]]>", "")).trim() }.orEmpty()
        return Regex("(?is)<(item|entry)\\b.*?</\\1>").findAll(xml).take(limit).map { m ->
            val b = m.value
            val link = Regex("(?is)<link[^>]*href=\"([^\"]+)\"").find(b)?.groupValues?.get(1) ?: tag(b, "link")
            FeedItem(TAG.replace(tag(b, "title"), "").trim(), link, tag(b, "pubDate").ifEmpty { tag(b, "updated").ifEmpty { tag(b, "published") } },
                TAG.replace(tag(b, "description").ifEmpty { tag(b, "summary").ifEmpty { tag(b, "content") } }, " ").replace(Regex("\\s+"), " ").trim().take(300))
        }.toList()
    }
}

internal object WebHttp {
    const val UA = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
    private const val MAX_BYTES = 2_000_000

    /** GET with up to 4 redirects (each hop re-checked by [HttpRequestPolicy]). Blocking. */
    fun get(url: String): Result<Pair<String, String>> = runCatching {
        var current = url
        repeat(5) {
            HttpRequestPolicy.check(current)?.let { error(it) }
            val c = URL(current).openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = false; c.connectTimeout = 15_000; c.readTimeout = 20_000
                c.setRequestProperty("User-Agent", UA); c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml,application/rss+xml;q=0.9,*/*;q=0.5")
                val code = c.responseCode
                if (code in 300..399) { c.getHeaderField("Location")?.let { loc -> current = URL(URL(current), loc).toString() } ?: error("Redirect without a location"); return@repeat }
                val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { r -> r.readText() }.orEmpty().take(MAX_BYTES)
                if (code !in 200..299) error("HTTP $code")
                return@runCatching current to body
            } finally { c.disconnect() }
        }
        error("Too many redirects")
    }
}

private fun invalid(m: String) = BuiltinToolResult.failure(code = "INVALID_ARGUMENTS", message = m)

/** Reads a web page as clean text (no JavaScript). For pages that need login or scripts, use the screen instead. */
class WebFetchBuiltin : BuiltinTool() {
    override val name = "web_fetch"
    override val defaultEnabled = true
    override val description = "Fetch a public web page and return its readable text (title + body, scripts and menus removed, up to max_chars). Does not run JavaScript and cannot log in."
    override val inputSchemaJson = """{"type":"object","properties":{"url":{"type":"string"},"max_chars":{"type":"number"}},"required":["url"]}"""
    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
        val a = parseScreenObject(request.argumentsJson) ?: return invalid("Arguments must be a JSON object.")
        val url = a.text("url") ?: return invalid("url is required.")
        val max = (a.int("max_chars") ?: 12_000).coerceIn(500, 40_000)
        val (final, html) = WebHttp.get(url).getOrElse { return BuiltinToolResult.failure(code = "FETCH_FAILED", message = it.message.orEmpty(), hint = "Try web_search for another source.") }
        return BuiltinToolResult.success(
            message = "Fetched $final",
            data = buildJsonObject { put("url", final); put("title", WebText.title(html)); put("text", WebText.extract(html, max)) },
        )
    }
}

/** Web search without an API key (DuckDuckGo HTML). Results are links and snippets: open the best ones with web_fetch. */
class WebSearchBuiltin : BuiltinTool() {
    override val name = "web_search"
    override val defaultEnabled = true
    override val description = "Search the web (no key needed). Returns up to 8 results with title, url and snippet. Follow up with web_fetch on the best ones."
    override val inputSchemaJson = """{"type":"object","properties":{"query":{"type":"string"},"limit":{"type":"number"}},"required":["query"]}"""
    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
        val a = parseScreenObject(request.argumentsJson) ?: return invalid("Arguments must be a JSON object.")
        val q = a.text("query") ?: return invalid("query is required.")
        val html = WebHttp.get("https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(q, "UTF-8")).getOrElse {
            return BuiltinToolResult.failure(code = "SEARCH_FAILED", message = it.message.orEmpty(), hint = "Read a known site with web_fetch, or use an MCP search server.")
        }.second
        val hits = WebText.duckResults(html, (a.int("limit") ?: 8).coerceIn(1, 15))
        if (hits.isEmpty()) return BuiltinToolResult.failure(code = "NO_RESULTS", message = "No results parsed (the search page may have changed or asked for a check).", hint = "Rephrase, or use web_fetch on a specific site.")
        return BuiltinToolResult.success(message = "${hits.size} result(s).", data = buildJsonObject {
            put("results", buildJsonArray { hits.forEach { add(buildJsonObject { put("title", it.title); put("url", it.url); put("snippet", it.snippet) }) } })
        })
    }
}

/** Reads an RSS/Atom feed: the cheapest way to see what a blog, newsletter or subreddit published lately. */
class RssReadBuiltin : BuiltinTool() {
    override val name = "rss_read"
    override val defaultEnabled = true
    override val description = "Read an RSS or Atom feed URL and return the latest items (title, link, date, summary). Substack feeds are https://<name>.substack.com/feed ."
    override val inputSchemaJson = """{"type":"object","properties":{"url":{"type":"string"},"limit":{"type":"number"}},"required":["url"]}"""
    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
        val a = parseScreenObject(request.argumentsJson) ?: return invalid("Arguments must be a JSON object.")
        val url = a.text("url") ?: return invalid("url is required.")
        val xml = WebHttp.get(url).getOrElse { return BuiltinToolResult.failure(code = "FETCH_FAILED", message = it.message.orEmpty()) }.second
        val items = WebText.feedItems(xml, (a.int("limit") ?: 15).coerceIn(1, 40))
        if (items.isEmpty()) return BuiltinToolResult.failure(code = "NOT_A_FEED", message = "No feed items found at that address.")
        return BuiltinToolResult.success(message = "${items.size} item(s).", data = buildJsonObject {
            put("items", buildJsonArray { items.forEach { add(buildJsonObject { put("title", it.title); put("link", it.link); put("date", it.date); put("summary", it.summary) }) } })
        })
    }
}
