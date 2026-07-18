package com.waryway.gab.ui

import com.waryway.gab.client.GabClient
import com.waryway.gab.model.ModelProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Chat-path failure UX: thin facade over [GrokBuildSendUx] / [LocalLlmSendUx].
 * Asserts recovery-oriented mapping without a second string catalog.
 */
class GrokBuildChatFailureUxTest {

    @Test
    fun `GROK_BUILD 401 maps to session login coaching`() {
        val err = GabClient.GabApiException("Chat failed: HTTP 401", """{"error":"unauthorized"}""")
        val msg = GrokBuildChatFailureUx.formatChatFailure(err, ModelProvider.GROK_BUILD)
        assertTrue(msg.contains(GrokBuildSendUx.LOGIN_CMD), msg)
        assertTrue(msg.contains("401") || msg.lowercase().contains("auth"), msg)
        assertFalse(msg.startsWith("Error:"), msg)
        // Same catalog as SendUx (no forked strings)
        assertEquals(GrokBuildSendUx.formatFailure(err), msg)
    }

    @Test
    fun `GROK_BUILD 403 maps to login recovery`() {
        val err = GabClient.GabApiException("Chat failed: HTTP 403", "forbidden")
        val msg = GrokBuildChatFailureUx.formatChatFailure(err, ModelProvider.GROK_BUILD)
        assertTrue(msg.contains(GrokBuildSendUx.LOGIN_CMD), msg)
        assertEquals(GrokBuildSendUx.formatFailure(err), msg)
    }

    @Test
    fun `GROK_BUILD connection refused is unreachable not LocalLLM`() {
        val err = RuntimeException("Connection refused")
        val msg = GrokBuildChatFailureUx.formatChatFailure(err, ModelProvider.GROK_BUILD)
        assertTrue(msg.contains("unreachable"), msg)
        assertTrue(msg.contains("cli-chat-proxy"), msg)
        assertFalse(msg.contains(LocalLlmSendUx.START_HINT), msg)
        assertFalse(msg.contains("LocalLLM"), msg)
    }

    @Test
    fun `GROK_BUILD unknown host is network style`() {
        val err = java.net.UnknownHostException("bad.local")
        val msg = GrokBuildChatFailureUx.formatChatFailure(err, ModelProvider.GROK_BUILD)
        assertTrue(msg.contains("unreachable"), msg)
        assertTrue(msg.contains("cli-chat-proxy"), msg)
        assertFalse(msg.contains(LocalLlmSendUx.START_HINT), msg)
    }

    @Test
    fun `GROK_BUILD HTTP 500 includes status and truncated body`() {
        val longBody = "x".repeat(300)
        val err = GabClient.GabApiException("Chat failed: HTTP 500", longBody)
        val msg = GrokBuildChatFailureUx.formatChatFailure(err, ModelProvider.GROK_BUILD)
        assertTrue(msg.startsWith("Error:"), msg)
        assertTrue(msg.contains("HTTP 500"), msg)
        assertTrue(msg.contains("…") || msg.length < longBody.length + 50, msg)
        val paren = Regex("""\((.+)\)""").find(msg)
        assertTrue(paren != null, "expected body snippet in parentheses: $msg")
        val snippet = paren?.groupValues?.get(1).orEmpty()
        assertTrue(snippet.length <= GrokBuildSendUx.MAX_BODY_SNIPPET + 1, snippet)
    }

    @Test
    fun `GROK_BUILD SSE stream error message preserved`() {
        val err = GabClient.GabApiException("SSE stream error: model overloaded")
        val msg = GrokBuildChatFailureUx.formatChatFailure(err, ModelProvider.GROK_BUILD)
        assertTrue(msg.contains("SSE stream error"), msg)
        assertTrue(msg.contains("model overloaded"), msg)
        assertTrue(msg.startsWith("Error:"), msg)
    }

    @Test
    fun `null error yields non-empty fallback`() {
        val msg = GrokBuildChatFailureUx.formatChatFailure(null, ModelProvider.GROK_BUILD)
        assertTrue(msg.isNotBlank(), msg)
        assertTrue(msg.contains("unknown") || msg.startsWith("Error:"), msg)
    }

    @Test
    fun `blank message GabApiException still non-empty`() {
        val err = GabClient.GabApiException("   ", null)
        val msg = GrokBuildChatFailureUx.formatChatFailure(err, ModelProvider.GROK_BUILD)
        assertTrue(msg.isNotBlank(), msg)
        assertTrue(msg.startsWith("Error:"), msg)
    }

    @Test
    fun `LOCAL_LLM defers to LocalLlmSendUx not GrokBuild copy`() {
        val err = RuntimeException("Connection refused")
        val msg = GrokBuildChatFailureUx.formatChatFailure(
            err,
            ModelProvider.LOCAL_LLM,
            rootUrl = "http://127.0.0.1:7400/v1"
        )
        val expected = LocalLlmSendUx.formatFailure(
            err,
            agentMode = false,
            rootUrl = "http://127.0.0.1:7400/v1"
        )
        assertEquals(expected, msg)
        assertTrue(msg.contains("LocalLLM"), msg)
        assertFalse(msg.contains("cli-chat-proxy"), msg)
        assertFalse(msg.contains(GrokBuildSendUx.LOGIN_CMD), msg)
    }

    @Test
    fun `other cloud providers get generic Error with optional body`() {
        val err = GabClient.GabApiException("Chat failed: HTTP 502", "bad gateway detail")
        val msg = GrokBuildChatFailureUx.formatChatFailure(err, ModelProvider.GAB_AI)
        assertTrue(msg.startsWith("Error:"), msg)
        assertTrue(msg.contains("HTTP 502"), msg)
        assertTrue(msg.contains("bad gateway detail"), msg)
        assertFalse(msg.contains(GrokBuildSendUx.LOGIN_CMD), msg)
        assertFalse(msg.contains("cli-chat-proxy"), msg)
    }

    @Test
    fun `shortBodyDetail collapses whitespace and truncates`() {
        assertEquals(null, GrokBuildSendUx.shortBodyDetail(null))
        assertEquals(null, GrokBuildSendUx.shortBodyDetail("  \n  "))
        assertEquals("hello world", GrokBuildSendUx.shortBodyDetail("hello\n  world"))
        val long = "a".repeat(250)
        val snip = GrokBuildSendUx.shortBodyDetail(long)!!
        assertTrue(snip.endsWith("…"), snip)
        assertTrue(snip.length <= GrokBuildSendUx.MAX_BODY_SNIPPET + 1, snip)
    }
}
