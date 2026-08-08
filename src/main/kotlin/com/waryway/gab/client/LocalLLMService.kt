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
 * Native LocalLLM stack APIs (corpus, rebuild, presets) beyond OpenAI /v1.
 */
class LocalLLMService(
    private val settings: WarywayGabSettings = WarywayGabSettings.getInstance(),
    private val sessionLog: SessionLog? = null
) {
    private fun log(level: LogLevel, message: String) = sessionLog?.log(level, message)
    data class Preset(val id: String, val name: String, val category: String)
    data class Status(
        val ready: Boolean,
        val defaultModel: String,
        val models: List<String>,
        val corpusCount: Int,
        val presets: List<Preset>,
        val maxTokens: Int,
        val contextSize: Int
    )

    private val rootUrl: String
        get() = AgentClient.normalizeLocalRootUrl(settings.localLlmBaseUrl)

    private val client = HttpClient.newBuilder()
        // Short connect so offline LocalLLM never stalls workbench / tool-window open.
        .connectTimeout(Duration.ofSeconds(2))
        .build()

    fun fetchStatus(): Status {
        log(LogLevel.HTTP, "GET $rootUrl/v1/localllm/status")
        val body = get("$rootUrl/v1/localllm/status")
        val status = Status(
            ready = body.contains("\"ready\":true") || body.contains("\"ready\": true"),
            defaultModel = extractString(body, "defaultModel") ?: "localllm-coder",
            models = extractStringList(body, "models"),
            corpusCount = extractInt(body, "count") ?: extractNestedInt(body, "corpus", "count") ?: 0,
            presets = parsePresets(body),
            maxTokens = extractInt(body, "maxTokens") ?: 384,
            contextSize = extractInt(body, "contextSize") ?: 4096
        )
        log(
            LogLevel.SYSTEM,
            "status: ready=${status.ready} model=${status.defaultModel} corpus=${status.corpusCount} ctx=${status.contextSize}"
        )
        return status
    }

    fun fetchCorpusCount(): Int {
        log(LogLevel.HTTP, "GET $rootUrl/api/corpus")
        val body = get("$rootUrl/api/corpus")
        val count = extractNestedInt(body, "stats", "count") ?: extractInt(body, "count") ?: 0
        log(LogLevel.SYSTEM, "corpus count: $count")
        return count
    }

    fun collectExample(instruction: String, output: String, tags: List<String> = emptyList()): Boolean {
        val tagJson = tags.joinToString(",") { "\"${escape(it)}\"" }
        val tagsPart = if (tags.isEmpty()) "" else """, "tags": [$tagJson]"""
        val json = """
            {"instruction":${jsonString(instruction)},"output":${jsonString(output)},"source":"gab-plugin"$tagsPart}
        """.trimIndent()
        log(LogLevel.HTTP, "POST $rootUrl/api/corpus (${instruction.length} chars)")
        val ok = post("$rootUrl/api/corpus", json)
        log(if (ok) LogLevel.SYSTEM else LogLevel.ERROR, if (ok) "corpus collect ok" else "corpus collect failed")
        return ok
    }

    fun startRebuild(force: Boolean = false): String {
        val path = if (force) "$rootUrl/api/rebuild?force=1" else "$rootUrl/api/rebuild"
        log(LogLevel.HTTP, "POST $path")
        val body = postBody(path, "{}")
        val id = extractString(body, "id") ?: extractString(body, "error") ?: "rebuild started"
        log(LogLevel.SYSTEM, "rebuild: $id")
        return id
    }

    fun healthOk(): Boolean = runCatching {
        log(LogLevel.HTTP, "GET $rootUrl/healthz")
        val body = get("$rootUrl/healthz")
        val ok = body.contains("\"status\":\"ok\"") || body.contains("ok")
        log(LogLevel.SYSTEM, "healthz: ${if (ok) "ok" else body.take(120)}")
        ok
    }.getOrDefault(false).also { ok ->
        if (!ok) log(LogLevel.ERROR, "health check failed")
    }

    private fun get(url: String): String {
        val req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Accept", "application/json")
            .apply { authHeader()?.let { header("Authorization", it) } }
            .GET()
            .timeout(Duration.ofSeconds(5))
            .build()
        val resp = try {
            client.send(req, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            val detail = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            throw LocalLLMException("GET $url failed: $detail")
        }
        if (resp.statusCode() !in 200..299) {
            val snippet = resp.body().take(300)
            log(LogLevel.ERROR, "GET $url → HTTP ${resp.statusCode()}: $snippet")
            throw LocalLLMException(formatHttpError(resp.statusCode(), snippet))
        }
        return resp.body()
    }

    private fun post(url: String, json: String): Boolean {
        val resp = postBody(url, json)
        return !resp.contains("\"error\"")
    }

    private fun postBody(url: String, json: String): String {
        val req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .apply { authHeader()?.let { header("Authorization", it) } }
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .timeout(Duration.ofSeconds(60))
            .build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) {
            log(LogLevel.ERROR, "POST $url → HTTP ${resp.statusCode()}: ${resp.body().take(300)}")
        }
        return resp.body()
    }

    /**
     * Bearer for OpenAI-compatible routes that require a key when
     * `data/localllm/config.json` sets `openai.apiKey` (default localllm-local).
     * Uses [WarywayGabSettings.getApiKey] which falls back to localllm-local when blank.
     * Returns null only if somehow still blank (should not happen for Local LLM defaults).
     */
    private fun authHeader(): String? {
        val key = settings.getApiKey(com.waryway.gab.model.ModelProvider.LOCAL_LLM)?.trim()
        return if (key.isNullOrBlank()) null else "Bearer $key"
    }

    /** Clear 401/auth text for status/models helpers (not empty silence). */
    internal fun formatHttpError(status: Int, bodySnippet: String): String {
        val snippet = bodySnippet.trim().replace('\n', ' ')
        if (status == 401 || GabClient.isInvalidApiKeyBody(snippet)) {
            val serverMsg = GabClient.extractOpenAiErrorMessage(snippet) ?: "invalid or missing API key"
            return "HTTP 401: $serverMsg — set Local LLM API key to match config openai.apiKey " +
                "(default localllm-local)"
        }
        return "HTTP $status: ${snippet.take(200)}"
    }

    private fun parsePresets(body: String): List<Preset> {
        val ids = Regex(""""id"\s*:\s*"([^"]+)"""").findAll(body).map { it.groupValues[1] }.toList()
        val names = Regex(""""name"\s*:\s*"([^"]+)"""").findAll(body).map { it.groupValues[1] }.toList()
        val cats = Regex(""""category"\s*:\s*"([^"]+)"""").findAll(body).map { it.groupValues[1] }.toList()
        if (ids.isEmpty()) {
            return listOf(
                Preset("gab-chat", "Plugin chat", "coding"),
                Preset("concise", "Concise", "coding"),
                Preset("goland", "GoLand", "coding"),
                Preset("stack", "Stack", "coding"),
                Preset("corpus", "Corpus writer", "growth"),
                Preset("code-edit", "Code edit", "coding")
            )
        }
        return ids.mapIndexed { i, id ->
            Preset(id, names.getOrElse(i) { id }, cats.getOrElse(i) { "coding" })
        }.distinctBy { it.id }
    }

    private fun extractString(body: String, key: String): String? =
        Regex(""""$key"\s*:\s*"([^"]*)"""").find(body)?.groupValues?.get(1)

    private fun extractInt(body: String, key: String): Int? =
        Regex(""""$key"\s*:\s*(\d+)"""").find(body)?.groupValues?.get(1)?.toIntOrNull()

    private fun extractNestedInt(body: String, obj: String, key: String): Int? {
        val idx = body.indexOf("\"$obj\"")
        if (idx < 0) return null
        val slice = body.substring(idx, (idx + 400).coerceAtMost(body.length))
        return extractInt(slice, key)
    }

    private fun extractStringList(body: String, key: String): List<String> {
        val idx = body.indexOf("\"$key\"")
        if (idx < 0) return emptyList()
        val start = body.indexOf('[', idx)
        val end = body.indexOf(']', start)
        if (start < 0 || end < 0) return emptyList()
        return Regex(""""([^"]+)"""").findAll(body.substring(start, end))
            .map { it.groupValues[1] }
            .toList()
    }

    private fun jsonString(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")

    class LocalLLMException(message: String) : Exception(message)
}