package com.waryway.gab.chat

import com.intellij.openapi.project.Project
import com.waryway.gab.client.GabClient
import com.waryway.gab.diagnostics.SessionLog
import com.waryway.gab.model.ChatMessage
import com.waryway.gab.model.Usage
import com.waryway.gab.settings.WarywayGabSettings
import com.waryway.gab.tools.GolandMcpExecutor
import com.waryway.gab.tools.ToolRegistry
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Multi-turn agent loop: send messages + tools, execute tool_calls, continue until done.
 * Exit policy is centralized in [decideAgentLoop] — never silent empty success.
 */
class AgentSession(
    private val project: Project,
    private val client: GabClient,
    private val sessionLog: SessionLog? = null,
    private val onStatus: (String) -> Unit = {},
    private val onStreamStart: () -> Unit = {},
    private val onStreamDelta: (String) -> Unit = {},
    private val cancelled: AtomicBoolean = AtomicBoolean(false),
    private val presetOverride: String? = null
) {

    data class Result(
        val finalContent: String,
        val totalUsage: Usage,
        val toolCallCount: Int
    )

    suspend fun run(model: String, messages: MutableList<ChatMessage>): Result {
        val localLlm = client.provider == com.waryway.gab.model.ModelProvider.LOCAL_LLM
        val settings = WarywayGabSettings.getInstance()
        val mcpExecutor = GolandMcpExecutor(project, settings)
        val mcpAvailable = mcpExecutor.isAvailable()
        val includeTools = mcpAvailable && (!localLlm || settings.localLlmUseMcpTools)
        val toolsJson = if (includeTools) ToolRegistry.openAiToolsJson(project) else ""

        sessionLog?.system(
            "agent start model=$model provider=${client.provider.displayName} " +
                "messages=${messages.size}${presetOverride?.let { " preset=$it" }.orEmpty()} " +
                "mcp=${if (mcpAvailable) "on" else "off"} tools=${if (includeTools) "on" else "off"}"
        )
        if (!mcpAvailable) {
            sessionLog?.error(
                "GoLand MCP Server not reachable — enable Settings → Tools → MCP Server. " +
                    "Agent will run without IDE tools."
            )
        }
        if (localLlm) {
            prepareLocalLlmMessages(messages)
            sessionLog?.system("local LLM: system prompt omitted, project context in user turn")
        } else {
            ensureSystemMessage(messages, localLlm = false)
        }

        var totalUsage = Usage.ZERO
        var toolCallCount = 0
        var iterations = 0
        var lastContent = ""
        var ambiguousRetryCount = 0
        var terminalLogged = false

        while (iterations < MAX_ITERATIONS) {
            if (cancelled.get()) {
                return cancelledResult(totalUsage, toolCallCount)
            }

            iterations++
            val statusLine = if (iterations == 1) "▸ Thinking…" else "▸ Continuing agent loop…"
            sessionLog?.system("iteration $iterations/$MAX_ITERATIONS")
            onStatus(statusLine)
            // Reset live stream body every completion (including after tools / retries)
            // so deltas do not keep growing one bubble across iterations.
            onStreamStart()

            val response = client.chatCompletion(
                model = model,
                messages = messages,
                toolsJson = toolsJson,
                includeTools = includeTools,
                presetOverride = presetOverride,
                onStreamDelta = { delta ->
                    if (!cancelled.get()) onStreamDelta(delta)
                },
                cancelled = { cancelled.get() }
            )
            totalUsage = totalUsage.plus(response.usage)

            // Cancel race: AtomicBoolean and/or ChatCompletionResult.cancelled (section-02).
            // Never treat a cancelled partial stream as clean success.
            if (cancelled.get() || response.cancelled) {
                return cancelledResult(totalUsage, toolCallCount)
            }

            // SSE streamError on the result (fixtures / non-throwing paths) is a hard failure.
            val streamErr = response.streamError?.trim().orEmpty()
            if (streamErr.isNotEmpty()) {
                lastContent = "Stream error: $streamErr"
                messages.add(ChatMessage(ChatMessage.Role.assistant, lastContent))
                sessionLog?.error("agent terminal reason=stream_error: $lastContent")
                logTerminal("stream_error")
                terminalLogged = true
                break
            }

            // Incomplete tool builders (excluded from toolCalls) must not look like a clean stop.
            val finishForDecision =
                if (response.toolCalls.isEmpty() && response.incompleteToolCallCount > 0) {
                    val fr = response.finishReason?.trim()?.lowercase().orEmpty()
                    if (fr == "tool_calls" || fr == "function_call") response.finishReason
                    else "tool_calls"
                } else {
                    response.finishReason
                }

            val decision = decideAgentLoop(
                cancelled = false,
                content = response.content,
                toolCallCount = response.toolCalls.size,
                finishReason = finishForDecision,
                iteration = iterations,
                maxIterations = MAX_ITERATIONS,
                ambiguousRetryCount = ambiguousRetryCount,
            )

            when (decision.action) {
                AgentLoopAction.CONTINUE -> {
                    val assistantMsg = ChatMessage(
                        role = ChatMessage.Role.assistant,
                        content = response.content.orEmpty(),
                        toolCalls = response.toolCalls
                    )
                    messages.add(assistantMsg)

                    for (toolCall in response.toolCalls) {
                        if (cancelled.get()) {
                            return cancelledResult(totalUsage, toolCallCount)
                        }

                        toolCallCount++
                        val toolLine = "▸ ${toolCall.name}(${summarizeArgs(toolCall.arguments)})"
                        sessionLog?.tool(toolLine.removePrefix("▸ ").trim())
                        onStatus(toolLine)
                        val result = try {
                            mcpExecutor.execute(toolCall.name, toolCall.arguments)
                        } catch (e: Exception) {
                            val err = "Error executing tool ${toolCall.name}: ${e.message ?: e::class.java.simpleName}"
                            sessionLog?.error(err)
                            err
                        }
                        val resultLine = "  → ${summarizeResult(result)}"
                        sessionLog?.tool(resultLine.trim())
                        onStatus(resultLine)
                        messages.add(
                            ChatMessage(
                                role = ChatMessage.Role.tool,
                                content = result,
                                toolCallId = toolCall.id
                            )
                        )
                    }

                    if (cancelled.get()) {
                        return cancelledResult(totalUsage, toolCallCount)
                    }
                    // Next completion with tool results in conversation.
                    continue
                }

                AgentLoopAction.RETRY_COMPLETION -> {
                    ambiguousRetryCount++
                    sessionLog?.system(
                        "agent retry reason=${decision.reason} " +
                            "attempt=$ambiguousRetryCount/$MAX_AMBIGUOUS_RETRIES"
                    )
                    continue
                }

                AgentLoopAction.TERMINAL_SUCCESS -> {
                    lastContent = decision.userMessage?.trim().orEmpty()
                        .ifEmpty { response.content?.trim().orEmpty() }
                    if (lastContent.isNotEmpty()) {
                        messages.add(ChatMessage(ChatMessage.Role.assistant, lastContent))
                    }
                    logTerminal(decision.reason)
                    terminalLogged = true
                    break
                }

                AgentLoopAction.TERMINAL_ERROR -> {
                    lastContent = decision.userMessage?.trim().orEmpty()
                        .ifEmpty { "The model returned an empty or incomplete response. Please try again." }
                    messages.add(ChatMessage(ChatMessage.Role.assistant, lastContent))
                    sessionLog?.error("agent terminal reason=${decision.reason}: $lastContent")
                    logTerminal(decision.reason)
                    terminalLogged = true
                    break
                }

                AgentLoopAction.CANCELLED -> {
                    return cancelledResult(totalUsage, toolCallCount)
                }

                AgentLoopAction.MAX_ITERATIONS -> {
                    lastContent = decision.userMessage.orEmpty()
                    logTerminal("max_iterations")
                    terminalLogged = true
                    break
                }
            }
        }

        if (iterations >= MAX_ITERATIONS) {
            sessionLog?.error("agent stopped after $MAX_ITERATIONS iterations")
            if (!terminalLogged) {
                logTerminal("max_iterations")
                terminalLogged = true
            }
            lastContent = (lastContent.ifEmpty { "" }) +
                "\n\n(agent stopped after $MAX_ITERATIONS tool rounds — ask to continue if needed)"
        }

        if (!terminalLogged) {
            // Defensive: loop exited without an explicit terminal classification.
            logTerminal(if (lastContent.isEmpty()) "empty_ambiguous" else "stop")
        }

        sessionLog?.system(
            "agent done: ${lastContent.length} chars, $toolCallCount tool calls, " +
                "tokens=${totalUsage.totalTokens}"
        )
        return Result(
            finalContent = lastContent.ifEmpty { "(no response)" },
            totalUsage = totalUsage,
            toolCallCount = toolCallCount
        )
    }

    private fun cancelledResult(totalUsage: Usage, toolCallCount: Int): Result {
        logTerminal("cancel")
        sessionLog?.system("agent cancelled by user")
        sessionLog?.system(
            "agent done: ${"Stopped by user.".length} chars, $toolCallCount tool calls, " +
                "tokens=${totalUsage.totalTokens}"
        )
        return Result("Stopped by user.", totalUsage, toolCallCount)
    }

    private fun logTerminal(reason: String) {
        sessionLog?.system("agent terminal reason=$reason")
    }

    private fun ensureSystemMessage(messages: MutableList<ChatMessage>, localLlm: Boolean = false) {
        val prompt = AgentSystemPrompt.build(project, localLlm)
        val existing = messages.indexOfFirst { it.role == ChatMessage.Role.system }
        if (existing >= 0) {
            messages[existing] = ChatMessage(ChatMessage.Role.system, prompt)
        } else {
            messages.add(0, ChatMessage(ChatMessage.Role.system, prompt))
        }
    }

    /** Small local models echo system prompts verbatim — keep context in the user turn only. */
    private fun prepareLocalLlmMessages(messages: MutableList<ChatMessage>) {
        messages.removeAll { it.role == ChatMessage.Role.system }
        val root = project.basePath ?: ""
        val lastUser = messages.indexOfLast { it.role == ChatMessage.Role.user }
        if (lastUser < 0) return
        val user = messages[lastUser]
        if (root.isNotBlank() && !user.content.contains(root)) {
            messages[lastUser] = ChatMessage(
                role = ChatMessage.Role.user,
                content = "[Project: $root]\n${user.content}"
            )
        }
    }

    private fun summarizeArgs(argumentsJson: String): String {
        if (argumentsJson.isBlank() || argumentsJson == "{}") return ""
        val pairs = Regex(""""(\w+)"\s*:\s*"((?:[^"\\]|\\.)*)"""")
            .findAll(argumentsJson)
            .map { match ->
                val key = match.groupValues[1]
                val value = match.groupValues[2]
                    .replace("\\n", " ")
                    .replace("\\\"", "\"")
                val short = if (value.length > 48) value.take(45) + "…" else value
                "$key=$short"
            }
            .take(3)
            .joinToString(", ")
        return pairs.ifEmpty { argumentsJson.take(60) }
    }

    private fun summarizeResult(result: String): String {
        val first = result.lineSequence().firstOrNull()?.trim().orEmpty()
        return if (first.length > 140) first.take(137) + "…" else first.ifEmpty { "(empty result)" }
    }

    companion object {
        const val MAX_ITERATIONS = 20
        const val MAX_AMBIGUOUS_RETRIES = 2
    }
}
