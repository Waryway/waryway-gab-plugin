package com.waryway.gab.client

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Reads Grok Build (CLI) session credentials from `~/.grok/auth.json`.
 *
 * Grok Build uses OIDC browser login (`grok login`), **not** console.x.ai API keys.
 * That session is billed / quota'd like the local Grok Build CLI and GoLand AI Chat
 * ACP agent — separate from the prepaid API team at `api.x.ai`.
 *
 * File shape (simplified):
 * ```json
 * {
 *   "https://auth.x.ai::<client_id>": {
 *     "key": "<access_token>",
 *     "email": "user@example.com",
 *     "expires_at": "2026-07-17T17:01:23.391055800Z",
 *     ...
 *   }
 * }
 * ```
 */
object GrokBuildAuth {

    /** Minimum CLI version accepted by cli-chat-proxy (server-enforced). */
    const val CLIENT_VERSION = "0.2.102"

    const val TOKEN_AUTH_HEADER = "X-XAI-Token-Auth"
    const val TOKEN_AUTH_VALUE = "xai-grok-cli"
    const val CLIENT_VERSION_HEADER = "x-grok-client-version"
    const val MODEL_OVERRIDE_HEADER = "x-grok-model-override"
    const val CLIENT_SURFACE_HEADER = "x-grok-client-surface"
    const val CLIENT_SURFACE_VALUE = "waryway-gab-plugin"
    const val USER_AGENT = "xai-grok-build/$CLIENT_VERSION"

    /**
     * Pure header map for cli-chat-proxy requests (Authorization + Grok Build extras).
     *
     * Live application remains in [GabClient.applyProviderAuth]; this helper exists so
     * unit tests can lock the contract without HTTP. [modelForOverride] is omitted when
     * blank/null (listModels / non-chat GETs).
     */
    fun requestHeaders(accessToken: String, modelForOverride: String? = null): Map<String, String> {
        val headers = linkedMapOf(
            "Authorization" to "Bearer $accessToken",
            TOKEN_AUTH_HEADER to TOKEN_AUTH_VALUE,
            CLIENT_VERSION_HEADER to CLIENT_VERSION,
            CLIENT_SURFACE_HEADER to CLIENT_SURFACE_VALUE,
            "User-Agent" to USER_AGENT
        )
        val model = modelForOverride?.trim().orEmpty()
        if (model.isNotEmpty()) {
            headers[MODEL_OVERRIDE_HEADER] = model
        }
        return headers
    }

    data class Session(
        val accessToken: String,
        val email: String? = null,
        val expiresAt: Instant? = null,
        val teamId: String? = null,
        val authMode: String? = null,
        val scopeKey: String? = null
    ) {
        fun isExpired(now: Instant = Instant.now()): Boolean {
            val exp = expiresAt ?: return false
            // Treat near-expiry as expired so callers re-login before hard 401s.
            return !exp.isAfter(now.plusSeconds(60))
        }
    }

    fun authJsonPath(): Path {
        val home = System.getenv("GROK_HOME")
            ?.takeIf { it.isNotBlank() }
            ?: System.getProperty("user.home")
        return Path.of(home, ".grok", "auth.json")
    }

    fun readSession(): Session? {
        val path = authJsonPath()
        if (!Files.isRegularFile(path)) return null
        val text = runCatching { Files.readString(path) }.getOrNull() ?: return null
        return parseSession(text)
    }

    fun hasUsableSession(): Boolean {
        val session = readSession() ?: return false
        return session.accessToken.isNotBlank() && !session.isExpired()
    }

    /**
     * Best-effort parser for the multi-scope auth.json map.
     * Prefers entries whose key contains `auth.x.ai` / `accounts.x.ai`, then any entry with a `key`.
     */
    internal fun parseSession(json: String): Session? {
        if (json.isBlank()) return null
        val scopes = findTopLevelObjectEntries(json)
        if (scopes.isEmpty()) return null

        val preferred = scopes.firstOrNull { (name, _) ->
            name.contains("auth.x.ai", ignoreCase = true) ||
                name.contains("accounts.x.ai", ignoreCase = true)
        } ?: scopes.first()

        val body = preferred.second
        val token = extractJsonString(body, "key")?.trim().orEmpty()
        if (token.isBlank()) return null

        return Session(
            accessToken = token,
            email = extractJsonString(body, "email"),
            expiresAt = extractJsonString(body, "expires_at")?.let { parseInstant(it) },
            teamId = extractJsonString(body, "team_id"),
            authMode = extractJsonString(body, "auth_mode"),
            scopeKey = preferred.first
        )
    }

    private fun parseInstant(raw: String): Instant? =
        runCatching { Instant.parse(raw.trim()) }.getOrNull()
            ?: runCatching {
                // Tolerate fractional seconds longer than nanos (auth.json may use 9 digits).
                val normalized = raw.trim().replace(Regex("(\\.\\d{9})\\d+"), "$1")
                Instant.parse(normalized)
            }.getOrNull()

    /**
     * Finds `"scopeKey": { ... }` pairs at the top level of a JSON object.
     * Does not fully parse JSON; sufficient for auth.json's flat map of objects.
     */
    private fun findTopLevelObjectEntries(json: String): List<Pair<String, String>> {
        val start = json.indexOf('{')
        if (start < 0) return emptyList()
        val results = mutableListOf<Pair<String, String>>()
        var i = start + 1
        while (i < json.length) {
            while (i < json.length && json[i].isWhitespace()) i++
            if (i >= json.length || json[i] == '}') break
            if (json[i] != '"') {
                // Skip unexpected tokens conservatively.
                i++
                continue
            }
            val key = readJsonString(json, i + 1) ?: break
            i = skipPastString(json, i + 1) + 1
            while (i < json.length && (json[i].isWhitespace() || json[i] == ':')) i++
            if (i >= json.length || json[i] != '{') {
                // Value is not an object — skip until next comma/brace at this level is hard;
                // auth.json only uses object values, so stop.
                break
            }
            val end = findMatchingBrace(json, i) ?: break
            results.add(key to json.substring(i, end + 1))
            i = end + 1
            while (i < json.length && (json[i].isWhitespace() || json[i] == ',')) i++
        }
        return results
    }

    private fun extractJsonString(objectJson: String, field: String): String? {
        val key = "\"$field\""
        val idx = objectJson.indexOf(key)
        if (idx < 0) return null
        val colon = objectJson.indexOf(':', idx + key.length)
        if (colon < 0) return null
        var pos = colon + 1
        while (pos < objectJson.length && objectJson[pos].isWhitespace()) pos++
        if (pos >= objectJson.length || objectJson[pos] != '"') return null
        return readJsonString(objectJson, pos + 1)
    }

    private fun readJsonString(source: String, start: Int): String? {
        val sb = StringBuilder()
        var i = start
        while (i < source.length) {
            when (val c = source[i]) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (i + 1 >= source.length) return sb.toString()
                    when (source[i + 1]) {
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        else -> sb.append(source[i + 1])
                    }
                    i += 2
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return sb.toString()
    }

    private fun skipPastString(source: String, start: Int): Int {
        var i = start
        while (i < source.length) {
            when (source[i]) {
                '"' -> return i
                '\\' -> i += 2
                else -> i++
            }
        }
        return source.length - 1
    }

    private fun findMatchingBrace(source: String, openIndex: Int): Int? {
        if (openIndex < 0 || openIndex >= source.length || source[openIndex] != '{') return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in openIndex until source.length) {
            val c = source[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '"') {
                    inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }
}
