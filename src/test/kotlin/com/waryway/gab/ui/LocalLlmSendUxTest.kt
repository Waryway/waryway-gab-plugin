package com.waryway.gab.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Unit tests for pure [LocalLlmSendUx] labels and error builders. */
class LocalLlmSendUxTest {

    @Test
    fun `sendPathLabel agent dry-run`() {
        assertEquals("Agent · dry-run", LocalLlmSendUx.sendPathLabel(agentMode = true, dryRun = true))
    }

    @Test
    fun `sendPathLabel agent APPLY`() {
        assertEquals("Agent · APPLY", LocalLlmSendUx.sendPathLabel(agentMode = true, dryRun = false))
    }

    @Test
    fun `sendPathLabel chat when agent mode off`() {
        assertEquals("Chat", LocalLlmSendUx.sendPathLabel(agentMode = false, dryRun = true))
        assertEquals("Chat", LocalLlmSendUx.sendPathLabel(agentMode = false, dryRun = false))
    }

    @Test
    fun `sendPathStatusLine prefixes Next Send`() {
        assertEquals(
            "Next Send: Agent · dry-run",
            LocalLlmSendUx.sendPathStatusLine(agentMode = true, dryRun = true)
        )
        assertEquals(
            "Next Send: Chat",
            LocalLlmSendUx.sendPathStatusLine(agentMode = false, dryRun = true)
        )
    }

    @Test
    fun `tooltips mention route families`() {
        val agentTip = LocalLlmSendUx.sendPathToolTip(agentMode = true, dryRun = true)
        assertTrue(agentTip.contains("/api/agent"), agentTip)
        assertTrue(agentTip.contains("dryRun=true"), agentTip)

        val applyTip = LocalLlmSendUx.sendPathToolTip(agentMode = true, dryRun = false)
        assertTrue(applyTip.contains("dryRun=false"), applyTip)

        val chatTip = LocalLlmSendUx.sendPathToolTip(agentMode = false, dryRun = true)
        assertTrue(chatTip.contains("/v1/chat/completions"), chatTip)
    }

    @Test
    fun `blankBaseUrlMessage is config not reachability`() {
        val msg = LocalLlmSendUx.blankBaseUrlMessage()
        assertTrue(msg.contains("base URL is blank"), msg)
        assertTrue(msg.contains("Plugin Settings"), msg)
        assertFalse(msg.contains("not reachable"), msg)
        assertFalse(msg.contains("not running"), msg)
    }

    @Test
    fun `offlineMessage is ops with start hint`() {
        val msg = LocalLlmSendUx.offlineMessage("http://127.0.0.1:7400")
        assertTrue(msg.contains("not running"), msg)
        assertTrue(msg.contains("127.0.0.1:7400"), msg)
        assertTrue(msg.contains(LocalLlmSendUx.START_HINT), msg)
    }

    @Test
    fun `normalizeRootUrl strips v1 and trailing slash`() {
        assertEquals(
            "http://127.0.0.1:7400",
            LocalLlmSendUx.normalizeRootUrl("http://127.0.0.1:7400/v1")
        )
        assertEquals(
            "http://127.0.0.1:7400",
            LocalLlmSendUx.normalizeRootUrl("http://127.0.0.1:7400/v1/")
        )
        assertEquals(
            "http://127.0.0.1:7400",
            LocalLlmSendUx.normalizeRootUrl("http://127.0.0.1:7400/")
        )
        assertEquals(
            LocalLlmSendUx.DEFAULT_ROOT,
            LocalLlmSendUx.normalizeRootUrl("   ")
        )
    }

    @Test
    fun `isUnreachableError matches connect failures`() {
        assertTrue(LocalLlmSendUx.isUnreachableError("Connection refused: getsockopt"))
        assertTrue(LocalLlmSendUx.isUnreachableError("HTTP connect timed out"))
        assertTrue(LocalLlmSendUx.isUnreachableError("java.net.ConnectException: Connection refused"))
        assertTrue(LocalLlmSendUx.isUnreachableError("Unknown host: bad.local"))
    }

    @Test
    fun `isUnreachableError ignores agent poll timeout`() {
        assertFalse(
            LocalLlmSendUx.isUnreachableError(
                "Agent run timed out after 1800s (id=abc-123)"
            )
        )
    }

    @Test
    fun `formatFailure connection uses offline guidance`() {
        val err = RuntimeException("Connection refused")
        val msg = LocalLlmSendUx.formatFailure(
            err,
            agentMode = true,
            rootUrl = "http://127.0.0.1:7400/v1"
        )
        assertTrue(msg.contains("unreachable"), msg)
        assertTrue(msg.contains("/api/agent"), msg)
        assertTrue(msg.contains(LocalLlmSendUx.START_HINT), msg)
        assertTrue(msg.contains("127.0.0.1:7400"), msg)
    }

    @Test
    fun `formatFailure non-connect keeps Error prefix`() {
        val msg = LocalLlmSendUx.formatFailure(
            RuntimeException("HTTP 500: boom"),
            agentMode = false
        )
        assertEquals("Error: HTTP 500: boom", msg)
    }

    @Test
    fun `formatFailure HTTP 401 surfaces auth not blank silence`() {
        val err = com.waryway.gab.client.GabClient.GabApiException(
            message = "Chat failed: HTTP 401 — invalid API key",
            body = """{"error":{"message":"invalid API key","type":"invalid_api_key"}}""",
            kind = com.waryway.gab.client.GabClient.GabApiException.Kind.AUTH
        )
        val msg = LocalLlmSendUx.formatFailure(err, agentMode = false)
        assertTrue(msg.contains("401"), msg)
        assertTrue(msg.contains("auth") || msg.contains("API key"), msg)
        assertTrue(msg.contains(LocalLlmSendUx.DEFAULT_API_KEY), msg)
        assertFalse(msg.startsWith("Error: Chat failed"), msg)
    }

