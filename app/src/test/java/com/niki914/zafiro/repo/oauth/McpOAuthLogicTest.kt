package com.niki914.zafiro.repo.oauth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpOAuthLogicTest {
    @Test fun pkceMatchesTheRfc7636Vector() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", McpOAuthLogic.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        assertNotEquals(McpOAuthLogic.randomUrlSafe(), McpOAuthLogic.randomUrlSafe())
        assertTrue(McpOAuthLogic.randomUrlSafe().length >= 43)
    }

    @Test fun discoveryUsesPublishedMetadataOrSpecDefaults() {
        assertEquals("https://auth.example.com", McpOAuthLogic.authorizationServer("""{"authorization_servers":["https://auth.example.com/"]}""", "https://mcp.example.com"))
        assertEquals("https://mcp.example.com", McpOAuthLogic.authorizationServer(null, "https://mcp.example.com"))
        val e = McpOAuthLogic.endpoints("""{"authorization_endpoint":"https://a/x","token_endpoint":"https://a/t","registration_endpoint":"https://a/r"}""", "https://a")
        assertEquals(McpOAuthLogic.Endpoints("https://a/x", "https://a/t", "https://a/r"), e)
        assertEquals(McpOAuthLogic.Endpoints("https://m/authorize", "https://m/token", "https://m/register"), McpOAuthLogic.endpoints(null, "https://m"))
        assertNull("metadata without registration endpoint means no dynamic registration", McpOAuthLogic.endpoints("""{"authorization_endpoint":"https://a/x","token_endpoint":"https://a/t"}""", "https://a").register)
        assertEquals("https://mcp.notion.com", McpOAuthLogic.origin("https://mcp.notion.com/mcp"))
    }

    @Test fun authorizeUrlCarriesPkceStateAndResource() {
        val u = McpOAuthLogic.authorizeUrl(McpOAuthLogic.Endpoints("https://a/authorize", "https://a/t", null), "cid 1", "st", "ch", "https://m/mcp")
        assertTrue(u, u.startsWith("https://a/authorize?response_type=code&client_id=cid+1&redirect_uri=zafiro-mcp%3A%2F%2Foauth&state=st&code_challenge=ch&code_challenge_method=S256&resource=https%3A%2F%2Fm%2Fmcp"))
    }

    @Test fun callbackValidatesStateAndSurfacesErrors() {
        assertEquals("abc", McpOAuthLogic.parseCallback("zafiro-mcp://oauth?code=abc&state=s1", "s1").getOrThrow())
        assertTrue(McpOAuthLogic.parseCallback("zafiro-mcp://oauth?code=abc&state=other", "s1").isFailure)
        assertTrue(McpOAuthLogic.parseCallback("zafiro-mcp://oauth?error=access_denied&error_description=No&state=s1", "s1").exceptionOrNull()!!.message!!.contains("access_denied"))
        assertTrue(McpOAuthLogic.parseCallback("zafiro-mcp://oauth?state=s1", "s1").isFailure)
    }

    @Test fun tokensParseAndRefreshTimingIsComputed() {
        assertEquals(McpOAuthLogic.Tokens("a", "r", 3600), McpOAuthLogic.parseTokens("""{"access_token":"a","refresh_token":"r","expires_in":3600,"token_type":"Bearer"}"""))
        assertNull(McpOAuthLogic.parseTokens("""{"error":"invalid_grant"}""")); assertNull(McpOAuthLogic.parseTokens("nope"))
        assertTrue(McpOAuthLogic.needsRefresh(1_000_000, 1_000_000 - 800_000)); assertFalse(McpOAuthLogic.needsRefresh(1_000_000, 0)); assertFalse(McpOAuthLogic.needsRefresh(null, 5))
        assertEquals("""{"client_name":"Zafiro","redirect_uris":["zafiro-mcp://oauth"],"grant_types":["authorization_code","refresh_token"],"response_types":["code"],"token_endpoint_auth_method":"none"}""", McpOAuthLogic.registrationBody())
        assertEquals("cid", McpOAuthLogic.clientIdFrom("""{"client_id":"cid","client_secret_expires_at":0}"""))
    }
}
