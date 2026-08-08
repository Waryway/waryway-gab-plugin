package com.waryway.gab.ui

import com.waryway.gab.client.GabClient
import com.waryway.gab.client.GrokBuildAuthRecovery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for pure [GrokBuildSendUx] send-path formatters.
 *
 * Auth-string content is owned by [GrokBuildAuthRecovery]; these tests assert
 * delegation (same strings) plus SendUx-owned network / formatFailure mapping.
 */
class GrokBuildSendUxTest {

    @Test
    fun `coachingMissingSession delegates to AuthRecovery`() {
        val path = "C:\\Users\\x\\.grok\\auth.json"
        assertEquals(
            GrokBuildAuthRecovery.coachingMissingSession(authPath = path),
            GrokBuildSendUx.coachingMissingSession(authPath = path)
        )
        val msg = GrokBuildSendUx.coachingMissingSession(authPath = path)
        assertTrue(msg.contains(GrokBuildSendUx.LOGIN_CMD), msg)
        assertTrue(msg.contains(path), msg)
        assertFalse(msg.lowercase().contains("expired"), msg)
    }

    @Test
    fun `coachingExpiredSession delegates and is distinct from missing`() {
        val path = GrokBuildSendUx.DEFAULT_AUTH_PATH_DISPLAY
        val expired = GrokBuildSendUx.coachingExpiredSession(email = "user@example.com", authPath = path)
        val missing = GrokBuildSendUx.coachingMissingSession(authPath = path)
        assertEquals(
            GrokBuildAuthRecovery.coachingExpiredSession(email = "user@example.com", authPath = path),
            expired
        )
        assertTrue(expired != missing)
        assertTrue(expired.contains("expired"), expired.lowercase())
        assertTrue(expired.contains("user@example.com"), expired)
        assertTrue(expired.contains(GrokBuildSendUx.LOGIN_CMD), expired)
    }

    @Test
    fun `coachingExpiredSession without email still coaches re-login`() {
        val msg = GrokBuildSendUx.coachingExpiredSession(email = null)
        assertTrue(msg.contains("expired"), msg.lowercase())
        assertTrue(msg.contains(GrokBuildSendUx.LOGIN_CMD), msg)
        assertFalse(msg.contains("()"), msg)
    }

    @Test
    fun `formatAuthFailure maps HTTP 401 via AuthRecovery`() {
        val msg = GrokBuildSendUx.formatAuthFailure(
            message = "Chat failed: HTTP 401",
            httpStatus = 401
        )
        assertNotNull(msg)
        assertEquals(
            GrokBuildAuthRecovery.formatAuthFailure(
                message = "Chat failed: HTTP 401",
                httpStatus = 401
            ),
            msg
        )
        assertTrue(msg.contains(GrokBuildSendUx.LOGIN_CMD), msg)
        assertTrue(msg.contains("401"), msg)
    }

    @Test
    fun `formatAuthFailure maps HTTP 403 from message alone`() {
        val msg = GrokBuildSendUx.formatAuthFailure(
            message = "Request failed: HTTP 403",
            httpStatus = null
        )
        assertNotNull(msg)
        assertTrue(msg.contains(GrokBuildSendUx.LOGIN_CMD), msg)
        assertTrue(msg.contains("403") || msg.lowercase().contains("auth"), msg)
    }

    @Test
    fun `formatAuthFailure maps unauthorized body hint`() {
        val msg = GrokBuildSendUx.formatAuthFailure(
            message = "Chat failed",
            httpStatus = null,
            body = """{"error":"unauthorized"}"""
        )
        assertNotNull(msg)
        assertTrue(msg.contains(GrokBuildSendUx.LOGIN_CMD), msg)
    }

    @Test
    fun `formatAuthFailure returns null for non-auth HTTP errors`() {
        assertNull(
            GrokBuildSendUx.formatAuthFailure(
                message = "Chat failed: HTTP 500",
                httpStatus = 500,
                body = "internal error"
            )
        )
        assertNull(
            GrokBuildSendUx.formatAuthFailure(
                message = "Chat failed: HTTP 429",
                httpStatus = 429
            )
        )
        assertNull(
            GrokBuildSendUx.formatAuthFailure(
                message = "Chat failed: HTTP 404",
                httpStatus = 404
            )
        )
    }

    @Test
    fun `isAuthClassFailure delegates to AuthRecovery`() {
        assertEquals(
            GrokBuildAuthRecovery.isAuthClassFailure(httpStatus = 401),
            GrokBuildSendUx.isAuthClassFailure(null, 401, null)
        )
        assertFalse(GrokBuildSendUx.isAuthClassFailure(null, null, null))
        assertFalse(GrokBuildSendUx.isAuthClassFailure("rate limited", 429, null))
        assertTrue(GrokBuildSendUx.isAuthClassFailure(null, 403, null))
    }

