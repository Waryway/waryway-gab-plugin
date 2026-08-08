package com.waryway.gab.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Regression lock for agent-loop control taxonomy (section-04 / wo-04-01-01).
 *
 * Targets pure [decideAgentLoop] + [AgentSession] constants. Complements
 * wo-03-02 [AgentLoopDecisionTest] (if present) — does not require Project/HTTP.
 */
class AgentLoopControlTest {

    // --- continue on tools ---

    @Test
    fun `tool_calls finish with non-empty tools continues loop`() {
        val d = decide(
            content = null,
            toolCallCount = 2,
            finishReason = "tool_calls",
        )
        assertEquals(AgentLoopAction.CONTINUE, d.action)
        assertEquals("tool_calls", d.reason)
    }

    @Test
    fun `non-empty tools continue even when finish_reason is stop`() {
        val d = decide(
            content = "calling tools",
            toolCallCount = 1,
            finishReason = "stop",
        )
        assertEquals(AgentLoopAction.CONTINUE, d.action)
        assertEquals("tool_calls", d.reason)
    }

    // --- terminal success ---

    @Test
    fun `stop with non-empty content and no tools is terminal success`() {
        val d = decide(
            content = "  Done.  ",
            toolCallCount = 0,
            finishReason = "stop",
        )
        assertEquals(AgentLoopAction.TERMINAL_SUCCESS, d.action)
        assertEquals("stop", d.reason)
        assertEquals("Done.", d.userMessage)
    }

    @Test
    fun `end_turn with content is terminal success`() {
        val d = decide(
            content = "Hello",
            toolCallCount = 0,
            finishReason = "end_turn",
        )
        assertEquals(AgentLoopAction.TERMINAL_SUCCESS, d.action)
        assertEquals("end_turn", d.reason)
        assertEquals("Hello", d.userMessage)
    }

    // --- empty / ambiguous policy (not silent success) ---

    @Test
    fun `empty content null finish retries when budget remains`() {
        val d = decide(
            content = null,
            toolCallCount = 0,
            finishReason = null,
            ambiguousRetryCount = 0,
        )
        assertEquals(AgentLoopAction.RETRY_COMPLETION, d.action)
        assertEquals("empty_ambiguous", d.reason)
        assertNotEquals(AgentLoopAction.TERMINAL_SUCCESS, d.action)
    }

    @Test
    fun `empty content blank finish is not silent success`() {
        val d = decide(
            content = "   ",
            toolCallCount = 0,
            finishReason = "",
            ambiguousRetryCount = 0,
        )
        assertEquals(AgentLoopAction.RETRY_COMPLETION, d.action)
        assertEquals("empty_ambiguous", d.reason)
    }

    @Test
    fun `empty ambiguous exhausted retries yields terminal error`() {
        val d = decide(
            content = "",
            toolCallCount = 0,
            finishReason = null,
            ambiguousRetryCount = AgentSession.MAX_AMBIGUOUS_RETRIES,
        )
        assertEquals(AgentLoopAction.TERMINAL_ERROR, d.action)
        assertEquals("empty_ambiguous", d.reason)
        assertTrue(d.userMessage.orEmpty().contains("empty or incomplete", ignoreCase = true))
    }

    @Test
    fun `finish tool_calls with empty tools is incomplete not success`() {
        val retry = decide(
            content = null,
            toolCallCount = 0,
            finishReason = "tool_calls",
            ambiguousRetryCount = 0,
        )
        assertEquals(AgentLoopAction.RETRY_COMPLETION, retry.action)
        assertEquals("incomplete_tool_calls", retry.reason)

        val error = decide(
            content = null,
            toolCallCount = 0,
            finishReason = "function_call",
            ambiguousRetryCount = AgentSession.MAX_AMBIGUOUS_RETRIES,
        )
        assertEquals(AgentLoopAction.TERMINAL_ERROR, error.action)
        assertEquals("incomplete_tool_calls", error.reason)
        assertTrue(error.userMessage.orEmpty().contains("no tool calls", ignoreCase = true))
    }

    // --- cancel ---

    @Test
    fun `cancelled flag yields TerminalCancelled with Stopped by user`() {
        val d = decideAgentLoop(
            cancelled = true,
            content = "partial",
            toolCallCount = 3,
            finishReason = "stop",
            iteration = 1,
            maxIterations = AgentSession.MAX_ITERATIONS,
            ambiguousRetryCount = 0,
        )
        assertEquals(AgentLoopAction.CANCELLED, d.action)
        assertEquals("cancel", d.reason)
        assertEquals("Stopped by user.", d.userMessage)
    }

