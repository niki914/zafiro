package com.niki914.zafiro.repo.oauth

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.niki914.logging.Logger
import com.niki914.zafiro.repo.XRepo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * "Sign in" for MCP servers that need OAuth, like connecting a connector in Claude: discover the server's sign-in service, register
 * Zafiro, open the browser, catch the redirect (zafiro-mcp://oauth), exchange the code (PKCE) and save the token as the server's
 * Authorization header. Tokens are refreshed in the background before they expire.
 */
object McpOAuth {
    private const val TAG = "McpOAuth"
    private val waiting = ConcurrentHashMap<String, CompletableDeferred<String>>()

    /** Called by MainActivity for zafiro-mcp:// links. @return true when the link was ours. */
    fun onCallback(uri: Uri?): Boolean {
        if (uri == null || uri.scheme != "zafiro-mcp") return false
        val state = uri.getQueryParameter("state")
        val waiter = state?.let { waiting[it] } ?: waiting.values.firstOrNull()
        waiter?.complete(uri.toString())
        return true
    }

    private data class Saved(val clientId: String, val tokenEndpoint: String, val resource: String, val refresh: String?, val expiresAtMs: Long?)

    private fun storeFile(context: Context) = File(context.filesDir, "settings/mcp_oauth.json")

    private fun readStore(context: Context): Map<String, Saved> = runCatching {
        Json.parseToJsonElement(storeFile(context).readText()).jsonObject.mapValues { (_, v) ->
            val o = v.jsonObject
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            Saved(s("client_id").orEmpty(), s("token_endpoint").orEmpty(), s("resource").orEmpty(), s("refresh"), s("expires_at")?.toLongOrNull())
        }
    }.getOrDefault(emptyMap())

    private fun writeStore(context: Context, m: Map<String, Saved>) {
        val f = storeFile(context).also { it.parentFile?.mkdirs() }
        f.writeText(JsonObject(m.mapValues { (_, s) ->
            JsonObject(buildMap {
                put("client_id", JsonPrimitive(s.clientId)); put("token_endpoint", JsonPrimitive(s.tokenEndpoint)); put("resource", JsonPrimitive(s.resource))
                s.refresh?.let { put("refresh", JsonPrimitive(it)) }; s.expiresAtMs?.let { put("expires_at", JsonPrimitive(it.toString())) }
            })
        }).toString())
    }

    private data class Http(val code: Int, val body: String)

    private fun http(method: String, url: String, body: String? = null, contentType: String = "application/json"): Http {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 15_000; c.readTimeout = 20_000; c.setRequestProperty("Accept", "application/json")
        if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", contentType); c.outputStream.use { it.write(body.toByteArray()) } }
        return try {
            val code = c.responseCode
            Http(code, (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally { c.disconnect() }
    }

    private fun getOrNull(url: String): String? = runCatching { http("GET", url).takeIf { it.code in 200..299 }?.body }.getOrNull()

    /** Full sign-in. Returns a message for the owner; throws with a plain reason when it cannot finish. */
    suspend fun signIn(context: Context, serverName: String, serverUrl: String): String = withContext(Dispatchers.IO) {
        val origin = McpOAuthLogic.origin(serverUrl)
        val resourceMeta = getOrNull("$origin/.well-known/oauth-protected-resource")
        val authServer = McpOAuthLogic.authorizationServer(resourceMeta, origin)
        val meta = getOrNull("$authServer/.well-known/oauth-authorization-server") ?: getOrNull("$authServer/.well-known/openid-configuration")
        val endpoints = McpOAuthLogic.endpoints(meta, authServer)
        val reg = endpoints.register ?: error("This server does not allow apps to register themselves, so Zafiro cannot sign in automatically. Paste an access token in the Headers field instead.")
        val r = http("POST", reg, McpOAuthLogic.registrationBody())
        val clientId = McpOAuthLogic.clientIdFrom(r.body) ?: error("Could not register Zafiro with the server (HTTP ${r.code}). ${r.body.take(160)}")

        val verifier = McpOAuthLogic.randomUrlSafe()
        val state = McpOAuthLogic.randomUrlSafe(16)
        val url = McpOAuthLogic.authorizeUrl(endpoints, clientId, state, McpOAuthLogic.challenge(verifier), serverUrl)
        val waiter = CompletableDeferred<String>().also { waiting[state] = it }
        try {
            withContext(Dispatchers.Main) {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            val callback = try { withTimeout(5 * 60_000L) { waiter.await() } } catch (e: kotlinx.coroutines.TimeoutCancellationException) { error("Sign-in was not completed within 5 minutes.") }
            val code = McpOAuthLogic.parseCallback(callback, state).getOrThrow()
            val t = http("POST", endpoints.token, McpOAuthLogic.tokenForm(code, verifier, clientId, serverUrl), "application/x-www-form-urlencoded")
            val tokens = McpOAuthLogic.parseTokens(t.body) ?: error("The server did not return a token (HTTP ${t.code}). ${t.body.take(160)}")
            val expiresAt = tokens.expiresInSec?.let { System.currentTimeMillis() + it * 1000 }
            writeStore(context, readStore(context) + (serverName to Saved(clientId, endpoints.token, serverUrl, tokens.refresh, expiresAt)))
            XRepo.setMcpAuthorization(serverName, tokens.access)
            "Signed in to $serverName." + if (tokens.refresh == null) " (This server gave no refresh token, so you will need to sign in again when it expires.)" else ""
        } finally { waiting.remove(state) }
    }

    /** Refreshes tokens that expire soon. Cheap to call often; failures are logged and retried next time. */
    suspend fun refreshDue(context: Context) = withContext(Dispatchers.IO) {
        val store = readStore(context)
        var changed = store
        store.forEach { (name, s) ->
            if (s.refresh == null || !McpOAuthLogic.needsRefresh(s.expiresAtMs, System.currentTimeMillis())) return@forEach
            runCatching {
                val t = http("POST", s.tokenEndpoint, McpOAuthLogic.refreshForm(s.refresh, s.clientId, s.resource), "application/x-www-form-urlencoded")
                val tokens = McpOAuthLogic.parseTokens(t.body) ?: error("refresh refused (HTTP ${t.code})")
                changed = changed + (name to s.copy(refresh = tokens.refresh ?: s.refresh, expiresAtMs = tokens.expiresInSec?.let { System.currentTimeMillis() + it * 1000 }))
                XRepo.setMcpAuthorization(name, tokens.access)
            }.onFailure { Logger.w(TAG, "refresh failed for $name: ${it.message}") }
        }
        if (changed !== store) writeStore(context, changed)
    }

    fun isSignedIn(context: Context, serverName: String) = serverName in readStore(context)
}
