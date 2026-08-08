package com.waryway.gab.ui

import com.waryway.gab.chat.LocalLlmAgentSession
import com.waryway.gab.client.GabClient
import com.waryway.gab.model.ModelProvider
import java.net.http.HttpTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentTimeoutUxTest {

    @Test
    fun `detects agent poll timeout message`() {
        assertTrue(
            AgentTimeoutUx.isTimeoutMessage(
                "Agent run timed out after 1800s (id=abc, state=planning)"
            )
        )
    }

    @Test
    fun `detects chat stream timeout message`() {
        assertTrue(AgentTimeoutUx.isTimeoutMessage("Chat timed out after 900s (provider=Grok Build)."))
        assertTrue(AgentTimeoutUx.isTimeoutMessage("request timed out"))
        assertTrue(AgentTimeoutUx.isTimeoutMessage("HttpTimeoutException"))
    }

    @Test
    fun `connect timeouts are not stream timeouts`() {
        assertFalse(AgentTimeoutUx.isTimeoutMessage("HTTP connect timed out"))
        assertFalse(AgentTimeoutUx.isTimeoutMessage("connect timed out"))
        assertFalse(AgentTimeoutUx.isTimeoutMessage("Connection refused"))
    }

    @Test
    fun `HttpTimeoutException and AgentTimeoutException are timeout errors`() {
        assertTrue(AgentTimeoutUx.isTimeoutError(HttpTimeoutException("request timed out")))
        assertTrue(
            AgentTimeoutUx.isTimeoutError(
                LocalLlmAgentSession.AgentTimeoutException("Agent run timed out after 40s", "run-1")
            )
        )
        assertTrue(
            AgentTimeoutUx.isTimeoutError(
                GabClient.GabApiException(
                    "Chat timed out after 900s",
                    kind = GabClient.GabApiException.Kind.TIMEOUT
                )
            )
        )
        assertFalse(AgentTimeoutUx.isTimeoutError(RuntimeException("HTTP 500")))
    }

    @Test
    fun `extractTimeoutSeconds parses after Ns`() {
        assertEquals(
            1800L,
            AgentTimeoutUx.extractTimeoutSeconds("Agent run timed out after 1800s (id=x)")
        )
        assertEquals(
            900L,
            AgentTimeoutUx.extractTimeoutSeconds("Chat timed out after 900s (provider=Gab AI).")
        )
    }

    @Test
    fun `formatTimeoutFailure local agent is recovery oriented`() {
        val msg = AgentTimeoutUx.formatTimeoutFailure(
            ModelProvider.LOCAL_LLM,
            agentMode = true,
            timeoutSeconds = 600
        )
        assertTrue(msg.contains("timed out"), msg)
        assertTrue(msg.contains("Agent poll") || msg.contains("poll timeout") || msg.contains("timeout"), msg)
        assertTrue(msg.contains("Chat") || msg.contains("cpp"), msg)
        assertFalse(msg.startsWith("Error:"), msg)
    }

    @Test
    fun `formatTimeoutFailure grok build mentions stream and retry`() {
        val msg = AgentTimeoutUx.formatTimeoutFailure(
            ModelProvider.GROK_BUILD,
            timeoutSeconds = 900
        )
        assertTrue(msg.contains("Grok Build"), msg)
        assertTrue(msg.contains("timed out"), msg)
        assertTrue(msg.contains("Retry") || msg.contains("retry"), msg)
        assertTrue(msg.contains("stream timeout") || msg.contains("Chat stream"), msg)
    }

    @Test
    fun `formatDuration formats minutes and seconds`() {
        assertEquals("45s", AgentTimeoutUx.formatDuration(45))
        assertEquals("15m", AgentTimeoutUx.formatDuration(900))
        assertEquals("15m 30s", AgentTimeoutUx.formatDuration(930))
    }

    @Test
    fun `mergePartialWithTimeout keeps partial before recovery`() {
        val merged = AgentTimeoutUx.mergePartialWithTimeout(
            partialContent = "Hello world from the model",
            timeoutMessage = "Grok Build stream timed out after 15m — retry."
        )
        assertTrue(merged.startsWith("Hello world from the model"), merged)
        assertTrue(merged.contains("Timed out (partial reply kept)"), merged)
        assertTrue(merged.contains("Grok Build stream timed out"), merged)
    }

    @Test
    fun `mergePartialWithTimeout without partial is recovery only`() {
        val merged = AgentTimeoutUx.mergePartialWithTimeout(null, "timeout recovery")
        assertEquals("timeout recovery", merged)
    }

    @Test
    fun `formatTimeoutFailureWithPartial includes partial for Grok`() {
        val msg = AgentTimeoutUx.formatTimeoutFailureWithPartial(
            provider = ModelProvider.GROK_BUILD,
            timeoutSeconds = 900,
            partialContent = "Partial answer that was streaming"
        )
        assertTrue(msg.contains("Partial answer that was streaming"), msg)
        assertTrue(msg.contains("timed out"), msg)
        assertTrue(msg.contains("partial reply kept"), msg.lowercase())
    }

    @Test
    fun `formatTimeoutFailure local chat mentions continue and stream settings`() {
        val msg = AgentTimeoutUx.formatTimeoutFailure(
            ModelProvider.LOCAL_LLM,
            agentMode = false,
            timeoutSeconds = 1800
        )
        assertTrue(msg.contains("timed out"), msg)
        assertTrue(msg.contains("Chat stream") || msg.contains("stream budget"), msg)
        assertTrue(msg.contains("continue") || msg.contains("retry"), msg.lowercase())
        assertFalse(msg.contains("401"), msg)
        assertFalse(msg.contains("unreachable"), msg)
    }

    @Test
    fun `AUTH exception is not a timeout error`() {
        val auth = GabClient.GabApiException(
            message = "Chat failed: HTTP 401 — invalid API key",
            body = """{"error":{"type":"invalid_api_key"}}""",
            kind = GabClient.GabApiException.Kind.AUTH
        )
        assertFalse(AgentTimeoutUx.isTimeoutError(auth))
        assertFalse(AgentTimeoutUx.isTimeoutMessage(auth.message))
    }

    @Test
    fun `local chat timeout with partial keeps text and recovery`() {
        val msg = AgentTimeoutUx.formatTimeoutFailureWithPartial(
            provider = ModelProvider.LOCAL_LLM,
            agentMode = false,
            timeoutSeconds = 900,
            partialContent = "Halfway done explaining"
        )
        assertTrue(msg.startsWith("Halfway done explaining"), msg)
        assertTrue(msg.contains("partial reply kept"), msg.lowercase())
        assertTrue(msg.contains("continue") || msg.contains("retry"), msg.lowercase())
    }
}
