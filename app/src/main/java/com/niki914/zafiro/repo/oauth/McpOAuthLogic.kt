package com.niki914.zafiro.repo.oauth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Pure OAuth 2.1 pieces for MCP servers (PKCE, discovery documents, URLs, token parsing). No Android, no network: unit-tested. */
object McpOAuthLogic {
    const val REDIRECT_URI = "zafiro-mcp://oauth"

    data class Endpoints(val authorize: String, val token: String, val register: String?)
    data class Tokens(val access: String, val refresh: String?, val expiresInSec: Long?)

    private val rnd = SecureRandom()

    fun randomUrlSafe(bytes: Int = 48): String {
        val b = ByteArray(bytes); rnd.nextBytes(b)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }

    /** RFC 7636 S256 challenge. */
    fun challenge(verifier: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    fun origin(url: String): String {
        val u = URI(url.trim())
        require(u.scheme == "https" || u.scheme == "http") { "Not a web address: $url" }
        return u.scheme + "://" + u.authority
    }

    /** Authorization server from RFC 9728 protected-resource metadata; falls back to the server's own origin. */
    fun authorizationServer(protectedResourceJson: String?, fallbackOrigin: String): String {
        val first = runCatching {
            Json.parseToJsonElement(protectedResourceJson.orEmpty()).jsonObject["authorization_servers"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        return (first ?: fallbackOrigin).trimEnd('/')
    }

    /** RFC 8414 metadata, or the MCP spec's default endpoint paths when the server publishes none. */
    fun endpoints(metadataJson: String?, authServer: String): Endpoints {
        val o = runCatching { Json.parseToJsonElement(metadataJson.orEmpty()) as? JsonObject }.getOrNull()
        fun s(k: String) = (o?.get(k) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        val base = authServer.trimEnd('/')
        return Endpoints(
            authorize = s("authorization_endpoint") ?: "$base/authorize",
            token = s("token_endpoint") ?: "$base/token",
            register = s("registration_endpoint") ?: if (o == null) "$base/register" else null,
        )
    }

    fun registrationBody(clientName: String = "Zafiro"): String = JsonObject(
        mapOf(
            "client_name" to JsonPrimitive(clientName),
            "redirect_uris" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(REDIRECT_URI))),
            "grant_types" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("authorization_code"), JsonPrimitive("refresh_token"))),
            "response_types" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("code"))),
            "token_endpoint_auth_method" to JsonPrimitive("none"),
        ),
    ).toString()

    fun clientIdFrom(registrationResponse: String): String? =
        runCatching { Json.parseToJsonElement(registrationResponse).jsonObject["client_id"]?.jsonPrimitive?.contentOrNull }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun authorizeUrl(e: Endpoints, clientId: String, state: String, challenge: String, resource: String, scope: String? = null): String =
        e.authorize + (if (e.authorize.contains('?')) "&" else "?") +
            listOfNotNull(
                "response_type=code", "client_id=${enc(clientId)}", "redirect_uri=${enc(REDIRECT_URI)}", "state=${enc(state)}",
                "code_challenge=${enc(challenge)}", "code_challenge_method=S256", "resource=${enc(resource)}", scope?.let { "scope=${enc(it)}" },
            ).joinToString("&")

    fun tokenForm(code: String, verifier: String, clientId: String, resource: String): String =
        "grant_type=authorization_code&code=${enc(code)}&redirect_uri=${enc(REDIRECT_URI)}&client_id=${enc(clientId)}&code_verifier=${enc(verifier)}&resource=${enc(resource)}"

    fun refreshForm(refreshToken: String, clientId: String, resource: String): String =
        "grant_type=refresh_token&refresh_token=${enc(refreshToken)}&client_id=${enc(clientId)}&resource=${enc(resource)}"

    fun parseTokens(json: String): Tokens? {
        val o = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
        val access = (o["access_token"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        return Tokens(access, (o["refresh_token"] as? JsonPrimitive)?.contentOrNull, (o["expires_in"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toLong())
    }

    /** Callback result: the authorization code, or an error message. The state must match the one we sent. */
    fun parseCallback(callback: String, expectedState: String): Result<String> {
        val q = runCatching { URI(callback).rawQuery.orEmpty() }.getOrDefault("")
        val params = q.split('&').filter { it.contains('=') }.associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        params["error"]?.let { return Result.failure(IllegalStateException("The server refused sign-in: $it ${params["error_description"].orEmpty()}".trim())) }
        if (params["state"] != expectedState) return Result.failure(IllegalStateException("Sign-in response did not match this request (state mismatch)."))
        return params["code"]?.let { Result.success(it) } ?: Result.failure(IllegalStateException("Sign-in finished without a code."))
    }

    /** Refresh when less than [marginSec] remain. */
    fun needsRefresh(expiresAtMs: Long?, nowMs: Long, marginSec: Long = 900): Boolean = expiresAtMs != null && nowMs >= expiresAtMs - marginSec * 1000
}
