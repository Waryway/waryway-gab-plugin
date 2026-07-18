package com.waryway.gab.client

/**
 * Pure coaching / recovery copy for Grok Build session and auth-class HTTP failures.
 *
 * Single string table for missing vs expired session and 401/403-style failures.
 * Callers in settings UI, tool window, and section-04 UX should use this object rather
 * than inventing parallel `grok login` messaging.
 *
 * No Swing / IntelliJ UI dependencies — unit-testable with plain strings and status codes.
 */
object GrokBuildAuthRecovery {

    /** Classify a live [GrokBuildAuth.Session] without re-reading disk. */
    enum class SessionState {
        /** No auth.json or no usable token field. */
        MISSING,
        /** Token present but expired / within near-expiry skew. */
        EXPIRED,
        /** Non-blank token and not expired. */
        USABLE
    }

    fun classifySession(session: GrokBuildAuth.Session?): SessionState {
        if (session == null || session.accessToken.isBlank()) return SessionState.MISSING
        if (session.isExpired()) return SessionState.EXPIRED
        return SessionState.USABLE
    }

    /**
     * Coaching when `~/.grok/auth.json` is absent or has no session token.
     */
    fun coachingMissingSession(authPath: String = defaultAuthPath()): String =
        "No Grok Build session found. Run `grok login` in a terminal, then try again. " +
            "Expected auth file: $authPath"

    /**
     * Coaching when the live session exists but is expired or within the near-expiry skew.
     * Distinct from [coachingMissingSession] so operators know re-login (not first-time setup).
     */
    fun coachingExpiredSession(
        email: String? = null,
        authPath: String = defaultAuthPath()
    ): String {
        val who = email?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        return "Grok Build session expired$who. Run `grok login` again to refresh. " +
            "Auth file: $authPath"
    }

    /**
     * Maps auth-class HTTP failures to recovery copy that mentions re-login.
     *
     * @return recovery string when the failure is auth-class (401/403 or body/message hints);
     *   `null` when the failure is **not** auth-class so callers keep non-auth messages.
     */
    fun formatAuthFailure(
        message: String? = null,
        httpStatus: Int? = null,
        body: String? = null
    ): String? {
        if (!isAuthClassFailure(message, httpStatus, body)) return null
        val path = defaultAuthPath()
        val statusPart = httpStatus?.let { "HTTP $it" }
        val detail = listOfNotNull(
            statusPart,
            message?.trim()?.takeIf { it.isNotEmpty() }
        ).joinToString(": ").ifBlank { "unauthorized" }
        return "Grok Build authentication failed ($detail). " +
            "Run `grok login` to refresh your session, then retry. Auth file: $path"
    }

    /**
     * True when status/message/body indicate an auth-class failure worth login coaching.
     * Non-auth HTTP errors (e.g. 500, 502, 404) return false even if message mentions "error".
     */
    fun isAuthClassFailure(
        message: String? = null,
        httpStatus: Int? = null,
        body: String? = null
    ): Boolean {
        if (httpStatus == 401 || httpStatus == 403) return true
        val combined = listOfNotNull(message, body).joinToString("\n")
        if (combined.isBlank()) return false
        // Status codes embedded in error text (e.g. "HTTP 401", "status=403")
        if (AUTH_STATUS_IN_TEXT.containsMatchIn(combined)) return true
        // Explicit auth / unauthorized language without forcing every "forbidden" product message
        if (AUTH_HINT.containsMatchIn(combined)) return true
        return false
    }

    private fun defaultAuthPath(): String =
        runCatching { GrokBuildAuth.authJsonPath().toString() }
            .getOrElse { "~/.grok/auth.json" }

    private val AUTH_STATUS_IN_TEXT = Regex(
        """(?i)(?:\bHTTP\s*[/:]?\s*|status[=:\s]+)(401|403)\b|\b(401|403)\s+Unauthorized\b"""
    )

    private val AUTH_HINT = Regex(
        """(?i)\b(unauthorized|unauthenticated|invalid[_\s-]?token|expired[_\s-]?token|""" +
            """token[_\s-]?expired|not[_\s-]?authenticated|authentication[_\s-]?required|""" +
            """access[_\s-]?denied|invalid[_\s-]?session|session[_\s-]?expired)\b"""
    )
}
