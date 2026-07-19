package com.niki914.nexus.agentic.repo

import com.niki914.nexus.h.util.xTry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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
        _result.value = UpdateCheckResult(hasUpdate = false, remoteVersion = null, releaseUrl = null)
    }

    fun isDismissed(): Boolean = dismissed
}

private object UpdateCheckApi {
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    private const val GITHUB_API_LATEST =
        "https://api.github.com/repos/niki914/agentic-nexus/releases/latest"

    private val semverRe = Regex("""(\d+\.\d+\.\d+)""")

    suspend fun check(currentVersion: String): UpdateCheckResult {
        return withContext(Dispatchers.IO) {
            xTry { resolveUpdateOrNull(currentVersion) } ?: noUpdate()
        }
    }

    private fun resolveUpdateOrNull(currentVersion: String): UpdateCheckResult {
        val body = fetchLatestRelease() ?: return noUpdate()
        val obj = json.parseToJsonElement(body) as? JsonObject ?: return noUpdate()

        if (obj["draft"]?.jsonPrimitive?.booleanOrNull == true) return noUpdate()
        if (obj["prerelease"]?.jsonPrimitive?.booleanOrNull == true) return noUpdate()

        val tagName = obj["tag_name"]?.jsonPrimitive?.content ?: return noUpdate()
        val remoteVersion = semverRe.find(tagName)?.groupValues?.get(1) ?: return noUpdate()

        if (!isNewer(remoteVersion, currentVersion)) return noUpdate()

        val releaseUrl = obj["html_url"]?.jsonPrimitive?.content.orEmpty()
        return UpdateCheckResult(
            hasUpdate = true,
            remoteVersion = remoteVersion,
            releaseUrl = releaseUrl,
        )
    }

    private fun fetchLatestRelease(): String? {
        val request = Request.Builder().url(GITHUB_API_LATEST).build()
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

    private fun noUpdate() = UpdateCheckResult(hasUpdate = false, remoteVersion = null, releaseUrl = null)
}
