package com.waryway.gab.client

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GrokBuildAuthRecoveryTest {

    @Test
    fun `coachingMissingSession mentions grok login and auth path`() {
        val path = "/tmp/fake/.grok/auth.json"
        val text = GrokBuildAuthRecovery.coachingMissingSession(authPath = path)
        assertContains(text, "grok login")
        assertContains(text, path)
        assertTrue(text.contains("No Grok Build session", ignoreCase = true) ||
            text.contains("session found", ignoreCase = true))
    }

    @Test
    fun `coachingExpiredSession is distinct from missing and includes email`() {
        val path = "C:\\Users\\x\\.grok\\auth.json"
        val expired = GrokBuildAuthRecovery.coachingExpiredSession(
            email = "user@example.com",
            authPath = path
        )
        val missing = GrokBuildAuthRecovery.coachingMissingSession(authPath = path)
        assertContains(expired, "expired", ignoreCase = true)
        assertContains(expired, "grok login")
        assertContains(expired, "user@example.com")
        assertContains(expired, path)
        assertContains(missing, "grok login")
        // Distinct wording: expired talks about refresh/again; missing about not found
        assertTrue(expired != missing)
        assertFalse(expired.contains("No Grok Build session", ignoreCase = true))
    }

    @Test
    fun `coachingExpiredSession works without email`() {
        val text = GrokBuildAuthRecovery.coachingExpiredSession(email = null, authPath = "~/.grok/auth.json")
        assertContains(text, "expired", ignoreCase = true)
        assertContains(text, "grok login")
        assertFalse(text.contains("()"))
    }

    @Test
    fun `formatAuthFailure maps HTTP 401`() {
        val text = GrokBuildAuthRecovery.formatAuthFailure(
            message = "Chat failed",
            httpStatus = 401,
            body = null
        )
        assertNotNull(text)
        assertContains(text, "401")
        assertContains(text, "grok login")
    }

    @Test
    fun `formatAuthFailure maps HTTP 403`() {
        val text = GrokBuildAuthRecovery.formatAuthFailure(httpStatus = 403)
        assertNotNull(text)
        assertContains(text, "403")
        assertContains(text, "grok login")
    }

    @Test
    fun `formatAuthFailure maps unauthorized message without status`() {
        val text = GrokBuildAuthRecovery.formatAuthFailure(
            message = "Error: unauthorized — invalid_token"
        )
        assertNotNull(text)
        assertContains(text, "grok login")
    }

    @Test
    fun `formatAuthFailure maps HTTP 401 embedded in message text`() {
        val text = GrokBuildAuthRecovery.formatAuthFailure(
            message = "Chat failed: HTTP 401"
        )
        assertNotNull(text)
        assertContains(text, "grok login")
    }

    @Test
    fun `formatAuthFailure maps session expired body hint`() {
        val text = GrokBuildAuthRecovery.formatAuthFailure(
            message = null,
            httpStatus = null,
            body = """{"error":"session_expired","message":"token expired"}"""
        )
        assertNotNull(text)
        assertContains(text, "grok login")
    }

    @Test
    fun `formatAuthFailure returns null for non-auth HTTP errors`() {
        assertNull(GrokBuildAuthRecovery.formatAuthFailure(message = "server error", httpStatus = 500))
        assertNull(GrokBuildAuthRecovery.formatAuthFailure(message = "bad gateway", httpStatus = 502))
        assertNull(GrokBuildAuthRecovery.formatAuthFailure(message = "not found", httpStatus = 404))
        assertNull(GrokBuildAuthRecovery.formatAuthFailure(message = "rate limited", httpStatus = 429))
    }

    @Test
    fun `formatAuthFailure returns null for blank unrelated messages`() {
        assertNull(GrokBuildAuthRecovery.formatAuthFailure(message = null, httpStatus = null, body = null))
        assertNull(GrokBuildAuthRecovery.formatAuthFailure(message = "connection reset by peer"))
        assertNull(GrokBuildAuthRecovery.formatAuthFailure(message = "timeout waiting for stream"))
    }

    @Test
    fun `classifySession maps missing expired usable`() {
        assertEquals(
            GrokBuildAuthRecovery.SessionState.MISSING,
            GrokBuildAuthRecovery.classifySession(null)
        )
        assertEquals(
            GrokBuildAuthRecovery.SessionState.MISSING,
            GrokBuildAuthRecovery.classifySession(
                GrokBuildAuth.Session(accessToken = "  ")
            )
        )
        assertEquals(
            GrokBuildAuthRecovery.SessionState.EXPIRED,
            GrokBuildAuthRecovery.classifySession(
                GrokBuildAuth.Session(
                    accessToken = "tok",
                    expiresAt = Instant.parse("2020-01-01T00:00:00Z")
                )
            )
        )
        assertEquals(
            GrokBuildAuthRecovery.SessionState.USABLE,
            GrokBuildAuthRecovery.classifySession(
                GrokBuildAuth.Session(
                    accessToken = "tok",
                    expiresAt = Instant.parse("2099-01-01T00:00:00Z")
                )
            )
        )
    }

    @Test
    fun `isAuthClassFailure is true only for auth-class signals`() {
        assertTrue(GrokBuildAuthRecovery.isAuthClassFailure(httpStatus = 401))
        assertTrue(GrokBuildAuthRecovery.isAuthClassFailure(httpStatus = 403))
        assertFalse(GrokBuildAuthRecovery.isAuthClassFailure(httpStatus = 500))
        assertFalse(GrokBuildAuthRecovery.isAuthClassFailure(message = "model not found"))
    }
}
