package com.waryway.gab.chat

import com.intellij.openapi.project.Project
import com.waryway.gab.client.GabClient
import com.waryway.gab.diagnostics.SessionLog
import com.waryway.gab.model.ChatMessage
import com.waryway.gab.model.Usage
import com.waryway.gab.tools.IdeToolExecutor
import com.waryway.gab.tools.ToolRegistry
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Multi-turn agent loop: send messages + tools, execute tool_calls, continue until done.
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
        sessionLog?.system(
            "agent start model=$model provider=${client.provider.displayName} " +
                "messages=${messages.size}${presetOverride?.let { " preset=$it" }.orEmpty()}"
        )
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

        while (iterations < MAX_ITERATIONS) {
            if (cancelled.get()) {
                sessionLog?.system("agent cancelled by user")
                return Result("Stopped by user.", totalUsage, toolCallCount)
            }

            iterations++
            val statusLine = if (iterations == 1) "▸ Thinking…" else "▸ Continuing agent loop…"
            sessionLog?.system("iteration $iterations/$MAX_ITERATIONS")
            onStatus(statusLine)
            if (iterations == 1) onStreamStart()

            val response = client.chatCompletion(
                model = model,
                messages = messages,
                toolsJson = ToolRegistry.openAiToolsJson(),
                presetOverride = presetOverride,
                onStreamDelta = { delta ->
                    if (!cancelled.get()) onStreamDelta(delta)
                },
                cancelled = { cancelled.get() }
            )
            totalUsage = totalUsage.plus(response.usage)

            if (response.toolCalls.isNotEmpty()) {
                val assistantMsg = ChatMessage(
                    role = ChatMessage.Role.assistant,
                    content = response.content.orEmpty(),
                    toolCalls = response.toolCalls
                )
                messages.add(assistantMsg)

                val executor = IdeToolExecutor(project)
                for (toolCall in response.toolCalls) {
                    if (cancelled.get()) break

                    toolCallCount++
                    val toolLine = "▸ ${toolCall.name}(${summarizeArgs(toolCall.arguments)})"
                    sessionLog?.tool(toolLine.removePrefix("▸ ").trim())
                    onStatus(toolLine)
                    val result = executor.execute(toolCall.name, toolCall.arguments)
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

                if (response.finishReason == "tool_calls" || response.toolCalls.isNotEmpty()) {
                    continue
                }
            }

            lastContent = response.content?.trim().orEmpty()
            if (lastContent.isNotEmpty()) {
                messages.add(ChatMessage(ChatMessage.Role.assistant, lastContent))
            }
            break
        }

        if (iterations >= MAX_ITERATIONS) {
            sessionLog?.error("agent stopped after $MAX_ITERATIONS iterations")
            lastContent = (lastContent.ifEmpty { "" }) +
                "\n\n(agent stopped after $MAX_ITERATIONS tool rounds — ask to continue if needed)"
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
    }
}