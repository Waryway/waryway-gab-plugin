package com.waryway.gab.ui

import com.waryway.gab.client.GabClient
import com.waryway.gab.client.GrokBuildAuthRecovery

/**
 * Pure Grok Build Send-path UX helpers (unit-testable, no Swing/IDE).
 *
 * **Auth / session recovery strings** live in [GrokBuildAuthRecovery] (section-01 single
 * string table). This object:
 * - re-exports thin wrappers for send-path callers (tool window / wo-04-02)
 * - adds **network / unreachable** copy distinct from [LocalLlmSendUx]
 * - maps Throwables via [formatFailure] (auth → network → generic `Error:`)
 *
 * Do **not** fork parallel `grok login` messaging here — extend [GrokBuildAuthRecovery]
 * if auth copy needs new cases.
 */
object GrokBuildSendUx {

    const val DEFAULT_PROXY_ROOT = "https://cli-chat-proxy.grok.com"
    const val DEFAULT_AUTH_PATH_DISPLAY = "~/.grok/auth.json"
    const val LOGIN_CMD = "grok login"

    // ── Session coaching (delegates to GrokBuildAuthRecovery) ────────────────

    /**
     * No usable session — see [GrokBuildAuthRecovery.coachingMissingSession].
     */
    fun coachingMissingSession(authPath: String = DEFAULT_AUTH_PATH_DISPLAY): String =
        GrokBuildAuthRecovery.coachingMissingSession(authPath = authPath)

    /**
     * Expired / near-expired session — see [GrokBuildAuthRecovery.coachingExpiredSession].
     */
    fun coachingExpiredSession(
        email: String? = null,
        authPath: String = DEFAULT_AUTH_PATH_DISPLAY
    ): String = GrokBuildAuthRecovery.coachingExpiredSession(email = email, authPath = authPath)

    // ── Auth-class HTTP (delegates to GrokBuildAuthRecovery) ─────────────────

    /**
     * Auth-class HTTP recovery copy, or `null` when not auth-class.
     * Delegates to [GrokBuildAuthRecovery.formatAuthFailure].
     *
     * [authPath] is accepted for call-site symmetry with session coaching but is not
     * currently threaded into the recovery table (AuthRecovery uses its default path).
     */
    @Suppress("UNUSED_PARAMETER")
    fun formatAuthFailure(
        message: String?,
        httpStatus: Int? = null,
        body: String? = null,
        authPath: String = DEFAULT_AUTH_PATH_DISPLAY
    ): String? = GrokBuildAuthRecovery.formatAuthFailure(
        message = message,
        httpStatus = httpStatus,
        body = body
    )

    /**
     * See [GrokBuildAuthRecovery.isAuthClassFailure].
     */
    fun isAuthClassFailure(
        message: String?,
        httpStatus: Int? = null,
        body: String? = null
    ): Boolean = GrokBuildAuthRecovery.isAuthClassFailure(
        message = message,
        httpStatus = httpStatus,
        body = body
    )

    // ── Network / unreachable (SendUx-owned; not Local LLM) ──────────────────

    /**
     * cli-chat-proxy unreachable — distinct from [LocalLlmSendUx.offlineMessage]
     * (no LocalLLM start script; names the proxy host).
     */
    fun networkMessage(
        detail: String? = null,
        proxyRoot: String = DEFAULT_PROXY_ROOT
    ): String {
        val root = normalizeProxyRoot(proxyRoot)
        val d = detail?.trim().orEmpty().ifBlank { "connection failed" }
        return "Grok Build unreachable at $root — check network / VPN, then retry. ($d)"
    }

    /**
     * Connection/DNS/refused style failures. Detection shared with
     * [LocalLlmSendUx.isUnreachableError]; **message text** uses [networkMessage].
     */
    fun isUnreachableError(message: String): Boolean =
        LocalLlmSendUx.isUnreachableError(message)

    // ── Send-path mapping ────────────────────────────────────────────────────

    /**
     * Map Send/chat failures to operator-facing Grok Build recovery copy.
     *
     * Priority: auth-class ([GrokBuildAuthRecovery]) → network/unreachable →
     * generic `Error: …` prefix (same shape as non-connect [LocalLlmSendUx.formatFailure]),
     * with optional short body detail from [GabClient.GabApiException.body].
     *
     * Accepts optional [httpStatus] / [body] when the caller already extracted them
     * (e.g. from [GabClient.GabApiException]); otherwise status is parsed from the
     * exception message when present.
     */
    fun formatFailure(
        error: Throwable?,
        httpStatus: Int? = null,
        body: String? = null,
        authPath: String = DEFAULT_AUTH_PATH_DISPLAY,
        proxyRoot: String = DEFAULT_PROXY_ROOT
    ): String {
        val msg = error?.message?.trim().orEmpty()
        val cls = error?.javaClass?.simpleName.orEmpty()
        val apiBody = body ?: (error as? GabClient.GabApiException)?.body
        val status = httpStatus ?: extractHttpStatus(msg)

        formatAuthFailure(msg, status, apiBody, authPath)?.let { return it }

        val combined = listOf(cls, msg).filter { it.isNotBlank() }.joinToString(" ")
        if (isUnreachableError(msg) || isUnreachableError(combined)) {
            val detail = msg.ifBlank { cls.ifBlank { "connection failed" } }
            return networkMessage(detail = detail, proxyRoot = proxyRoot)
        }

        val base = msg.ifBlank { cls.ifBlank { "unknown failure" } }
        val snippet = shortBodyDetail(apiBody)
        return if (snippet != null && !base.contains(snippet)) {
            "Error: $base ($snippet)"
        } else {
            "Error: $base"
        }
    }

    /**
     * Collapse response body to a single-line operator snippet (≤ [MAX_BODY_SNIPPET] chars).
     * Returns null when blank after cleanup.
     */
    fun shortBodyDetail(body: String?, maxChars: Int = MAX_BODY_SNIPPET): String? {
        if (body.isNullOrBlank()) return null
        val oneLine = body.replace(Regex("\\s+"), " ").trim()
        if (oneLine.isEmpty()) return null
        return if (oneLine.length <= maxChars) oneLine else oneLine.take(maxChars).trimEnd() + "…"
    }

    /** Max characters of API error body shown in generic `Error:` messages. */
    const val MAX_BODY_SNIPPET = 200

    /**
     * Parse first `HTTP NNN` token from free text (e.g. `Chat failed: HTTP 401`).
     */
    fun extractHttpStatus(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val m = Regex("""(?i)\bHTTP\s+(\d{3})\b""").find(text) ?: return null
        return m.groupValues[1].toIntOrNull()
    }

    /**
     * Strip trailing `/v1` and slashes for display of the proxy root.
     */
    fun normalizeProxyRoot(baseUrl: String, fallback: String = DEFAULT_PROXY_ROOT): String {
        var u = baseUrl.trim().trimEnd('/')
        if (u.length >= 3 && u.endsWith("/v1", ignoreCase = true)) {
            u = u.dropLast(3).trimEnd('/')
        }
        return u.ifBlank { fallback }
    }
}
