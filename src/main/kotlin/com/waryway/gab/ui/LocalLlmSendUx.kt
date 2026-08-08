package com.waryway.gab.ui

/**
 * Pure Local LLM Send-path UX strings and labels (unit-testable, no Swing/IDE).
 *
 * Separates:
 * - **config**: blank base URL
 * - **auth**: HTTP 401 / invalid_api_key (must not look like empty assistant silence)
 * - **ops**: server down / connection refused (health preflight + exception path)
 * - **routing**: Agent (/api/agent) vs Chat (/v1/chat/completions) + dry-run vs APPLY
 */
object LocalLlmSendUx {

    const val DEFAULT_ROOT = "http://127.0.0.1:7400"
    const val START_HINT = "scripts\\localllm-run.bat"
    /** Aligns with data/localllm/config.json openai.apiKey default. */
    const val DEFAULT_API_KEY = "localllm-local"

    /** Workbench checkbox label — path is explicit so operators need no tribal knowledge. */
    const val AGENT_MODE_CHECK_LABEL = "Agent mode → /api/agent"

    /**
     * Interval for “still generating…” chat status while Local SSE is silent
     * (go-cpu often needs ~90s before first tokens — empty silence is the failure mode).
     */
    const val STILL_GENERATING_INTERVAL_MS: Long = 30_000L

    /**
     * Operator-facing progress line while Chat waits for first / further SSE tokens.
     * Pure / unit-testable — no Swing.
     *
     * @param elapsedSeconds wall time since this completion started
     * @param streamBudgetSeconds effective [GabClient.streamTimeoutSeconds] (0 = omit budget clause)
     */
    fun stillGeneratingStatus(elapsedSeconds: Long, streamBudgetSeconds: Long = 0L): String {
        val elapsed = elapsedSeconds.coerceAtLeast(0L)
        val elapsedLabel = AgentTimeoutUx.formatDuration(elapsed)
        val budgetPart = if (streamBudgetSeconds > 0L) {
            "; stream budget ${AgentTimeoutUx.formatDuration(streamBudgetSeconds)}"
        } else {
            ""
        }
        return "▸ Still generating… ($elapsedLabel elapsed$budgetPart). " +
            "Local go-cpu often needs ~90s+ before first tokens — not a hang yet."
    }

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
    fun normalizeRootUrl(baseUrl: String, fallback: String = DEFAULT_ROOT): String =
        com.waryway.gab.client.AgentClient.normalizeLocalRootUrl(baseUrl, fallback)

