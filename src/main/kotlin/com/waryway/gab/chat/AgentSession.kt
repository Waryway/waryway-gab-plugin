package com.waryway.gab.chat

import com.intellij.openapi.project.Project
import com.waryway.gab.client.GabClient
import com.waryway.gab.diagnostics.SessionLog
import com.waryway.gab.model.ChatMessage
import com.waryway.gab.model.ModelProvider
import com.waryway.gab.model.Usage
import com.waryway.gab.settings.WarywayGabSettings
import com.waryway.gab.tools.GolandMcpExecutor
import com.waryway.gab.tools.ToolRegistry
import com.waryway.gab.ui.AgentTimeoutUx
import com.waryway.gab.ui.LocalLlmSendUx
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Multi-turn agent loop: send messages + tools, execute tool_calls, continue until done.
 * Exit policy is centralized in [decideAgentLoop] — never silent empty success.
 */
class AgentSession(
    private val project: Project,
    private val client: GabClient,
    private val sessionLog: SessionLog? = null,
    private val onStatus: (String) -> Unit = {},
    /**
     * Full command/tool stdout for the chat UI (collapsed expandable blocks).
     * [command] is the shell command or tool label; [output] is the full executor result.
     */
    private val onCommandOutput: (command: String, output: String) -> Unit = { _, _ -> },
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
        // In-process native tools always work; MCP is preferred when reachable.
        // Local LLM still gates tools on the settings checkbox (small models mishandle tools).
        val includeTools = !localLlm || settings.localLlmUseMcpTools
        val toolsJson = if (includeTools) ToolRegistry.openAiToolsJson(project) else ""
        val mcpProbe = mcpExecutor.probeMcp(forceRefresh = false)
        val backend = if (includeTools) mcpExecutor.backendLabel() else "off"
        val mcpEndpointHint = mcpExecutor.discoveredEndpointHint()

        sessionLog?.system(
            "agent start model=$model provider=${client.provider.displayName} " +
                "messages=${messages.size}${presetOverride?.let { " preset=$it" }.orEmpty()} " +
                "mcp=${mcpProbe.status.name.lowercase()} tools=${if (includeTools) "on" else "off"} " +
                "backend=$backend" +
                mcpEndpointHint?.let { " mcpEndpoint=$it" }.orEmpty()
        )
        when (mcpProbe.status) {
            com.waryway.gab.tools.GolandMcpClient.ProbeStatus.AVAILABLE -> {
                // Preferred path — full JetBrains MCP toolset.
            }
            com.waryway.gab.tools.GolandMcpClient.ProbeStatus.IDE_UP_MCP_OFF -> {
                sessionLog?.system(
                    "MCP HTTP off (IDE up, list_tools 404) — using native in-process tools. " +
                        "Enable Settings → Tools → MCP Server for the full toolset. " +
                        (mcpProbe.detail ?: "")
                )
                if (includeTools) {
                    onStatus("▸ Tools: native (MCP Server not enabled — core tools still run)")
                }
            }
            com.waryway.gab.tools.GolandMcpClient.ProbeStatus.UNREACHABLE -> {
                sessionLog?.system(
                    "MCP unreachable — using native in-process tools. " +
                        (mcpProbe.detail ?: "")
                )
                if (includeTools) {
                    onStatus("▸ Tools: native (MCP unreachable — core tools still run)")
                }
            }
        }
        if (localLlm) {
            prepareLocalLlmMessages(messages)
            sessionLog?.system("local LLM: system prompt omitted, project context in user turn")
        } else {
            // Match prompt to actual tools payload — never claim tools when includeTools is false.
            // Session facts (model / provider / backend) must match the agent-start log line.
            ensureSystemMessage(
                messages,
                localLlm = false,
                toolsAvailable = includeTools,
                provider = client.provider,
                modelId = model,
                toolsBackend = backend,
            )
        }

        var totalUsage = Usage.ZERO
        var toolCallCount = 0
        var iterations = 0
        var lastContent = ""
        var ambiguousRetryCount = 0
        var terminalLogged = false
        val sessionStartedAt = System.currentTimeMillis()
        val sessionTimeoutMs = GabClient.resolveSessionTimeoutMs(client.streamTimeoutSeconds)
        var softBudgetWarned = false

        while (iterations < MAX_ITERATIONS) {
            if (cancelled.get()) {
                return cancelledResult(totalUsage, toolCallCount)
            }

            val now = System.currentTimeMillis()
            val elapsedMs = now - sessionStartedAt
            if (elapsedMs > sessionTimeoutMs) {
                lastContent = sessionBudgetExceededMessage(
                    lastContent = lastContent,
                    elapsedSec = elapsedMs / 1000,
                    budgetSec = sessionTimeoutMs / 1000,
                    iterations = iterations,
                    toolCallCount = toolCallCount,
                )
                messages.add(ChatMessage(ChatMessage.Role.assistant, lastContent))
                sessionLog?.error("agent terminal reason=session_timeout: ${lastContent.take(200)}")
                logTerminal("session_timeout")
                terminalLogged = true
                break
            }
            if (!softBudgetWarned && elapsedMs > (sessionTimeoutMs * SESSION_SOFT_WARN_FRACTION).toLong()) {
                softBudgetWarned = true
                val remainSec = ((sessionTimeoutMs - elapsedMs) / 1000).coerceAtLeast(0)
                onStatus(
                    "▸ Session budget warning — ~${remainSec}s left " +
                        "(${elapsedMs / 1000}s used of ${sessionTimeoutMs / 1000}s multi-turn cap)"
                )
                sessionLog?.system(
                    "agent session soft budget warn elapsed=${elapsedMs / 1000}s " +
                        "budget=${sessionTimeoutMs / 1000}s"
                )
            }

            iterations++
            val statusLine = if (iterations == 1) {
                if (localLlm) {
                    "▸ Thinking… (Local go-cpu may take ~90s+ before first tokens)"
                } else {
                    "▸ Thinking…"
                }
            } else {
                "▸ Continuing agent loop… (round $iterations, ${elapsedMs / 1000}s elapsed)"
            }
            sessionLog?.system(
                "iteration $iterations/$MAX_ITERATIONS elapsed=${elapsedMs / 1000}s " +
                    "sessionBudget=${sessionTimeoutMs / 1000}s"
            )
            onStatus(statusLine)
            // Reset live stream body every completion (including after tools / retries)
            // so deltas do not keep growing one bubble across iterations.
            onStreamStart()

            val response = try {
                // Local go-cpu can sit silent ~90s before first SSE token — heartbeat
                // so the operator sees progress without inventing a heavy spinner GUI.
                coroutineScope {
                    val completionStartedAt = System.currentTimeMillis()
                    val heartbeatJob = if (localLlm) {
                        launch {
                            while (isActive) {
                                delay(LocalLlmSendUx.STILL_GENERATING_INTERVAL_MS)
                                if (cancelled.get()) break
                                val elapsedSec =
                                    (System.currentTimeMillis() - completionStartedAt) / 1000L
                                onStatus(
                                    LocalLlmSendUx.stillGeneratingStatus(
                                        elapsedSeconds = elapsedSec,
                                        streamBudgetSeconds = client.streamTimeoutSeconds
                                    )
                                )
                            }
                        }
                    } else {
                        null
                    }
                    try {
                        client.chatCompletion(
                            model = model,
                            messages = messages,
                            toolsJson = toolsJson,
                            includeTools = includeTools,
                            presetOverride = presetOverride,
                            onStreamDelta = { delta ->
                                if (!cancelled.get()) onStreamDelta(delta)
                            },
                            onStreamReset = {
                                // Retry after empty timeout — clear partial tokens from failed attempt.
                                onStreamStart()
                            },
                            cancelled = { cancelled.get() }
                        )
                    } finally {
                        heartbeatJob?.cancel()
                    }
                }
            } catch (e: GabClient.GabApiException) {
                // AUTH must rethrow — outer Send path formats 401 (never as stream timeout).
                if (e.kind == GabClient.GabApiException.Kind.AUTH) throw e
                // Timeout / transport with optional partial — finish turn gracefully (keep work).
                // Connect-class TRANSPORT without partial rethrows → unreachable UX in outer catch.
                if (e.kind == GabClient.GabApiException.Kind.TIMEOUT ||
                    (AgentTimeoutUx.isTimeoutError(e) &&
                        e.kind != GabClient.GabApiException.Kind.TRANSPORT)
                ) {
                    lastContent = formatStreamTimeoutContent(e, lastContent)
                    messages.add(ChatMessage(ChatMessage.Role.assistant, lastContent))
                    sessionLog?.error("agent terminal reason=stream_timeout: ${e.message}")
                    logTerminal("stream_timeout")
                    terminalLogged = true
                    break
                }
                if (e.kind == GabClient.GabApiException.Kind.TRANSPORT &&
                    !e.partialContent.isNullOrBlank()
                ) {
                    lastContent = formatTransportPartialContent(e, lastContent)
                    messages.add(ChatMessage(ChatMessage.Role.assistant, lastContent))
                    sessionLog?.error("agent terminal reason=transport_partial: ${e.message}")
                    logTerminal("transport_partial")
                    terminalLogged = true
                    break
                }
                throw e
            }
            totalUsage = totalUsage.plus(response.usage)
            if (!response.content.isNullOrBlank()) {
                lastContent = response.content.trim()
            }

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
                        val argSummary = summarizeArgs(toolCall.arguments)
                        val isShellTool =
                            toolCall.name == "execute_terminal_command" ||
                                toolCall.name == "build_project"
                        val commandLabel = when {
                            isShellTool ->
                                extractCommandArg(toolCall.arguments).ifBlank {
                                    if (toolCall.name == "build_project") "build_project" else argSummary
                                }
                            else -> toolCall.name
                        }
                        val toolLine = when {
                            isShellTool -> "▸ cmd: $commandLabel"
                            else -> "▸ ${toolCall.name}($argSummary)"
                        }
                        sessionLog?.tool(
                            "${toolCall.name}($argSummary) via ${mcpExecutor.backendLabel()}"
                        )
                        onStatus(toolLine)
                        val result = try {
                            mcpExecutor.execute(toolCall.name, toolCall.arguments)
                        } catch (e: Exception) {
                            val err = "Error executing tool ${toolCall.name}: ${e.message ?: e::class.java.simpleName}"
                            sessionLog?.error(err)
                            err
                        }
                        // Full output to session log (capped) so Export fail packages stay useful.
                        val logBody = if (result.length > 4_000) {
                            result.take(4_000) + "\n… (${result.length - 4_000} more chars)"
                        } else {
                            result
                        }
                        sessionLog?.tool("result:\n$logBody")
                        if (isShellTool) {
                            // Chat: collapsible full output (collapsed by default).
                            onCommandOutput(commandLabel, result)
                        } else {
                            // Non-shell tools: keep a short one-line summary in the timeline.
                            onStatus("  → ${summarizeResult(result)}")
                        }
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

    private fun formatStreamTimeoutContent(
        e: GabClient.GabApiException,
        previousContent: String
    ): String {
        val partial = e.partialContent?.trim().orEmpty()
            .ifEmpty { previousContent.trim() }
        return AgentTimeoutUx.formatTimeoutFailureWithPartial(
            provider = client.provider,
            agentMode = false,
            timeoutSeconds = AgentTimeoutUx.extractTimeoutSeconds(e.message)
                ?: client.streamTimeoutSeconds,
            detail = e.message?.take(120),
            partialContent = partial.takeIf { it.isNotEmpty() }
        )
    }

    private fun formatTransportPartialContent(
        e: GabClient.GabApiException,
        previousContent: String
    ): String {
        val partial = e.partialContent?.trim().orEmpty()
            .ifEmpty { previousContent.trim() }
        val err = "Transport interrupted: ${e.message?.trim().orEmpty().ifBlank { "connection lost" }}. " +
            "Partial reply kept — retry Send to continue."
        return AgentTimeoutUx.mergePartialWithTimeout(partial, err)
            .replace("— Timed out (partial reply kept) —", "— Interrupted (partial reply kept) —")
    }

    private fun sessionBudgetExceededMessage(
        lastContent: String,
        elapsedSec: Long,
        budgetSec: Long,
        iterations: Int,
        toolCallCount: Int
    ): String {
        val note =
            "Agent session timed out after ${AgentTimeoutUx.formatDuration(elapsedSec)} " +
                "(multi-turn budget ${AgentTimeoutUx.formatDuration(budgetSec)}; " +
                "$iterations rounds, $toolCallCount tool calls). " +
                "Partial work above is kept — send “continue” or raise Chat stream timeout " +
                "(session budget scales with stream timeout)."
        val prior = lastContent.trim()
        return if (prior.isNotEmpty()) {
            AgentTimeoutUx.mergePartialWithTimeout(prior, note)
        } else {
            note
        }
    }

    private fun ensureSystemMessage(
        messages: MutableList<ChatMessage>,
        localLlm: Boolean = false,
        toolsAvailable: Boolean = true,
        provider: ModelProvider? = null,
        modelId: String? = null,
        toolsBackend: String? = null,
    ) {
        val prompt = AgentSystemPrompt.build(
            project,
            localLlm,
            toolsAvailable = toolsAvailable,
            provider = provider,
            modelId = modelId,
            toolsBackend = toolsBackend,
        )
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

    private fun extractCommandArg(argumentsJson: String): String {
        val m = Regex(""""command"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(argumentsJson)
            ?: return ""
        return m.groupValues[1]
            .replace("\\n", " ")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .let { if (it.length > 100) it.take(97) + "…" else it }
    }

    private fun summarizeResult(result: String): String {
        val first = result.lineSequence().firstOrNull()?.trim().orEmpty()
        return if (first.length > 140) first.take(137) + "…" else first.ifEmpty { "(empty result)" }
    }

    companion object {
        const val MAX_ITERATIONS = 20
        const val MAX_AMBIGUOUS_RETRIES = 2
        /** Soft status when this fraction of the multi-turn session budget is used. */
        const val SESSION_SOFT_WARN_FRACTION = 0.8
    }
}
