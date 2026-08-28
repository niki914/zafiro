package com.niki914.zafiro.repo

import com.niki914.xposed.api.util.xTry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

data class UpdateCheckResult(
    val hasUpdate: Boolean,
    val remoteVersion: String?,
    val releaseUrl: String?,
)

object UpdateCheckHolder {
    private val _result = MutableStateFlow<UpdateCheckResult?>(null)
    val result: StateFlow<UpdateCheckResult?> = _result.asStateFlow()

    private var fired = false
    private var dismissed = false

    suspend fun runOnce(currentVersion: String) {
        if (fired) return
        fired = true
        val r = UpdateCheckApi.check(currentVersion)
        _result.value = r
    }

    fun dismiss() {
        dismissed = true
        _result.value =
            UpdateCheckResult(hasUpdate = false, remoteVersion = null, releaseUrl = null)
    }

    fun isDismissed(): Boolean = dismissed
}

private object UpdateCheckApi {
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    private const val GITHUB_API_LATEST =
        "https://api.github.com/repos/niki914/zafiro/releases/latest"
    private const val GITHUB_API_LATEST_ANY =
        "https://api.github.com/repos/niki914/zafiro/releases?per_page=1"

    private val semverRe = Regex("""(\d+\.\d+\.\d+)""")

    suspend fun check(currentVersion: String): UpdateCheckResult {
        return withContext(Dispatchers.IO) {
            xTry { resolveUpdateOrNull(currentVersion) } ?: noUpdate()
        }
    }

    private fun resolveUpdateOrNull(currentVersion: String): UpdateCheckResult {
        // 先看最新 stable release；无更新时兜底看最新 release（含 prerelease，
        // preview 分发线靠它才能被检测到）
        return checkRelease(fetchLatestRelease(), currentVersion, includePrerelease = false)
            ?: checkRelease(fetchLatestReleaseAny(), currentVersion, includePrerelease = true)
            ?: noUpdate()
    }

    private fun checkRelease(
        body: String?,
        currentVersion: String,
        includePrerelease: Boolean,
    ): UpdateCheckResult? {
        if (body == null) return null
        val element = json.parseToJsonElement(body)
        val obj = when (element) {
            is JsonObject -> element
            is JsonArray -> element.firstOrNull() as? JsonObject
            else -> null
        } ?: return null

        if (obj["draft"]?.jsonPrimitive?.booleanOrNull == true) return null
        if (!includePrerelease && obj["prerelease"]?.jsonPrimitive?.booleanOrNull == true) return null

        val tagName = obj["tag_name"]?.jsonPrimitive?.content ?: return null
        val remoteVersion = semverRe.find(tagName)?.groupValues?.get(1) ?: return null

        if (!isNewer(remoteVersion, currentVersion)) return null

        val releaseUrl = obj["html_url"]?.jsonPrimitive?.content.orEmpty()
        return UpdateCheckResult(
            hasUpdate = true,
            remoteVersion = remoteVersion,
            releaseUrl = releaseUrl,
        )
    }

    private fun fetchLatestRelease(): String? = fetch(GITHUB_API_LATEST)

    private fun fetchLatestReleaseAny(): String? = fetch(GITHUB_API_LATEST_ANY)

    private fun fetch(url: String): String? {
        val request = Request.Builder().url(url).build()
        return xTry {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful && response.body != null) {
                    response.body!!.string()
                } else {
                    null
                }
            }
        }
    }

    private fun isNewer(remote: String, current: String): Boolean {
        val r = remote.split(".").map { it.toIntOrNull() ?: 0 }
        val c = current.split(".").map { it.toIntOrNull() ?: 0 }
        val len = maxOf(r.size, c.size)
        for (i in 0 until len) {
            val rp = r.getOrElse(i) { 0 }
            val cp = c.getOrElse(i) { 0 }
            if (rp > cp) return true
            if (rp < cp) return false
        }
        return false
    }

    private fun noUpdate() =
        UpdateCheckResult(hasUpdate = false, remoteVersion = null, releaseUrl = null)
}
