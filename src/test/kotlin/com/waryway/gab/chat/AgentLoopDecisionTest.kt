package com.waryway.gab.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pure JVM taxonomy tests for [decideAgentLoop] (wo-03-02).
 * No Project / IDE / network. Section-04 (wo-04-01-01) should extend session-level
 * coverage here rather than duplicating this matrix.
 */
class AgentLoopDecisionTest {

    private fun decide(
        cancelled: Boolean = false,
        content: String? = null,
        toolCallCount: Int = 0,
        finishReason: String? = null,
        iteration: Int = 1,
        maxIterations: Int = AgentSession.MAX_ITERATIONS,
        ambiguousRetryCount: Int = 0,
        maxAmbiguousRetries: Int = AgentSession.MAX_AMBIGUOUS_RETRIES,
    ): AgentLoopDecision = decideAgentLoop(
        cancelled = cancelled,
        content = content,
        toolCallCount = toolCallCount,
        finishReason = finishReason,
        iteration = iteration,
        maxIterations = maxIterations,
        ambiguousRetryCount = ambiguousRetryCount,
        maxAmbiguousRetries = maxAmbiguousRetries,
    )

    // --- cancel ---

    @Test
    fun `cancelled true yields CANCELLED and Stopped by user`() {
        val d = decide(cancelled = true, content = "partial", toolCallCount = 2, finishReason = "stop")
        assertEquals(AgentLoopAction.CANCELLED, d.action)
        assertEquals("cancel", d.reason)
        assertEquals("Stopped by user.", d.userMessage)
    }

    @Test
    fun `cancelled takes precedence over max iterations`() {
        val d = decide(cancelled = true, iteration = 99, maxIterations = 1)
        assertEquals(AgentLoopAction.CANCELLED, d.action)
        assertEquals("cancel", d.reason)
    }

    // --- tools continue ---

    @Test
    fun `non-empty toolCalls yields CONTINUE tool_calls`() {
        val d = decide(content = "calling tools", toolCallCount = 2, finishReason = "tool_calls")
        assertEquals(AgentLoopAction.CONTINUE, d.action)
        assertEquals("tool_calls", d.reason)
    }

    @Test
    fun `toolCalls with content is not terminal success`() {
        val d = decide(content = "I'll search", toolCallCount = 1, finishReason = "stop")
        assertEquals(AgentLoopAction.CONTINUE, d.action)
        assertEquals("tool_calls", d.reason)
    }

    // --- finish_reason tool use with empty tools ---

    @Test
    fun `finishReason tool_calls with empty tools retries when retries remain`() {
        val d = decide(
            content = null,
            toolCallCount = 0,
            finishReason = "tool_calls",
            ambiguousRetryCount = 0,
        )
        assertEquals(AgentLoopAction.RETRY_COMPLETION, d.action)
        assertEquals("incomplete_tool_calls", d.reason)
    }

    @Test
    fun `finishReason function_call case-insensitive with empty tools retries`() {
        val d = decide(
            content = "x",
            toolCallCount = 0,
            finishReason = "Function_Call",
            ambiguousRetryCount = 1,
        )
        assertEquals(AgentLoopAction.RETRY_COMPLETION, d.action)
        assertEquals("incomplete_tool_calls", d.reason)
    }

    @Test
    fun `finishReason tool_calls empty tools exhausted is TERMINAL_ERROR not success`() {
        val d = decide(
            content = null,
            toolCallCount = 0,
            finishReason = "tool_calls",
            ambiguousRetryCount = AgentSession.MAX_AMBIGUOUS_RETRIES,
        )
        assertEquals(AgentLoopAction.TERMINAL_ERROR, d.action)
        assertEquals("incomplete_tool_calls", d.reason)
        assertNotNull(d.userMessage)
        assertTrue(d.userMessage!!.contains("no tool calls", ignoreCase = true))
    }

    // --- terminal success (MCP-off / single-shot chat style) ---

    @Test
    fun `non-empty content no tools finish stop is TERMINAL_SUCCESS`() {
        val d = decide(content = "Hello from Grok", toolCallCount = 0, finishReason = "stop")
        assertEquals(AgentLoopAction.TERMINAL_SUCCESS, d.action)
        assertEquals("stop", d.reason)
        assertEquals("Hello from Grok", d.userMessage)
    }

    @Test
    fun `non-empty content no tools finish null is TERMINAL_SUCCESS soft stop`() {
        val d = decide(content = "Answer without finish_reason", toolCallCount = 0, finishReason = null)
        assertEquals(AgentLoopAction.TERMINAL_SUCCESS, d.action)
        assertEquals("stop", d.reason)
        assertEquals("Answer without finish_reason", d.userMessage)
    }

    @Test
    fun `non-empty content end_turn is TERMINAL_SUCCESS`() {
        val d = decide(content = "Done", toolCallCount = 0, finishReason = "end_turn")
        assertEquals(AgentLoopAction.TERMINAL_SUCCESS, d.action)
        assertEquals("end_turn", d.reason)
    }

    @Test
    fun `MCP-off style content only stop is success`() {
        // Single-shot chat when tools are off: content + stop, no tool path.
        val d = decide(content = "Here is the refactored code.", toolCallCount = 0, finishReason = "stop")
        assertEquals(AgentLoopAction.TERMINAL_SUCCESS, d.action)
        assertTrue(d.action != AgentLoopAction.CONTINUE)
        assertTrue(d.action != AgentLoopAction.RETRY_COMPLETION)
    }

