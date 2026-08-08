package com.waryway.gab.client

import com.waryway.gab.diagnostics.LogLevel
import com.waryway.gab.diagnostics.SessionLog
import com.waryway.gab.settings.WarywayGabSettings
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * HTTP client for LocalLLM server agent API (paths under /api/agent).
 *
 * Separate from [LocalLLMService] (status/corpus/rebuild) and [GabClient] (OpenAI chat).
 * Uses the same [rootUrl] resolution as [LocalLLMService] (strip trailing /v1).
 *
 * Agent routes do not require a Bearer key; auth is sent only when a Local LLM API key is set.
 *
 * Callers must supply [startRun] dryRun explicitly — no silent apply default.
 *
 * LOCAL_LLM `/api/agent` only — not used for GROK_BUILD / cloud chat providers.
 */
class AgentClient(
    private val settings: WarywayGabSettings? = null,
    private val sessionLog: SessionLog? = null,
    /** When set (e.g. unit tests), skips [WarywayGabSettings] lookup for base URL. */
    private val rootUrlOverride: String? = null
) {
    private fun log(level: LogLevel, message: String) = sessionLog?.log(level, message)

    private fun settingsOrDefault(): WarywayGabSettings =
        settings ?: WarywayGabSettings.getInstance()

    /** Base URL without trailing /v1, same rule as [LocalLlmSendUx.normalizeRootUrl]. */
    val rootUrl: String
        get() = normalizeLocalRootUrl(rootUrlOverride ?: settingsOrDefault().localLlmBaseUrl)

    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .build()

    // ── DTOs (match pkg/localllm/agent/types.go + live probe JSON) ──────────

    data class AgentTool(
        val name: String,
        val description: String = "",
        val inputSchema: String? = null,
        val category: String? = null,
        val local: Boolean = false
    )

    data class AgentEvent(
        val at: String? = null,
        val kind: String = "",
        val taskId: String? = null,
        val tool: String? = null,
        val detail: String = ""
    )

    data class AgentTask(
        val id: String = "",
        val title: String = "",
        val description: String = "",
        val dependsOn: List<String> = emptyList(),
        val tool: String? = null,
        /** Raw JSON object/string for tool args (server `toolArgs`). */
        val toolArgs: String? = null,
        val status: String = "",
        val result: String? = null,
        val error: String? = null
    )

    data class AgentPlan(
        val summary: String = "",
        val tasks: List<AgentTask> = emptyList()
    )

    data class AgentRun(
        val id: String = "",
        val goal: String = "",
        val state: String = "",
        val preset: String = "",
        val model: String? = null,
        val repoRoot: String = "",
        val plan: AgentPlan? = null,
        val events: List<AgentEvent> = emptyList(),
        val step: Int = 0,
        val maxSteps: Int = 0,
        val dryRun: Boolean = true,
        /** Server progress line (planning / tool name / heuristic fallback). */
        val message: String? = null,
        val createdAt: String? = null,
        val updatedAt: String? = null,
        val finalAnswer: String? = null,
        val error: String? = null
    ) {
        val isTerminal: Boolean
            get() = state in TERMINAL_STATES
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /** GET /api/agent/tools — capability / catalog check. */
    fun listTools(): List<AgentTool> {
        val url = "$rootUrl/api/agent/tools"
        log(LogLevel.HTTP, "GET $url")
        val body = get(url)
        val tools = parseToolsResponse(body)
        log(LogLevel.SYSTEM, "agent tools: ${tools.size}")
        return tools
    }

    /**
     * POST /api/agent/runs — start async run; expects HTTP 202 + [AgentRun].
     *
     * [dryRun] is always serialized as a JSON boolean (never omitted).
     */
    fun startRun(
        goal: String,
        dryRun: Boolean,
        preset: String? = null,
        model: String? = null,
        maxSteps: Int? = null
    ): AgentRun {
        require(goal.isNotBlank()) { "goal must not be blank" }
        val url = "$rootUrl/api/agent/runs"
        val json = buildStartBody(goal, dryRun, preset, model, maxSteps)
        log(LogLevel.HTTP, "POST $url dryRun=$dryRun preset=${preset.orEmpty()} goalChars=${goal.length}")
        val body = postExpect(url, json, accepted = setOf(200, 202))
        val run = parseRun(body)
        log(LogLevel.SYSTEM, "agent start: id=${run.id} state=${run.state} dryRun=${run.dryRun}")
        return run
    }

    /**
     * GET /api/agent/runs/{id} — poll run snapshot.
     *
     * [quiet] skips per-poll HTTP/SYS log lines. Poll loops should pass quiet=true and
     * log only on state/step/message changes — pure-Go local LLM planning can take minutes
     * and otherwise floods the activity log with identical `state=planning step=0/N` lines.
     */
    fun getRun(id: String, quiet: Boolean = false): AgentRun {
        require(id.isNotBlank()) { "run id must not be blank" }
        val url = "$rootUrl/api/agent/runs/${id.trim()}"
        if (!quiet) log(LogLevel.HTTP, "GET $url")
        val body = get(url)
        val run = parseRun(body)
        if (!quiet) {
            log(
                LogLevel.SYSTEM,
                "agent get: id=${run.id} state=${run.state} step=${run.step}/${run.maxSteps}" +
                    run.message?.takeIf { it.isNotBlank() }?.let { " msg=$it" }.orEmpty()
            )
        }
        return run
    }

    /** POST /api/agent/runs/{id}/cancel */
    fun cancelRun(id: String): AgentRun {
        require(id.isNotBlank()) { "run id must not be blank" }
        val url = "$rootUrl/api/agent/runs/${id.trim()}/cancel"
        log(LogLevel.HTTP, "POST $url")
        val body = postExpect(url, "{}", accepted = setOf(200))
        val run = parseRun(body)
        log(LogLevel.SYSTEM, "agent cancel: id=${run.id} state=${run.state}")
        return run
    }

    /** POST /api/agent/runs/{id}/resume — paused/failed to re-execute. */
    fun resumeRun(id: String): AgentRun {
        require(id.isNotBlank()) { "run id must not be blank" }
        val url = "$rootUrl/api/agent/runs/${id.trim()}/resume"
        log(LogLevel.HTTP, "POST $url")
        val body = postExpect(url, "{}", accepted = setOf(200))
        val run = parseRun(body)
        log(LogLevel.SYSTEM, "agent resume: id=${run.id} state=${run.state}")
        return run
    }

    // ── HTTP ───────────────────────────────────────────────────────────────

    private fun get(url: String): String {
        val req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Accept", "application/json")
            .apply { authHeader()?.let { header("Authorization", it) } }
            .GET()
            .timeout(Duration.ofSeconds(30))
            .build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        return checkResponse("GET", url, resp, accepted = setOf(200))
    }

    private fun postExpect(url: String, json: String, accepted: Set<Int>): String {
        val req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .apply { authHeader()?.let { header("Authorization", it) } }
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .timeout(Duration.ofSeconds(60))
            .build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        return checkResponse("POST", url, resp, accepted)
    }

    private fun checkResponse(
        method: String,
        url: String,
        resp: HttpResponse<String>,
        accepted: Set<Int>
    ): String {
        val code = resp.statusCode()
        val body = resp.body().orEmpty()
        if (code !in accepted) {
            val snippet = body.take(300).replace('\n', ' ')
            log(LogLevel.ERROR, "$method $url → HTTP $code: $snippet")
            throw AgentException("Agent $method failed: HTTP $code: ${snippet.take(200)}", code, body)
        }
        return body
    }

    private fun authHeader(): String? {
        val key = settingsOrDefault().getApiKey(com.waryway.gab.model.ModelProvider.LOCAL_LLM)?.trim()
        return if (key.isNullOrBlank()) null else "Bearer $key"
    }

    // ── Request body ───────────────────────────────────────────────────────

    internal fun buildStartBody(
        goal: String,
        dryRun: Boolean,
        preset: String?,
        model: String?,
        maxSteps: Int?
    ): String = buildString {
        append('{')
        append("\"goal\":").append(jsonString(goal))
        // Explicit boolean — never omit (server pointer override + IDE safety)
        append(",\"dryRun\":").append(dryRun)
        if (!preset.isNullOrBlank()) {
            append(",\"preset\":").append(jsonString(preset))
        }
        if (!model.isNullOrBlank()) {
            append(",\"model\":").append(jsonString(model))
        }
        if (maxSteps != null && maxSteps > 0) {
            append(",\"maxSteps\":").append(maxSteps)
        }
        append('}')
    }

    // ── Parse ──────────────────────────────────────────────────────────────

    internal fun parseToolsResponse(body: String): List<AgentTool> {
        val arrRaw = arrayFieldRaw(body, "tools") ?: return emptyList()
        return objectSlices(arrRaw).map { obj ->
            AgentTool(
                name = stringField(obj, "name") ?: "",
                description = stringField(obj, "description") ?: "",
                inputSchema = objectOrRawField(obj, "inputSchema"),
                category = stringField(obj, "category"),
                local = boolField(obj, "local") ?: false
            )
        }.filter { it.name.isNotBlank() }
    }

    internal fun parseRun(body: String): AgentRun {
        val plan = parsePlan(body)
        val events = parseEvents(body)
        return AgentRun(
            id = stringField(body, "id") ?: "",
            goal = stringField(body, "goal") ?: "",
            state = stringField(body, "state") ?: "",
            preset = stringField(body, "preset") ?: "",
            model = stringField(body, "model"),
            repoRoot = stringField(body, "repoRoot") ?: "",
            plan = plan,
            events = events,
            step = intField(body, "step") ?: 0,
            maxSteps = intField(body, "maxSteps") ?: 0,
            dryRun = boolField(body, "dryRun") ?: true,
            message = stringField(body, "message"),
            createdAt = stringField(body, "createdAt"),
            updatedAt = stringField(body, "updatedAt"),
            finalAnswer = stringField(body, "finalAnswer"),
            error = stringField(body, "error")
        )
    }

    private fun parsePlan(body: String): AgentPlan? {
        val planObj = extractObjectField(body, "plan") ?: return null
        val tasks = parseTasks(planObj)
        return AgentPlan(
            summary = stringField(planObj, "summary") ?: "",
            tasks = tasks
        )
    }

    private fun parseTasks(planObj: String): List<AgentTask> {
        val arrRaw = arrayFieldRaw(planObj, "tasks") ?: return emptyList()
        return objectSlices(arrRaw).map { obj ->
            AgentTask(
                id = stringField(obj, "id") ?: "",
                title = stringField(obj, "title") ?: "",
                description = stringField(obj, "description") ?: "",
                dependsOn = stringArrayField(obj, "dependsOn"),
                tool = stringField(obj, "tool"),
                toolArgs = objectOrRawField(obj, "toolArgs"),
                status = stringField(obj, "status") ?: "",
                result = stringField(obj, "result"),
                error = stringField(obj, "error")
            )
        }
    }

    private fun parseEvents(body: String): List<AgentEvent> {
        val arrRaw = arrayFieldRaw(body, "events") ?: return emptyList()
        return objectSlices(arrRaw).map { obj ->
            AgentEvent(
                at = stringField(obj, "at"),
                kind = stringField(obj, "kind") ?: "",
                taskId = stringField(obj, "taskId"),
                tool = stringField(obj, "tool"),
                detail = stringField(obj, "detail") ?: ""
            )
        }
    }

    /** Inner content of a JSON array field (without surrounding brackets). */
    private fun arrayFieldRaw(source: String, key: String): String? {
        val start = valueStart(source, key) ?: return null
        if (start >= source.length || source[start] != '[') return null
        val end = findMatchingBracket(source, start, '[', ']') ?: return null
        return source.substring(start + 1, end)
    }

    /** Slice top-level JSON objects from a JSON array body (without brackets). */
    private fun objectSlices(inner: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < inner.length) {
            val start = inner.indexOf('{', i)
            if (start < 0) break
            val end = findMatchingBracket(inner, start, '{', '}') ?: break
            out.add(inner.substring(start, end + 1))
            i = end + 1
        }
        return out
    }

    // ── Lightweight JSON field helpers ─────────────────────────────────────

    /**
     * Index of `"key"` at brace depth 0 within [source] (object body or full `{...}`).
     * Avoids matching nested task `id`/`error` when reading a run snapshot.
     */
    private fun indexOfKey(source: String, key: String, atDepth: Int = 0): Int? {
        val pattern = "\"$key\""
        var depth = 0
        var inString = false
        var escaped = false
        var i = 0
        // If source is a full object, start depth at 0 and track braces
        while (i < source.length) {
            val c = source[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else when (c) {
                    '\\' -> escaped = true
                    '"' -> inString = false
                }
                i++
                continue
            }
            when (c) {
                '"' -> {
                    if (depth == atDepth && source.startsWith(pattern, i)) {
                        val after = i + pattern.length
                        var j = after
                        while (j < source.length && source[j].isWhitespace()) j++
                        if (j < source.length && source[j] == ':') return i
                    }
                    inString = true
                }
                '{' -> depth++
                '}' -> depth = (depth - 1).coerceAtLeast(0)
                '[' -> depth++
                ']' -> depth = (depth - 1).coerceAtLeast(0)
            }
            i++
        }
        return null
    }

    private fun valueStart(source: String, key: String): Int? {
        // Keys inside a sliced object `{...}` sit at depth 1 (outer braces).
        val atDepth = if (source.isNotEmpty() && source[0] == '{') 1 else 0
        val keyIdx = indexOfKey(source, key, atDepth = atDepth) ?: return null
        val colon = source.indexOf(':', keyIdx + key.length + 2)
        if (colon < 0) return null
        var i = colon + 1
        while (i < source.length && source[i].isWhitespace()) i++
        return i
    }

    private fun stringField(source: String, key: String): String? {
        val start = valueStart(source, key) ?: return null
        if (start >= source.length) return null
        when (source[start]) {
            '"' -> return readJsonString(source, start + 1)
            'n' -> if (source.startsWith("null", start)) return null
        }
        return null
    }

    private fun intField(source: String, key: String): Int? {
        val start = valueStart(source, key) ?: return null
        if (start >= source.length) return null
        if (source[start] == 'n' && source.startsWith("null", start)) return null
        var end = start
        if (end < source.length && source[end] == '-') end++
        while (end < source.length && source[end].isDigit()) end++
        if (end == start || (end == start + 1 && source[start] == '-')) return null
        return source.substring(start, end).toIntOrNull()
    }

    private fun boolField(source: String, key: String): Boolean? {
        val start = valueStart(source, key) ?: return null
        return when {
            source.startsWith("true", start) -> true
            source.startsWith("false", start) -> false
            source.startsWith("null", start) -> null
            else -> null
        }
    }

    private fun stringArrayField(source: String, key: String): List<String> {
        val start = valueStart(source, key) ?: return emptyList()
        if (start >= source.length || source[start] != '[') return emptyList()
        val end = findMatchingBracket(source, start, '[', ']') ?: return emptyList()
        val inner = source.substring(start + 1, end)
        val out = mutableListOf<String>()
        var i = 0
        while (i < inner.length) {
            while (i < inner.length && (inner[i].isWhitespace() || inner[i] == ',')) i++
            if (i >= inner.length) break
            if (inner[i] != '"') break
            val s = readJsonString(inner, i + 1) ?: break
            out.add(s)
            // Skip the quoted literal using the same string rules as the matcher
            i++ // opening "
            var escaped = false
            while (i < inner.length) {
                val c = inner[i++]
                if (escaped) {
                    escaped = false
                    continue
                }
                when (c) {
                    '\\' -> escaped = true
                    '"' -> break
                }
            }
        }
        return out
    }

    /** Extract nested object value for [key], or raw non-null scalar/array as string. */
    private fun objectOrRawField(source: String, key: String): String? {
        val start = valueStart(source, key) ?: return null
        if (start >= source.length) return null
        return when (source[start]) {
            'n' -> if (source.startsWith("null", start)) null else null
            '{' -> {
                val end = findMatchingBracket(source, start, '{', '}') ?: return null
                source.substring(start, end + 1)
            }
            '[' -> {
                val end = findMatchingBracket(source, start, '[', ']') ?: return null
                source.substring(start, end + 1)
            }
            '"' -> {
                val s = readJsonString(source, start + 1)
                s
            }
            else -> {
                var end = start
                while (end < source.length && source[end] !in ",}]") end++
                source.substring(start, end).trim().takeIf { it.isNotEmpty() }
            }
        }
    }

    private fun extractObjectField(source: String, key: String): String? {
        val start = valueStart(source, key) ?: return null
        if (start >= source.length) return null
        if (source[start] == 'n' && source.startsWith("null", start)) return null
        if (source[start] != '{') return null
        val end = findMatchingBracket(source, start, '{', '}') ?: return null
        return source.substring(start, end + 1)
    }

    private fun findMatchingBracket(source: String, openIndex: Int, open: Char, close: Char): Int? {
        if (openIndex < 0 || openIndex >= source.length || source[openIndex] != open) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in openIndex until source.length) {
            val c = source[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else when (c) {
                    '\\' -> escaped = true
                    '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }

    private fun readJsonString(source: String, start: Int): String? {
        val sb = StringBuilder()
        var i = start
        while (i < source.length) {
            val c = source[i]
            when (c) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (i + 1 >= source.length) return sb.toString()
                    when (source[i + 1]) {
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'u' -> {
                            if (i + 5 < source.length) {
                                val hex = source.substring(i + 2, i + 6)
                                hex.toIntOrNull(16)?.let { sb.append(it.toChar()) }
                                    ?: sb.append("\\u").append(hex)
                                i += 6
                                continue
                            }
                            sb.append(source[i + 1])
                            i += 2
                            continue
                        }
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

    private fun jsonString(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) {
                    sb.append("\\u").append("%04x".format(c.code))
                } else {
                    sb.append(c)
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    class AgentException(
        message: String,
        val statusCode: Int = 0,
        val body: String? = null
    ) : Exception(message)

    companion object {
        val TERMINAL_STATES: Set<String> = setOf("done", "failed", "cancelled")

        /**
         * Strip trailing slashes and a final `/v1` segment so
         * `http://host:7400/v1/` → `http://host:7400` (not left as `…/v1`).
         * Shared with [LocalLLMService] — keep in sync with [com.waryway.gab.ui.LocalLlmSendUx.normalizeRootUrl].
         */
        fun normalizeLocalRootUrl(
            baseUrl: String,
            fallback: String = "http://127.0.0.1:7400"
        ): String {
            var u = baseUrl.trim().trimEnd('/')
            if (u.length >= 3 && u.endsWith("/v1", ignoreCase = true)) {
                u = u.dropLast(3).trimEnd('/')
            }
            return u.ifBlank { fallback }
        }
    }
}