    @Test
    fun `networkMessage names proxy not LocalLLM start script`() {
        val msg = GrokBuildSendUx.networkMessage(detail = "Connection refused")
        assertTrue(msg.contains("cli-chat-proxy"), msg)
        assertTrue(msg.contains("Connection refused"), msg)
        assertFalse(msg.contains(LocalLlmSendUx.START_HINT), msg)
        assertFalse(msg.contains("LocalLLM"), msg)
        assertFalse(msg.contains("localllm-run"), msg.lowercase())
    }

    @Test
    fun `normalizeProxyRoot strips v1`() {
        assertEquals(
            "https://cli-chat-proxy.grok.com",
            GrokBuildSendUx.normalizeProxyRoot("https://cli-chat-proxy.grok.com/v1")
        )
        assertEquals(
            "https://cli-chat-proxy.grok.com",
            GrokBuildSendUx.normalizeProxyRoot("https://cli-chat-proxy.grok.com/v1/")
        )
        assertEquals(
            GrokBuildSendUx.DEFAULT_PROXY_ROOT,
            GrokBuildSendUx.normalizeProxyRoot("   ")
        )
    }

    @Test
    fun `formatFailure auth-class uses recovery copy`() {
        val err = GabClient.GabApiException("Chat failed: HTTP 401", """{"error":"unauthorized"}""")
        val msg = GrokBuildSendUx.formatFailure(err)
        assertTrue(msg.contains(GrokBuildSendUx.LOGIN_CMD), msg)
        assertTrue(msg.contains("401") || msg.lowercase().contains("auth"), msg)
        assertFalse(msg.startsWith("Error:"), msg)
    }

    @Test
    fun `formatFailure connection uses network guidance not LocalLLM`() {
        val err = RuntimeException("Connection refused")
        val msg = GrokBuildSendUx.formatFailure(err)
        assertTrue(msg.contains("unreachable"), msg)
        assertTrue(msg.contains("cli-chat-proxy"), msg)
        assertTrue(msg.contains("Connection refused"), msg)
        assertFalse(msg.contains(LocalLlmSendUx.START_HINT), msg)
        assertFalse(msg.contains("LocalLLM"), msg)
    }

    @Test
    fun `formatFailure non-auth non-connect keeps Error prefix`() {
        val msg = GrokBuildSendUx.formatFailure(
            RuntimeException("Chat failed: HTTP 500: boom")
        )
        assertEquals("Error: Chat failed: HTTP 500: boom", msg)
    }

    @Test
    fun `formatFailure generic HTTP includes short body snippet`() {
        val err = GabClient.GabApiException(
            "Chat failed: HTTP 500",
            """{"error":"internal\n  boom"}"""
        )
        val msg = GrokBuildSendUx.formatFailure(err)
        assertTrue(msg.startsWith("Error:"), msg)
        assertTrue(msg.contains("HTTP 500"), msg)
        assertTrue(msg.contains("internal boom") || msg.contains("internal"), msg)
        assertFalse(msg.contains("\n"), msg)
    }

    @Test
    fun `formatFailure SSE stream error preserves server message`() {
        val err = GabClient.GabApiException("SSE stream error: rate limited")
        val msg = GrokBuildSendUx.formatFailure(err)
        assertEquals("Error: SSE stream error: rate limited", msg)
    }

    @Test
    fun `formatFailure null yields non-empty fallback`() {
        val msg = GrokBuildSendUx.formatFailure(null)
        assertTrue(msg.isNotBlank())
        assertTrue(msg.startsWith("Error:"))
    }

    @Test
    fun `formatFailure extracts status from explicit httpStatus param`() {
        val msg = GrokBuildSendUx.formatFailure(
            error = RuntimeException("request rejected"),
            httpStatus = 403,
            body = "forbidden"
        )
        // 403 alone is auth-class in AuthRecovery; body "forbidden" alone may not be.
        assertTrue(msg.contains(GrokBuildSendUx.LOGIN_CMD), msg)
        assertFalse(msg.startsWith("Error:"), msg)
    }

    @Test
    fun `extractHttpStatus parses Chat failed messages`() {
        assertEquals(401, GrokBuildSendUx.extractHttpStatus("Chat failed: HTTP 401"))
        assertEquals(403, GrokBuildSendUx.extractHttpStatus("Request failed: HTTP 403"))
        assertNull(GrokBuildSendUx.extractHttpStatus("no status here"))
        assertNull(GrokBuildSendUx.extractHttpStatus(null))
    }

    @Test
    fun `isUnreachableError aligns with LocalLlmSendUx detection`() {
        assertTrue(GrokBuildSendUx.isUnreachableError("Connection refused"))
        assertTrue(GrokBuildSendUx.isUnreachableError("Unknown host: bad.local"))
        assertFalse(GrokBuildSendUx.isUnreachableError("Chat failed: HTTP 500"))
    }

    @Test
    fun `missing and expired coaching strings differ`() {
        val missing = GrokBuildSendUx.coachingMissingSession()
        val expired = GrokBuildSendUx.coachingExpiredSession()
        assertTrue(missing != expired)
        assertTrue(missing.contains("No Grok Build session"), missing)
        assertTrue(expired.contains("expired"), expired.lowercase())
    }
}
