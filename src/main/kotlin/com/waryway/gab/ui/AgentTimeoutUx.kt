package com.waryway.gab.ui

import com.waryway.gab.chat.LocalLlmAgentSession
import com.waryway.gab.model.ModelProvider
import java.net.http.HttpTimeoutException

/**
 * Shared timeout classification and operator-facing recovery copy for all agents
 * (Local LLM agent poll, Local chat, Grok Build, Grok API, Gab AI).
 *
 * Detection is intentionally broader than [LocalLlmSendUx.isUnreachableError]:
 * connect timeouts stay "unreachable"; request / stream / agent-poll timeouts map here.
 */
object AgentTimeoutUx {

    /** Default cloud stream budget when settings are unavailable (minutes). */
    const val DEFAULT_CHAT_STREAM_TIMEOUT_MINUTES = 15

    /** Default Local LLM chat stream budget (minutes) — pure-Go generation is slow. */
    const val DEFAULT_LOCAL_CHAT_STREAM_TIMEOUT_MINUTES = 30

    /**
     * True when [message] looks like a request/stream/agent-run timeout — not a cold server.
     * Connect-level timeouts remain [LocalLlmSendUx.isUnreachableError] (false here).
     */
    fun isTimeoutMessage(message: String?): Boolean {
        val m = message?.trim()?.lowercase().orEmpty()
        if (m.isBlank()) return false
        // Connect-class → unreachable path, not stream/run timeout UX.
        if (m.contains("connect timed out") || m.contains("connection timed out")) return false
        if (m.contains("http connect timed out")) return false
        if (m.contains("connection refused") || m.contains("connection reset")) return false
        return m.contains("agent run timed out") ||
            m.contains("agenttimeoutexception") ||
            m.contains("request timed out") ||
            m.contains("httptimeoutexception") ||
            m.contains("stream timed out") ||
            m.contains("chat timed out") ||
            m.contains("read timed out") ||
            m.contains("sockettimeoutexception") ||
            (m.contains("timed out") && !m.contains("connect")) ||
            (m.contains("timeout") && (
                m.contains("after ") ||
                    m.contains("stream") ||
                    m.contains("request") ||
                    m.contains("agent") ||
                    m.contains("poll") ||
                    m.contains("http")
                ))
    }

    /** Throwable classification including [HttpTimeoutException] and agent poll timeout. */
    fun isTimeoutError(error: Throwable?): Boolean {
        if (error == null) return false
        if (error is LocalLlmAgentSession.AgentTimeoutException) return true
        if (error is HttpTimeoutException) return true
        // Walk short cause chain (wrapped IO).
        var cur: Throwable? = error
        var depth = 0
        while (cur != null && depth < 4) {
            if (cur is HttpTimeoutException) return true
            if (cur is LocalLlmAgentSession.AgentTimeoutException) return true
            if (isTimeoutMessage(cur.message) || isTimeoutMessage(cur.javaClass.simpleName)) return true
            cur = cur.cause
            depth++
        }
        return false
    }

    /**
     * Operator-facing timeout recovery for [provider].
     *
     * @param agentMode Local LLM only: true → /api/agent poll timeout guidance
     * @param timeoutSeconds Optional budget that was exhausted (shown when known)
     * @param detail Short underlying exception text (optional)
     */
    fun formatTimeoutFailure(
        provider: ModelProvider,
        agentMode: Boolean = false,
        timeoutSeconds: Long? = null,
        detail: String? = null
    ): String {
        val budget = timeoutSeconds?.takeIf { it > 0 }?.let { " after ${formatDuration(it)}" }.orEmpty()
        val d = detail?.trim()?.takeIf { it.isNotEmpty() && it.length <= 160 }
        val detailSuffix = d?.let { " ($it)" }.orEmpty()

        return when (provider) {
            ModelProvider.LOCAL_LLM -> if (agentMode) {
                "Local LLM agent timed out$budget — the server run was cancelled. " +
                    "Raise Settings → Local LLM → Agent poll timeout, use Chat for Q&A, " +
                    "or switch inference to cpp/vulkan if pure go-cpu planning is too slow. " +
                    "Partial answer above is kept when present — send “continue” or retry." +
                    detailSuffix
            } else {
                "Local LLM chat timed out$budget — generation exceeded the stream budget. " +
                    "Raise Settings → Chat stream timeout (default 30 min local), simplify the prompt, " +
                    "or use a faster backend (cpp/vulkan). " +
                    "Partial reply above is kept when present — send “continue” or retry Send. " +
                    "go-cpu first tokens often take ~90s; a true hang is multi-minute silence past budget." +
                    detailSuffix
            }
            ModelProvider.GROK_BUILD ->
                "Grok Build stream timed out$budget — the proxy did not finish within the " +
                    "client budget (common with long reasoning / tool rounds). " +
                    "Retry Send, raise Settings → Chat stream timeout, or break the task into smaller steps." +
                    detailSuffix
            ModelProvider.GROK ->
                "Grok API stream timed out$budget — the response did not finish in time. " +
                    "Retry Send, raise Settings → Chat stream timeout, or shorten the request." +
                    detailSuffix
            ModelProvider.GAB_AI ->
                "Gab AI stream timed out$budget — the response did not finish in time. " +
                    "Retry Send, raise Settings → Chat stream timeout, or shorten the request." +
                    detailSuffix
        }
    }

    /** Extract "after Ns" from known timeout messages when present. */
    fun extractTimeoutSeconds(message: String?): Long? {
        if (message.isNullOrBlank()) return null
        Regex("""(?i)timed\s+out\s+after\s+(\d+)\s*s""").find(message)?.groupValues?.get(1)
            ?.toLongOrNull()?.let { return it }
        Regex("""(?i)after\s+(\d+)\s*s\b""").find(message)?.groupValues?.get(1)
            ?.toLongOrNull()?.let { return it }
        Regex("""(?i)timeout[=:]\s*(\d+)\s*s""").find(message)?.groupValues?.get(1)
            ?.toLongOrNull()?.let { return it }
        return null
    }

    fun formatDuration(totalSeconds: Long): String {
        if (totalSeconds < 60) return "${totalSeconds}s"
        val m = totalSeconds / 60
        val s = totalSeconds % 60
        return if (s == 0L) "${m}m" else "${m}m ${s}s"
    }

    /**
     * Keep any tokens already streamed, then append timeout recovery.
     * Never drops partial work on a mid-stream timeout.
     */
    fun mergePartialWithTimeout(
        partialContent: String?,
        timeoutMessage: String,
        softCap: Int = PARTIAL_SOFT_CAP
    ): String {
        val recovery = timeoutMessage.trim()
        val partial = partialContent?.trim().orEmpty()
        if (partial.isEmpty()) return recovery
        val body = if (partial.length <= softCap) {
            partial
        } else {
            partial.take(softCap).trimEnd() + "\n… (partial reply truncated for display)"
        }
        return "$body\n\n— Timed out (partial reply kept) —\n$recovery"
    }

    /**
     * Full operator-facing timeout text including optional partial stream content.
     */
    fun formatTimeoutFailureWithPartial(
        provider: ModelProvider,
        agentMode: Boolean = false,
        timeoutSeconds: Long? = null,
        detail: String? = null,
        partialContent: String? = null
    ): String {
        val recovery = formatTimeoutFailure(
            provider = provider,
            agentMode = agentMode,
            timeoutSeconds = timeoutSeconds,
            detail = detail
        )
        return mergePartialWithTimeout(partialContent, recovery)
    }

    /** Soft cap for partial content embedded in timeout bubbles. */
    const val PARTIAL_SOFT_CAP = 12_000
}
