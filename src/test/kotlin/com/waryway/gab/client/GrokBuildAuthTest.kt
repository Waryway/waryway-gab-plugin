package com.waryway.gab.client

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GrokBuildAuthTest {

    @Test
    fun `parseSession reads auth_xai scope entry`() {
        val json = """
            {
              "https://auth.x.ai::b1a00492-073a-47ea-816f-4c329264a828": {
                "key": "eyJaccess-token-value",
                "auth_mode": "oidc",
                "email": "user@example.com",
                "team_id": "team-1",
                "expires_at": "2099-01-01T00:00:00Z"
              }
            }
        """.trimIndent()

        val session = GrokBuildAuth.parseSession(json)
        assertNotNull(session)
        assertEquals("eyJaccess-token-value", session.accessToken)
        assertEquals("user@example.com", session.email)
        assertEquals("team-1", session.teamId)
        assertEquals("oidc", session.authMode)
        assertFalse(session.isExpired())
    }

    @Test
    fun `parseSession returns null when key missing`() {
        val json = """{"https://auth.x.ai::x": {"email": "a@b.c"}}"""
        assertNull(GrokBuildAuth.parseSession(json))
    }

    @Test
    fun `expired session is detected`() {
        val json = """
            {
              "https://auth.x.ai::x": {
                "key": "tok",
                "expires_at": "2020-01-01T00:00:00Z"
              }
            }
        """.trimIndent()
        val session = GrokBuildAuth.parseSession(json)
        assertNotNull(session)
        assertTrue(session.isExpired())
    }

    @Test
    fun `session isExpired applies 60s near-expiry skew`() {
        val now = Instant.parse("2026-07-17T12:00:00Z")
        // Expires in 30s → within skew → treated as expired
        val near = GrokBuildAuth.Session(
            accessToken = "tok",
            expiresAt = Instant.parse("2026-07-17T12:00:30Z")
        )
        assertTrue(near.isExpired(now))
        // Expires exactly at now+60s → not after now+60 → expired
        val atSkew = GrokBuildAuth.Session(
            accessToken = "tok",
            expiresAt = Instant.parse("2026-07-17T12:01:00Z")
        )
        assertTrue(atSkew.isExpired(now))
        // Expires after now+60s → usable
        val ok = GrokBuildAuth.Session(
            accessToken = "tok",
            expiresAt = Instant.parse("2026-07-17T12:01:01Z")
        )
        assertFalse(ok.isExpired(now))
    }

    @Test
    fun `session without expires_at is not expired`() {
        val session = GrokBuildAuth.Session(accessToken = "tok", expiresAt = null)
        assertFalse(session.isExpired())
    }

    @Test
    fun `parseSession blank key is not usable`() {
        val json = """
            {
              "https://auth.x.ai::x": {
                "key": "   ",
                "expires_at": "2099-01-01T00:00:00Z"
              }
            }
        """.trimIndent()
        assertNull(GrokBuildAuth.parseSession(json))
    }

    @Test
    fun `client constants match cli-chat-proxy contract`() {
        assertEquals("xai-grok-cli", GrokBuildAuth.TOKEN_AUTH_VALUE)
        assertEquals("X-XAI-Token-Auth", GrokBuildAuth.TOKEN_AUTH_HEADER)
        assertEquals("x-grok-client-version", GrokBuildAuth.CLIENT_VERSION_HEADER)
        assertEquals("x-grok-model-override", GrokBuildAuth.MODEL_OVERRIDE_HEADER)
        assertEquals("x-grok-client-surface", GrokBuildAuth.CLIENT_SURFACE_HEADER)
        assertEquals("waryway-gab-plugin", GrokBuildAuth.CLIENT_SURFACE_VALUE)
        assertTrue(GrokBuildAuth.CLIENT_VERSION.isNotBlank())
        assertEquals("xai-grok-build/${GrokBuildAuth.CLIENT_VERSION}", GrokBuildAuth.USER_AGENT)
    }

    @Test
    fun `requestHeaders includes Authorization TokenAuth version surface and User-Agent`() {
        val headers = GrokBuildAuth.requestHeaders("access-token-abc", modelForOverride = null)
        assertEquals("Bearer access-token-abc", headers["Authorization"])
        assertEquals(GrokBuildAuth.TOKEN_AUTH_VALUE, headers[GrokBuildAuth.TOKEN_AUTH_HEADER])
        assertEquals(GrokBuildAuth.CLIENT_VERSION, headers[GrokBuildAuth.CLIENT_VERSION_HEADER])
        assertEquals(GrokBuildAuth.CLIENT_SURFACE_VALUE, headers[GrokBuildAuth.CLIENT_SURFACE_HEADER])
        assertEquals(GrokBuildAuth.USER_AGENT, headers["User-Agent"])
        assertFalse(headers.containsKey(GrokBuildAuth.MODEL_OVERRIDE_HEADER))
    }

    @Test
    fun `requestHeaders with non-blank model includes model override`() {
        val headers = GrokBuildAuth.requestHeaders("tok", modelForOverride = "grok-4")
        assertEquals("grok-4", headers[GrokBuildAuth.MODEL_OVERRIDE_HEADER])
        assertEquals("Bearer tok", headers["Authorization"])
        assertEquals(GrokBuildAuth.TOKEN_AUTH_VALUE, headers[GrokBuildAuth.TOKEN_AUTH_HEADER])
        assertEquals(GrokBuildAuth.CLIENT_VERSION, headers[GrokBuildAuth.CLIENT_VERSION_HEADER])
        assertEquals(GrokBuildAuth.CLIENT_SURFACE_VALUE, headers[GrokBuildAuth.CLIENT_SURFACE_HEADER])
    }

    @Test
    fun `requestHeaders trims model and omits override when blank`() {
        val withSpaces = GrokBuildAuth.requestHeaders("tok", modelForOverride = "  grok-beta  ")
        assertEquals("grok-beta", withSpaces[GrokBuildAuth.MODEL_OVERRIDE_HEADER])

        val blank = GrokBuildAuth.requestHeaders("tok", modelForOverride = "   ")
        assertFalse(blank.containsKey(GrokBuildAuth.MODEL_OVERRIDE_HEADER))

        val empty = GrokBuildAuth.requestHeaders("tok", modelForOverride = "")
        assertFalse(empty.containsKey(GrokBuildAuth.MODEL_OVERRIDE_HEADER))
    }
}
