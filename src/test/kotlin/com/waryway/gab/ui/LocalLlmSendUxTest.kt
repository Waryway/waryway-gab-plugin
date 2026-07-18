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
                "Agent run timed out after 480s (id=abc-123)"
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
