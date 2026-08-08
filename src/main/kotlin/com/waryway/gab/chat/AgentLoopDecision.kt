package com.waryway.gab.chat

/**
 * Pure agent-loop exit / continue taxonomy.
 * Unit-testable without Project / IDE (section-04 / wo-03-02).
 */
enum class AgentLoopAction {
    /** Execute tool_calls (if any) and request another completion. */
    CONTINUE,
    /** Same conversation; re-request completion (empty / incomplete / tool-use without tools). */
    RETRY_COMPLETION,
    /** Model finished cleanly (or with length note); stop loop. */
    TERMINAL_SUCCESS,
    /** Hard / policy failure with user-visible message; stop loop. */
    TERMINAL_ERROR,
    /** User cancel. */
    CANCELLED,
    /** Hit iteration cap. */
    MAX_ITERATIONS,
}

data class AgentLoopDecision(
    val action: AgentLoopAction,
    /** Machine-ish reason: cancel | stop | empty_ambiguous | length | tool_calls | max_iterations | error | … */
    val reason: String,
    /** Optional final transcript content when terminal. */
    val userMessage: String? = null,
)

private val TOOL_USE_FINISH = setOf("tool_calls", "function_call")
private val SUCCESS_FINISH = setOf("stop", "end_turn", "stop_sequence")
private val FILTER_FINISH = setOf("content_filter", "contentfilter")

/**
 * Classify one completion step into continue / retry / terminal.
 *
 * @param toolCallCount number of tool calls on the completion (avoids ToolCall dependency)
 * @param ambiguousRetryCount retries already used for empty / incomplete / phantom tool-use
 */
fun decideAgentLoop(
    cancelled: Boolean,
    content: String?,
    toolCallCount: Int,
    finishReason: String?,
    iteration: Int,
    maxIterations: Int,
    ambiguousRetryCount: Int,
    maxAmbiguousRetries: Int = 2,
): AgentLoopDecision {
    if (cancelled) {
        return AgentLoopDecision(
            action = AgentLoopAction.CANCELLED,
            reason = "cancel",
            userMessage = "Stopped by user.",
        )
    }

    if (iteration > maxIterations) {
        return AgentLoopDecision(
            action = AgentLoopAction.MAX_ITERATIONS,
            reason = "max_iterations",
            userMessage = "(agent stopped after $maxIterations tool rounds — ask to continue if needed)",
        )
    }

    val trimmed = content?.trim().orEmpty()
    val finish = finishReason?.trim()?.lowercase().orEmpty()

    // Pending tools always continue the multi-step loop.
    if (toolCallCount > 0) {
        return AgentLoopDecision(
            action = AgentLoopAction.CONTINUE,
            reason = "tool_calls",
        )
    }

    // finish_reason says tool use but list empty — incomplete, not a short answer.
    if (finish in TOOL_USE_FINISH) {
        return retryOrError(
            ambiguousRetryCount = ambiguousRetryCount,
            maxAmbiguousRetries = maxAmbiguousRetries,
            retryReason = "incomplete_tool_calls",
            errorMessage = "The model indicated tool use but returned no tool calls.",
        )
    }

    if (finish in FILTER_FINISH || finish.contains("content_filter")) {
        return AgentLoopDecision(
            action = AgentLoopAction.TERMINAL_ERROR,
            reason = "content_filter",
            userMessage = "Response blocked by content filter.",
        )
    }

    if (finish == "length") {
        val note = "(Output stopped: model hit the maximum length limit.)"
        return if (trimmed.isNotEmpty()) {
            AgentLoopDecision(
                action = AgentLoopAction.TERMINAL_SUCCESS,
                reason = "length",
                userMessage = "$trimmed\n\n$note",
            )
        } else {
            AgentLoopDecision(
                action = AgentLoopAction.TERMINAL_ERROR,
                reason = "length",
                userMessage = note,
            )
        }
    }

    val terminalSuccessFinish =
        finish.isEmpty() || finish in SUCCESS_FINISH

    if (trimmed.isNotEmpty() && terminalSuccessFinish) {
        return AgentLoopDecision(
            action = AgentLoopAction.TERMINAL_SUCCESS,
            reason = if (finish.isEmpty()) "stop" else finish,
            userMessage = trimmed,
        )
    }

    // Non-empty content with an unrecognized finish reason — treat as success, not silent drop.
    if (trimmed.isNotEmpty()) {
        return AgentLoopDecision(
            action = AgentLoopAction.TERMINAL_SUCCESS,
            reason = finish.ifEmpty { "stop" },
            userMessage = trimmed,
        )
    }

    // Empty content, no tools.
    // Explicit terminal stop with nothing to say → success (caller may show "(no response)").
    if (finish in SUCCESS_FINISH) {
        return AgentLoopDecision(
            action = AgentLoopAction.TERMINAL_SUCCESS,
            reason = finish,
            userMessage = "",
        )
    }

    // Empty + null/blank/unknown finish → bounded retry then explicit error.
    return retryOrError(
        ambiguousRetryCount = ambiguousRetryCount,
        maxAmbiguousRetries = maxAmbiguousRetries,
        retryReason = "empty_ambiguous",
        errorMessage = "The model returned an empty or incomplete response. Please try again.",
    )
}

private fun retryOrError(
    ambiguousRetryCount: Int,
    maxAmbiguousRetries: Int,
    retryReason: String,
    errorMessage: String,
): AgentLoopDecision {
    return if (ambiguousRetryCount < maxAmbiguousRetries) {
        AgentLoopDecision(
            action = AgentLoopAction.RETRY_COMPLETION,
            reason = retryReason,
        )
    } else {
        AgentLoopDecision(
            action = AgentLoopAction.TERMINAL_ERROR,
            reason = retryReason,
            userMessage = errorMessage,
        )
    }
}
