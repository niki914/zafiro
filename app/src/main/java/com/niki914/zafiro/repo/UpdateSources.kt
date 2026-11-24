package com.niki914.zafiro.repo

import com.niki914.zafiro.api.util.tryOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request

/** Where an update can come from. */
enum class UpdateSource(val title: String, val repo: String) {
    /** This app: Zafiro Autonomous releases. They already contain everything merged from the original. */
    OURS("Zafiro Autonomous (this app)", "TricretA/my-zafiro"),

    /** The original open-source Zafiro. A different app with a different signature: it cannot replace this one. */
    UPSTREAM("Original Zafiro (open source)", "niki914/zafiro"),
}

data class ReleaseInfo(val version: String, val newer: Boolean, val notes: String, val pageUrl: String, val apkUrl: String?, val prerelease: Boolean)

/** Parses GitHub release JSON; pure and unit-tested. */
object ReleaseParser {
    private val semver = Regex("""(\d+)\.(\d+)\.(\d+)""")
    private val json = Json { ignoreUnknownKeys = true }

    fun isNewer(remote: String, current: String): Boolean {
        val r = semver.find(remote)?.groupValues?.drop(1)?.map { it.toInt() } ?: return false
        val c = semver.find(current)?.groupValues?.drop(1)?.map { it.toInt() } ?: return true
        for (i in 0..2) if (r[i] != c[i]) return r[i] > c[i]
        return false
    }

    /** First non-draft release of a list (or a single release object). */
    fun parse(body: String, current: String): ReleaseInfo? {
        val el = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return null
        val obj = (el as? JsonObject) ?: (el as? JsonArray)?.firstOrNull { (it as? JsonObject)?.get("draft")?.jsonPrimitive?.booleanOrNull != true } as? JsonObject ?: return null
        val tag = obj["tag_name"]?.jsonPrimitive?.contentOrNull ?: return null
        val version = semver.find(tag)?.value ?: return null
        val apk = (obj["assets"] as? JsonArray)?.firstNotNullOfOrNull { a ->
            val o = a as? JsonObject ?: return@firstNotNullOfOrNull null
            if (o["name"]?.jsonPrimitive?.contentOrNull.orEmpty().endsWith(".apk")) o["browser_download_url"]?.jsonPrimitive?.contentOrNull else null
        }
        return ReleaseInfo(version, isNewer(version, current), obj["body"]?.jsonPrimitive?.contentOrNull.orEmpty().trim(), obj["html_url"]?.jsonPrimitive?.contentOrNull.orEmpty(), apk, obj["prerelease"]?.jsonPrimitive?.booleanOrNull == true)
    }
}

object UpdateSources {
    /** Latest release of [source], or null when it cannot be reached. */
    suspend fun latest(source: UpdateSource, current: String): ReleaseInfo? = withContext(Dispatchers.IO) {
        tryOrNull {
            val req = Request.Builder().url("https://api.github.com/repos/${source.repo}/releases?per_page=5").build()
            SharedHttp.client.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
        }?.let { ReleaseParser.parse(it, current) }
    }
}