    @Test
    fun `formatAuthFailure null when not auth`() {
        assertEquals(null, LocalLlmSendUx.formatAuthFailure(RuntimeException("HTTP 500: boom")))
        assertFalse(LocalLlmSendUx.isAuthError(RuntimeException("Connection refused")))
    }

    @Test
    fun `isAuthError matches invalid_api_key body`() {
        val err = com.waryway.gab.client.GabClient.GabApiException(
            "Chat failed: HTTP 401",
            """{"error":{"message":"invalid API key","type":"invalid_api_key"}}"""
        )
        assertTrue(LocalLlmSendUx.isAuthError(err))
        val formatted = LocalLlmSendUx.formatAuthFailure(err, agentMode = false)
        assertTrue(formatted != null && formatted.contains("invalid API key"), formatted ?: "null")
    }

    @Test
    fun `formatFailure agent timeout is recovery oriented not bare Error`() {
        val err = RuntimeException(
            "Agent run timed out after 1800s (id=abc, state=planning, step=0/30)"
        )
        val msg = LocalLlmSendUx.formatFailure(err, agentMode = true)
        assertTrue(msg.contains("timed out"), msg)
        assertTrue(
            msg.contains("Agent poll") || msg.contains("poll timeout") || msg.contains("Chat"),
            msg
        )
        assertFalse(msg.startsWith("Error: Agent run timed out"), msg)
        assertFalse(msg.contains("unreachable"), msg)
    }

    @Test
    fun `formatFailure chat stream timeout mentions stream budget`() {
        val err = RuntimeException("Chat timed out after 900s (provider=Local LLM).")
        val msg = LocalLlmSendUx.formatFailure(err, agentMode = false)
        assertTrue(msg.contains("timed out"), msg)
        assertTrue(msg.contains("stream") || msg.contains("Chat stream") || msg.contains("generation"), msg)
        assertFalse(msg.contains("unreachable"), msg)
    }

    @Test
    fun `formatFailure timeout with partialContent keeps streamed text`() {
        val err = com.waryway.gab.client.GabClient.GabApiException(
            message = "Chat timed out after 900s (provider=Local LLM).",
            kind = com.waryway.gab.client.GabClient.GabApiException.Kind.TIMEOUT,
            partialContent = "I was halfway through explaining the fix"
        )
        val msg = LocalLlmSendUx.formatFailure(err, agentMode = false)
        assertTrue(msg.contains("I was halfway through explaining the fix"), msg)
        assertTrue(msg.contains("partial reply kept"), msg.lowercase())
        assertTrue(msg.contains("timed out"), msg)
        assertTrue(
            msg.contains("continue", ignoreCase = true) || msg.contains("retry", ignoreCase = true),
            msg
        )
    }

    @Test
    fun `formatFailure auth is never labeled as timeout`() {
        val err = com.waryway.gab.client.GabClient.GabApiException(
            message = "Chat failed: HTTP 401 — invalid API key",
            body = """{"error":{"message":"invalid API key","type":"invalid_api_key"}}""",
            kind = com.waryway.gab.client.GabClient.GabApiException.Kind.AUTH
        )
        val msg = LocalLlmSendUx.formatFailure(err, agentMode = false)
        assertTrue(msg.contains("401") || msg.contains("auth"), msg.lowercase())
        assertFalse(msg.contains("stream budget"), msg.lowercase())
        assertFalse(msg.contains("partial reply kept"), msg.lowercase())
        assertFalse(AgentTimeoutUx.isTimeoutError(err))
    }

    @Test
    fun `formatFailure connect transport is unreachable not stream timeout`() {
        val err = com.waryway.gab.client.GabClient.GabApiException(
            message = "Chat transport failed: Connection refused",
            body = "Connection refused",
            kind = com.waryway.gab.client.GabClient.GabApiException.Kind.TRANSPORT
        )
        val msg = LocalLlmSendUx.formatFailure(err, agentMode = false)
        assertTrue(msg.contains("unreachable"), msg)
        assertFalse(msg.contains("stream budget"), msg.lowercase())
        assertFalse(msg.contains("Chat stream timeout"), msg)
    }

    @Test
    fun `stillGeneratingStatus mentions go-cpu and elapsed`() {
        val msg = LocalLlmSendUx.stillGeneratingStatus(
            elapsedSeconds = 90,
            streamBudgetSeconds = 1800
        )
        assertTrue(msg.contains("Still generating"), msg)
        assertTrue(msg.contains("90s") || msg.contains("1m 30s"), msg)
        assertTrue(msg.contains("go-cpu") || msg.contains("90s+"), msg)
        assertTrue(msg.contains("30m") || msg.contains("1800"), msg)
        assertEquals(30_000L, LocalLlmSendUx.STILL_GENERATING_INTERVAL_MS)
    }

    @Test
    fun `formatFailure chat path names v1`() {
        val msg = LocalLlmSendUx.formatFailure(
            RuntimeException("Connection refused"),
            agentMode = false,
            rootUrl = "http://127.0.0.1:7400"
        )
        assertTrue(msg.contains("/v1"), msg)
        assertFalse(msg.contains("/api/agent"), msg)
    }

    @Test
    fun `agent mode check label mentions api agent`() {
        assertTrue(LocalLlmSendUx.AGENT_MODE_CHECK_LABEL.contains("/api/agent"))
    }
}