    @Test
    fun `whitespace-only content treated as empty for ambiguous path`() {
        val d = decide(content = "   \n\t  ", toolCallCount = 0, finishReason = null, ambiguousRetryCount = 0)
        assertEquals(AgentLoopAction.RETRY_COMPLETION, d.action)
        assertEquals("empty_ambiguous", d.reason)
    }

    // --- empty / ambiguous ---

    @Test
    fun `empty content no tools null finish with retries remaining is RETRY_COMPLETION`() {
        val d = decide(
            content = null,
            toolCallCount = 0,
            finishReason = null,
            ambiguousRetryCount = 0,
        )
        assertEquals(AgentLoopAction.RETRY_COMPLETION, d.action)
        assertEquals("empty_ambiguous", d.reason)
    }

    @Test
    fun `empty content blank finish still retries when under budget`() {
        val d = decide(
            content = "",
            toolCallCount = 0,
            finishReason = "  ",
            ambiguousRetryCount = 1,
            maxAmbiguousRetries = 2,
        )
        assertEquals(AgentLoopAction.RETRY_COMPLETION, d.action)
        assertEquals("empty_ambiguous", d.reason)
    }

    @Test
    fun `empty content ambiguous retries exhausted is TERMINAL_ERROR not silent success`() {
        val d = decide(
            content = null,
            toolCallCount = 0,
            finishReason = null,
            ambiguousRetryCount = 2,
            maxAmbiguousRetries = 2,
        )
        assertEquals(AgentLoopAction.TERMINAL_ERROR, d.action)
        assertEquals("empty_ambiguous", d.reason)
        assertNotNull(d.userMessage)
        assertTrue(d.userMessage!!.isNotBlank())
        assertTrue(
            d.userMessage!!.contains("empty", ignoreCase = true) ||
                d.userMessage!!.contains("incomplete", ignoreCase = true)
        )
    }

    @Test
    fun `empty content with explicit stop is TERMINAL_SUCCESS empty body`() {
        // Explicit model stop with nothing to say — not ambiguous retry.
        val d = decide(content = null, toolCallCount = 0, finishReason = "stop")
        assertEquals(AgentLoopAction.TERMINAL_SUCCESS, d.action)
        assertEquals("stop", d.reason)
        assertEquals("", d.userMessage)
    }

    // --- length ---

    @Test
    fun `finishReason length with content is TERMINAL_SUCCESS with length note`() {
        val d = decide(content = "Truncated answer", toolCallCount = 0, finishReason = "length")
        assertEquals(AgentLoopAction.TERMINAL_SUCCESS, d.action)
        assertEquals("length", d.reason)
        assertNotNull(d.userMessage)
        assertTrue(d.userMessage!!.contains("Truncated answer"))
        assertTrue(d.userMessage!!.contains("length", ignoreCase = true))
    }

    @Test
    fun `finishReason length empty content is TERMINAL_ERROR not clean silent stop`() {
        val d = decide(content = null, toolCallCount = 0, finishReason = "length")
        assertEquals(AgentLoopAction.TERMINAL_ERROR, d.action)
        assertEquals("length", d.reason)
        assertNotNull(d.userMessage)
        assertTrue(d.userMessage!!.contains("length", ignoreCase = true))
    }

    // --- content filter ---

    @Test
    fun `content_filter is TERMINAL_ERROR`() {
        val d = decide(content = "x", toolCallCount = 0, finishReason = "content_filter")
        assertEquals(AgentLoopAction.TERMINAL_ERROR, d.action)
        assertEquals("content_filter", d.reason)
        assertNotNull(d.userMessage)
    }

    // --- max iterations ---

    @Test
    fun `iteration greater than maxIterations is MAX_ITERATIONS`() {
        val max = 20
        val d = decide(
            content = "still going",
            toolCallCount = 0,
            finishReason = "stop",
            iteration = max + 1,
            maxIterations = max,
        )
        assertEquals(AgentLoopAction.MAX_ITERATIONS, d.action)
        assertEquals("max_iterations", d.reason)
        assertNotNull(d.userMessage)
        assertTrue(d.userMessage!!.contains("$max"))
        assertTrue(
            d.userMessage!!.contains("tool rounds", ignoreCase = true) ||
                d.userMessage!!.contains("stopped", ignoreCase = true)
        )
    }

    @Test
    fun `iteration equal to maxIterations still classifies content normally`() {
        // Helper uses iteration > maxIterations; equal is still in-budget for classification.
        val d = decide(
            content = "Last allowed round answer",
            toolCallCount = 0,
            finishReason = "stop",
            iteration = 20,
            maxIterations = 20,
        )
        assertEquals(AgentLoopAction.TERMINAL_SUCCESS, d.action)
        assertEquals("stop", d.reason)
    }

    // --- unrecognized finish with content ---

    @Test
    fun `non-empty content unknown finish is TERMINAL_SUCCESS not drop`() {
        val d = decide(content = "Got an answer", toolCallCount = 0, finishReason = "other")
        assertEquals(AgentLoopAction.TERMINAL_SUCCESS, d.action)
        assertEquals("other", d.reason)
        assertEquals("Got an answer", d.userMessage)
    }
}