    /**
     * Map Send/start failures to operator-facing copy.
     * Priority: auth/401 → unreachable → timeout recovery → generic `Error:`.
     */
    fun formatFailure(
        error: Throwable?,
        agentMode: Boolean,
        rootUrl: String = DEFAULT_ROOT
    ): String {
        val msg = error?.message?.trim().orEmpty()
        val cls = error?.javaClass?.simpleName.orEmpty()
        val combined = listOf(cls, msg).filter { it.isNotBlank() }.joinToString(" ")
        val api = error as? com.waryway.gab.client.GabClient.GabApiException
        val transportBody = api?.body.orEmpty()
        // Auth first — never leave the operator with a blank assistant bubble,
        // and never reclassify 401 as stream timeout.
        formatAuthFailure(error, agentMode)?.let { return it }
        // Connect / cold server → unreachable (distinct from stream budget timeout).
        val connectClass = com.waryway.gab.client.GabClient.isConnectClassTransport(cls, msg) ||
            com.waryway.gab.client.GabClient.isConnectClassTransport(cls, transportBody) ||
            isUnreachableError(msg) ||
            isUnreachableError(combined) ||
            isUnreachableError(transportBody)
        if (connectClass) {
            val path = if (agentMode) "agent (/api/agent)" else "chat (/v1)"
            val root = normalizeRootUrl(rootUrl)
            val detail = msg.ifBlank {
                transportBody.ifBlank { cls.ifBlank { "connection failed" } }
            }
            return "LocalLLM unreachable for $path at $root — start $START_HINT. ($detail)"
        }
        // Explicit AUTH kind already handled; never treat AUTH as timeout below.
        if (api?.kind == com.waryway.gab.client.GabClient.GabApiException.Kind.AUTH) {
            return formatAuthFailure(error, agentMode)
                ?: "Local LLM auth failed (HTTP 401). Set API key to $DEFAULT_API_KEY and retry."
        }
        if (api?.kind == com.waryway.gab.client.GabClient.GabApiException.Kind.TIMEOUT ||
            AgentTimeoutUx.isTimeoutError(error) ||
            AgentTimeoutUx.isTimeoutMessage(msg) ||
            AgentTimeoutUx.isTimeoutMessage(combined)
        ) {
            val secs = AgentTimeoutUx.extractTimeoutSeconds(msg)
                ?: api?.message?.let { AgentTimeoutUx.extractTimeoutSeconds(it) }
            val partial = when (error) {
                is com.waryway.gab.client.GabClient.GabApiException -> error.partialContent
                is com.waryway.gab.chat.LocalLlmAgentSession.AgentTimeoutException ->
                    error.partialContent
                else -> null
            }
            return AgentTimeoutUx.formatTimeoutFailureWithPartial(
                provider = com.waryway.gab.model.ModelProvider.LOCAL_LLM,
                agentMode = agentMode,
                timeoutSeconds = secs,
                detail = msg.takeIf { it.isNotBlank() && it.length <= 120 },
                partialContent = partial
            )
        }
        // Transport failures with partial stream content — keep the tokens.
        val partial = api?.partialContent?.trim().orEmpty()
        if (partial.isNotEmpty() && api?.kind == com.waryway.gab.client.GabClient.GabApiException.Kind.TRANSPORT) {
            val base = "Error: ${msg.ifBlank { cls.ifBlank { "unknown failure" } }}"
            return AgentTimeoutUx.mergePartialWithTimeout(partial, base)
                .replace("— Timed out (partial reply kept) —", "— Interrupted (partial reply kept) —")
        }
        return "Error: ${msg.ifBlank { cls.ifBlank { "unknown failure" } }}"
    }

    /**
     * Operator-facing copy for missing/wrong Bearer when the server requires a key.
     * Returns null when [error] is not an auth-class failure.
     */
    fun formatAuthFailure(error: Throwable?, agentMode: Boolean = false): String? {
        if (!isAuthError(error)) return null
        val api = error as? com.waryway.gab.client.GabClient.GabApiException
        val path = if (agentMode) "agent" else "chat (/v1/chat/completions)"
        val serverHint = api?.let {
            com.waryway.gab.client.GabClient.extractOpenAiErrorMessage(it.body)
        }?.takeIf { it.isNotBlank() }
        val detail = serverHint ?: "invalid or missing API key"
        return "Local LLM auth failed (HTTP 401) on $path — $detail. " +
            "Set Settings → Local LLM API key to match data/localllm/config.json openai.apiKey " +
            "(default $DEFAULT_API_KEY). Empty/wrong key is not silent — fix the key and retry."
    }

    /** True for 401 / invalid_api_key / GabApiException.Kind.AUTH. */
    fun isAuthError(error: Throwable?): Boolean {
        if (error == null) return false
        val api = error as? com.waryway.gab.client.GabClient.GabApiException
        if (api?.kind == com.waryway.gab.client.GabClient.GabApiException.Kind.AUTH) return true
        val msg = error.message.orEmpty()
        val body = api?.body.orEmpty()
        if (com.waryway.gab.client.GabClient.isInvalidApiKeyBody(body)) return true
        val m = msg.lowercase()
        return m.contains("http 401") ||
            m.contains("invalid_api_key") ||
            (m.contains("invalid") && m.contains("api key"))
    }

    /**
     * True for connect/DNS/refused style failures — not agent poll / stream timeouts
     * (`Agent run timed out after …`, `Chat timed out after …`).
     */
    fun isUnreachableError(message: String): Boolean {
        val m = message.lowercase()
        if (m.isBlank()) return false
        // Keep agent poll / stream timeouts distinct from cold server.
        if (AgentTimeoutUx.isTimeoutMessage(message)) return false
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
