package com.waryway.gab.ui

/**
 * Pure Local LLM Send-path UX strings and labels (unit-testable, no Swing/IDE).
 *
 * Separates:
 * - **config**: blank base URL
 * - **ops**: server down / connection refused (health preflight + exception path)
 * - **routing**: Agent (/api/agent) vs Chat (/v1/chat/completions) + dry-run vs APPLY
 */
object LocalLlmSendUx {

    const val DEFAULT_ROOT = "http://127.0.0.1:7400"
    const val START_HINT = "scripts\\localllm-run.bat"

    /** Workbench checkbox label — path is explicit so operators need no tribal knowledge. */
    const val AGENT_MODE_CHECK_LABEL = "Agent mode → /api/agent"

    /**
     * Badge / status for next Send.
     * Examples: `Agent · dry-run`, `Agent · APPLY`, `Chat`.
     */
    fun sendPathLabel(agentMode: Boolean, dryRun: Boolean): String = when {
        !agentMode -> "Chat"
        dryRun -> "Agent · dry-run"
        else -> "Agent · APPLY"
    }

    fun sendPathToolTip(agentMode: Boolean, dryRun: Boolean): String = when {
        !agentMode ->
            "Next Send uses /v1/chat/completions (chat only — no server plan/tools)"
        dryRun ->
            "Next Send uses /api/agent with dryRun=true (plan + tools, no workspace writes)"
        else ->
            "Next Send uses /api/agent with dryRun=false (mutating tools allowed)"
    }

    /** Longer workbench status line under mode controls. */
    fun sendPathStatusLine(agentMode: Boolean, dryRun: Boolean): String =
        "Next Send: ${sendPathLabel(agentMode, dryRun)}"

    /**
     * Early gate when Local LLM base URL is blank (config).
     * Must **not** claim the server is unreachable — that is health/exception copy.
     */
    fun blankBaseUrlMessage(): String =
        "Local LLM base URL is blank — set it in Plugin Settings " +
            "(default $DEFAULT_ROOT/v1). This is not a live connection check."

    /**
     * Health preflight failed or server cold at [rootUrl].
     * Actionable start hint for Windows ops scripts.
     */
    fun offlineMessage(rootUrl: String = DEFAULT_ROOT): String {
        val root = rootUrl.trim().ifBlank { DEFAULT_ROOT }
        return "LocalLLM not running at $root — start $START_HINT, then retry."
    }

    /**
     * Strip trailing `/v1` and slashes — same rule as [com.waryway.gab.client.LocalLLMService]
     * and [com.waryway.gab.client.AgentClient].
     */
    fun normalizeRootUrl(baseUrl: String, fallback: String = DEFAULT_ROOT): String {
        var u = baseUrl.trim().trimEnd('/')
        if (u.endsWith("/v1")) {
            u = u.removeSuffix("/v1").trimEnd('/')
        }
        return u.ifBlank { fallback }
    }

    /**
     * Map Send/start failures to operator-facing copy.
     * Connection-style failures get offline guidance; agent poll timeouts keep original text.
     */
    fun formatFailure(
        error: Throwable?,
        agentMode: Boolean,
        rootUrl: String = DEFAULT_ROOT
    ): String {
        val msg = error?.message?.trim().orEmpty()
        val cls = error?.javaClass?.simpleName.orEmpty()
        val combined = listOf(cls, msg).filter { it.isNotBlank() }.joinToString(" ")
        if (isUnreachableError(msg) || isUnreachableError(combined)) {
            val path = if (agentMode) "agent (/api/agent)" else "chat (/v1)"
            val root = normalizeRootUrl(rootUrl)
            val detail = msg.ifBlank { cls.ifBlank { "connection failed" } }
            return "LocalLLM unreachable for $path at $root — start $START_HINT. ($detail)"
        }
        return "Error: ${msg.ifBlank { cls.ifBlank { "unknown failure" } }}"
    }

    /**
     * True for connect/DNS/refused style failures — not agent poll timeouts
     * (`Agent run timed out after …`).
     */
    fun isUnreachableError(message: String): Boolean {
        val m = message.lowercase()
        if (m.isBlank()) return false
        // Keep agent poll / run timeouts distinct from cold server.
        if (m.contains("agent run timed out")) return false
        if (m.contains("agenttimeoutexception")) return false
        return m.contains("connection refused") ||
            m.contains("connect timed out") ||
            m.contains("http connect timed out") ||
            m.contains("connection reset") ||
            m.contains("failed to connect") ||
            m.contains("no route to host") ||
            m.contains("network is unreachable") ||
            m.contains("connectionexception") ||
            m.contains("connectexception") ||
            m.contains("unknownhost") ||
            m.contains("unknown host") ||
            (m.contains("connect") && m.contains("refused"))
    }
}