    @Test
    fun `cancel takes precedence over tools and content`() {
        val d = decideAgentLoop(
            cancelled = true,
            content = null,
            toolCallCount = 0,
            finishReason = null,
            iteration = 99,
            maxIterations = 1,
            ambiguousRetryCount = 99,
        )
        assertEquals(AgentLoopAction.CANCELLED, d.action)
        assertEquals("Stopped by user.", d.userMessage)
    }

    // --- max iterations ---

    @Test
    fun `iteration past max yields MAX_ITERATIONS with stop-after-N wording`() {
        val max = AgentSession.MAX_ITERATIONS
        val d = decideAgentLoop(
            cancelled = false,
            content = "still going",
            toolCallCount = 1,
            finishReason = "tool_calls",
            iteration = max + 1,
            maxIterations = max,
            ambiguousRetryCount = 0,
        )
        assertEquals(AgentLoopAction.MAX_ITERATIONS, d.action)
        assertEquals("max_iterations", d.reason)
        val msg = d.userMessage.orEmpty()
        assertTrue(msg.contains("stopped after $max"), "expected stop-after-N in: $msg")
        assertTrue(msg.contains("tool rounds"), "expected tool-rounds wording in: $msg")
    }

    @Test
    fun `AgentSession MAX_ITERATIONS constant is positive and matches helper messaging`() {
        assertTrue(AgentSession.MAX_ITERATIONS > 0)
        assertEquals(20, AgentSession.MAX_ITERATIONS)
        assertEquals(2, AgentSession.MAX_AMBIGUOUS_RETRIES)

        val d = decideAgentLoop(
            cancelled = false,
            content = null,
            toolCallCount = 0,
            finishReason = null,
            iteration = AgentSession.MAX_ITERATIONS + 1,
            maxIterations = AgentSession.MAX_ITERATIONS,
            ambiguousRetryCount = 0,
        )
        // Same phrasing AgentSession appends on hard loop exit
        assertEquals(
            "(agent stopped after ${AgentSession.MAX_ITERATIONS} tool rounds — ask to continue if needed)",
            d.userMessage,
        )
    }

    // --- extra regression locks from implemented policy ---

    @Test
    fun `content_filter finish is terminal error not success`() {
        val d = decide(
            content = "blocked text",
            toolCallCount = 0,
            finishReason = "content_filter",
        )
        assertEquals(AgentLoopAction.TERMINAL_ERROR, d.action)
        assertEquals("content_filter", d.reason)
        assertTrue(d.userMessage.orEmpty().contains("content filter", ignoreCase = true))
    }

    @Test
    fun `length finish with content is success with length note`() {
        val d = decide(
            content = "Partial answer",
            toolCallCount = 0,
            finishReason = "length",
        )
        assertEquals(AgentLoopAction.TERMINAL_SUCCESS, d.action)
        assertEquals("length", d.reason)
        assertTrue(d.userMessage.orEmpty().startsWith("Partial answer"))
        assertTrue(d.userMessage.orEmpty().contains("maximum length", ignoreCase = true))
    }

    @Test
    fun `empty content with explicit stop is terminal success empty not ambiguous retry`() {
        val d = decide(
            content = "",
            toolCallCount = 0,
            finishReason = "stop",
        )
        assertEquals(AgentLoopAction.TERMINAL_SUCCESS, d.action)
        assertEquals("stop", d.reason)
        assertEquals("", d.userMessage)
    }

    private fun decide(
        content: String?,
        toolCallCount: Int,
        finishReason: String?,
        iteration: Int = 1,
        maxIterations: Int = AgentSession.MAX_ITERATIONS,
        ambiguousRetryCount: Int = 0,
        maxAmbiguousRetries: Int = AgentSession.MAX_AMBIGUOUS_RETRIES,
    ): AgentLoopDecision = decideAgentLoop(
        cancelled = false,
        content = content,
        toolCallCount = toolCallCount,
        finishReason = finishReason,
        iteration = iteration,
        maxIterations = maxIterations,
        ambiguousRetryCount = ambiguousRetryCount,
        maxAmbiguousRetries = maxAmbiguousRetries,
    )
}
