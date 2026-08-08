package com.waryway.gab.client

import com.waryway.gab.diagnostics.LogLevel
import com.waryway.gab.diagnostics.SessionLog
import com.waryway.gab.model.ChatMessage
import com.waryway.gab.model.CreditsInfo
import com.waryway.gab.model.ToolCall
import com.waryway.gab.model.ModelProvider
import com.waryway.gab.model.Usage
import com.waryway.gab.tools.ToolDefinition
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * OpenAI-compatible chat client shared by Gab AI, xAI Grok API, Grok Build, and Local LLM.
 *
 * Routing is entirely [provider]-driven:
 * - Base URL defaults to [ModelProvider.baseUrl] (override via [baseUrlOverride])
 * - Auth: `Authorization: Bearer <token>` for all backends
 * - [ModelProvider.GROK_BUILD] also sends cli-chat-proxy headers (`X-XAI-Token-Auth`,
 *   `x-grok-client-version`, `x-grok-model-override`) using a `grok login` session token
 * - Credits (`GET /credits`) only when [ModelProvider.supportsCredits] (Gab AI)
 * - Models: `GET {baseUrl}/models` — Gab, api.x.ai, cli-chat-proxy, or local
 * - Chat: `POST {baseUrl}/chat/completions` with standard OpenAI body for cloud providers
 *
 * Local LLM may add `localllm` preset + capped `max_tokens`; Grok/Gab never emit those fields.
 */
class GabClient(
    private val apiKey: String,
    val provider: ModelProvider = ModelProvider.GAB_AI,
    baseUrlOverride: String? = null,
    private val localLlmPreset: String? = null,
    private val sessionLog: SessionLog? = null,
    /**
     * Full SSE request budget (connect + headers + body read). Long reasoning / slow local
     * generation need more than the old 210s hard cap. 0 = provider default.
     */
    streamTimeoutSeconds: Long = 0L
) {

    private fun log(level: LogLevel, message: String) = sessionLog?.log(level, message)

    private val baseUrl = baseUrlOverride?.trimEnd('/') ?: provider.baseUrl
    private val isLocalLlm = provider == ModelProvider.LOCAL_LLM
    private val isGrokBuild = provider == ModelProvider.GROK_BUILD
    /** Effective stream timeout (seconds) after applying provider defaults. */
    val streamTimeoutSeconds: Long = resolveStreamTimeoutSeconds(streamTimeoutSeconds, provider)
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    /** Live SSE body; closed by [abortActiveStream] so Stop unblocks [readLine]. */
    private val activeStreamBody = AtomicReference<InputStream?>(null)

    /**
     * Force-close the in-flight SSE body (if any). Safe from the UI Stop button.
     * Unblocks a blocked [BufferedReader.readLine] so cancel is not stuck until the next chunk.
     */
    fun abortActiveStream() {
        val stream = activeStreamBody.getAndSet(null) ?: return
        try {
            stream.close()
            log(LogLevel.SYSTEM, "SSE stream body closed (abort)")
        } catch (e: Exception) {
            log(LogLevel.SYSTEM, "SSE abort close: ${e.message}")
        }
    }

    data class ModelInfo(
        val id: String,
        val ownedBy: String? = null,
        val contextWindow: Int? = null,
        val supportsTools: Boolean = false,
        val supportsThinking: Boolean = false
    )

    /**
     * @param finishReason null means no non-null string finish_reason was observed (not the same as `"stop"`).
     * @param cancelled true when the caller aborted mid-stream; do not treat as a clean model stop.
     * @param streamError SSE error message if the stream reported failure without throwing (prefer throw on live streams).
     * @param incompleteToolCallCount builders left at end that could not become full [ToolCall]s (blank id and/or name).
     */
    data class ChatCompletionResult(
        val content: String?,
        val toolCalls: List<ToolCall>,
        val finishReason: String?,
        val usage: Usage,
        val cancelled: Boolean = false,
        val streamError: String? = null,
        val incompleteToolCallCount: Int = 0
    )

    /**
     * Lists models from `{baseUrl}/models` (OpenAI-compatible).
     * - [ModelProvider.GROK] → `https://api.x.ai/v1/models` with API key
     * - [ModelProvider.GROK_BUILD] → `https://cli-chat-proxy.grok.com/v1/models` with session token
     */
    suspend fun listModels(): List<ModelInfo> = withContext(Dispatchers.IO) {
        log(LogLevel.HTTP, "GET $baseUrl/models provider=${provider.name}")
        val body = get("$baseUrl/models")
        val models = parseModels(body)
        log(LogLevel.HTTP, "models: ${models.size} returned")
        models
    }

    /**
     * Gab credits endpoint only. Grok and Local LLM skip the network call
     * ([ModelProvider.supportsCredits] is false) and return null for UI callers.
     */
    suspend fun getCredits(): CreditsInfo? = withContext(Dispatchers.IO) {
        if (!provider.supportsCredits) return@withContext null
        val body = get("$baseUrl/credits")
        parseCredits(body)
    }

    fun getContextWindowForModel(modelId: String, models: List<ModelInfo>): Int? =
        models.find { it.id == modelId }?.contextWindow

    /**
     * Chat completion with optional tools. Uses SSE streaming internally to avoid gateway timeouts.
     * Tools + stream stay enabled for Grok and Gab (disabled by default only for Local LLM).
     */
    suspend fun chatCompletion(
        model: String,
        messages: List<ChatMessage>,
        toolsJson: String = "",
        includeTools: Boolean = !isLocalLlm,
        presetOverride: String? = null,
        onStreamDelta: ((String) -> Unit)? = null,
        /**
         * Called before a retry attempt so the UI can clear the live stream body
         * (partial tokens from a failed attempt should not concat with the retry).
         */
        onStreamReset: (() -> Unit)? = null,
        cancelled: () -> Boolean = { false }
    ): ChatCompletionResult = withContext(Dispatchers.IO) {
        if (cancelled()) {
            return@withContext ChatCompletionResult(
                content = null,
                toolCalls = emptyList(),
                finishReason = null,
                usage = Usage.ZERO,
                cancelled = true
            )
        }
        var lastError: GabApiException? = null
        repeat(MAX_RETRIES) { attempt ->
            if (cancelled()) {
                return@withContext ChatCompletionResult(
                    content = null,
                    toolCalls = emptyList(),
                    finishReason = null,
                    usage = Usage.ZERO,
                    cancelled = true
                )
            }
            if (attempt > 0) {
                // Clear prior attempt's partial stream before re-POSTing.
                try {
                    onStreamReset?.invoke()
                } catch (_: Exception) {
                }
            }
            try {
                return@withContext chatCompletionStreaming(
                    model, messages, toolsJson, includeTools, presetOverride, onStreamDelta, cancelled
                )
            } catch (e: GabApiException) {
                lastError = e
                val detail = e.body?.take(400)?.replace('\n', ' ')?.trim()
                val partialHint = e.partialContent?.let { " partialChars=${it.length}" }.orEmpty()
                log(
                    LogLevel.ERROR,
                    "chat attempt ${attempt + 1}/$MAX_RETRIES failed: ${e.message}" +
                        "${detail?.let { " — $it" }.orEmpty()}$partialHint"
                )
                if (cancelled() || !isRetryable(e) || attempt == MAX_RETRIES - 1) throw e
                val backoff = RETRY_BACKOFF_MS[attempt.coerceAtMost(RETRY_BACKOFF_MS.lastIndex)]
                log(LogLevel.SYSTEM, "retrying in ${backoff}ms…")
                delay(backoff)
            } catch (e: Exception) {
                // Network/timeout outside GabApiException (HttpTimeoutException, IOException, …).
                if (cancelled() || Thread.currentThread().isInterrupted) {
                    return@withContext ChatCompletionResult(
                        content = null,
                        toolCalls = emptyList(),
                        finishReason = null,
                        usage = Usage.ZERO,
                        cancelled = true
                    )
                }
                val wrapped = wrapTransportException(e)
                lastError = wrapped
                log(
                    LogLevel.ERROR,
                    "chat attempt ${attempt + 1}/$MAX_RETRIES failed: ${wrapped.message}"
                )
                if (!isRetryable(wrapped) || attempt == MAX_RETRIES - 1) throw wrapped
                val backoff = RETRY_BACKOFF_MS[attempt.coerceAtMost(RETRY_BACKOFF_MS.lastIndex)]
                log(LogLevel.SYSTEM, "retrying in ${backoff}ms…")
                delay(backoff)
            }
        }
        throw lastError ?: GabApiException("Chat failed after $MAX_RETRIES attempts")
    }

    private fun chatCompletionStreaming(
        model: String,
        messages: List<ChatMessage>,
        toolsJson: String,
        includeTools: Boolean,
        presetOverride: String?,
        onStreamDelta: ((String) -> Unit)?,
        cancelled: () -> Boolean
    ): ChatCompletionResult {
        val body = buildJsonChatRequest(
            model = model,
            messages = messages,
            stream = true,
            toolsJson = toolsJson,
            includeTools = includeTools,
            presetOverride = presetOverride
        )

        val url = "$baseUrl/chat/completions"
        val preset = if (isLocalLlm) {
            presetOverride?.takeIf { it.isNotBlank() } ?: localLlmPreset
        } else {
            null
        }
        log(
            LogLevel.HTTP,
            "POST $url provider=${provider.name} model=$model messages=${messages.size} " +
                "stream=true tools=${includeTools && toolsJson.isNotBlank()}" +
                (preset?.let { " preset=$it" }.orEmpty())
        )

        // OpenAI-compatible headers; Grok Build adds cli-chat-proxy session headers.
        // Timeout covers the full stream (headers + body). Defaults are generous for
        // reasoning models / slow local generation; see [streamTimeoutSeconds].
        val req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .timeout(Duration.ofSeconds(streamTimeoutSeconds))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .apply { applyProviderAuth(this, modelForOverride = model) }
            .build()

        log(LogLevel.SYSTEM, "stream timeout=${streamTimeoutSeconds}s provider=${provider.name}")
        val resp = try {
            client.send(req, HttpResponse.BodyHandlers.ofInputStream())
        } catch (e: Exception) {
            throw wrapTransportException(e)
        }
        log(LogLevel.HTTP, "response HTTP ${resp.statusCode()}")
        if (resp.statusCode() !in 200..299) {
            val err = resp.body().bufferedReader().readText()
            log(LogLevel.ERROR, "body: ${err.take(500).replace('\n', ' ')}")
            throw httpStatusException("Chat failed", resp.statusCode(), err)
        }

        val bodyStream = resp.body()
        activeStreamBody.set(bodyStream)
        val accumulator = GabSseAccumulator()
        var sseLines = 0
        var tokenChars = 0
        var wasCancelled = false
        try {
            bodyStream.bufferedReader().use { reader ->
                try {
                    var line = reader.readLine()
                    while (line != null) {
                        if (cancelled()) {
                            wasCancelled = true
                            log(LogLevel.SYSTEM, "SSE stream cancelled by user")
                            break
                        }
                        if (line.trim().startsWith("data:")) sseLines++
                        GabSseAccumulator.processSseLine(line, accumulator) { event ->
                            when (event) {
                                is GabSseAccumulator.SseEvent.Delta -> {
                                    tokenChars += event.text.length
                                    if (!cancelled()) onStreamDelta?.invoke(event.text)
                                }
                                is GabSseAccumulator.SseEvent.Error -> {
                                    // Fail the completion — never return empty content + null finish as success.
                                    log(LogLevel.ERROR, "SSE server error: ${event.message}")
                                    throw GabApiException("SSE stream error: ${event.message}")
                                }
                                is GabSseAccumulator.SseEvent.Finish -> {
                                    log(LogLevel.SSE, "finish_reason=${event.reason}")
                                }
                            }
                        }
                        line = reader.readLine()
                    }
                } catch (e: Exception) {
                    // abortActiveStream() clears activeStreamBody then closes → readLine throws.
                    // Real network errors leave the ref set and are rethrown (wrapped).
                    val abortedByUs = activeStreamBody.get() == null
                    val partial = accumulator.peekContent()
                    when {
                        e is GabApiException -> {
                            // Preserve any tokens already streamed when rethrowing.
                            if (e.partialContent.isNullOrBlank() && !partial.isNullOrBlank()) {
                                throw e.withPartialContent(partial)
                            }
                            throw e
                        }
                        cancelled() || Thread.currentThread().isInterrupted ||
                            (e is java.io.IOException && abortedByUs) -> {
                            wasCancelled = true
                            log(
                                LogLevel.SYSTEM,
                                "SSE stream aborted: ${e.message ?: e::class.java.simpleName}"
                            )
                        }
                        else -> throw wrapTransportException(e, partialContent = partial)
                    }
                }
            }
        } finally {
            activeStreamBody.compareAndSet(bodyStream, null)
            try {
                bodyStream.close()
            } catch (_: Exception) {
            }
        }
        // cancelled=true; do not invent finishReason="stop" — leave whatever the accumulator saw (usually null).
        val result = accumulator.toResult(cancelled = wasCancelled || cancelled())
        log(
            LogLevel.SSE,
            "stream done: $sseLines chunks, ~$tokenChars chars, tools=${result.toolCalls.size}, " +
                "incompleteTools=${result.incompleteToolCallCount}, finish_reason=${result.finishReason}, " +
                "cancelled=${result.cancelled}, streamError=${result.streamError}, " +
                "tokens=${result.usage.totalTokens}"
        )
        if (result.incompleteToolCallCount > 0) {
            log(
                LogLevel.SYSTEM,
                "SSE incomplete tool builders: ${result.incompleteToolCallCount} " +
                    "(excluded from toolCalls — agent loop should not treat as clean tool_calls)"
            )
        }
        return result
    }

    private fun isRetryable(e: GabApiException): Boolean {
        val message = e.message.orEmpty()
        if (RETRYABLE_STATUS_CODES.any { message.contains("HTTP $it") }) return true
        if (e.kind == GabApiException.Kind.TIMEOUT ||
            message.lowercase().contains("timed out")
        ) {
            // Only retry empty timeouts — partial streams should surface, not re-POST.
            return shouldRetryTimeout(e.partialContent)
        }
        val m = message.lowercase()
        return m.contains("temporarily unavailable") ||
            m.contains("connection reset")
    }

    /**
     * Map raw transport failures to [GabApiException] with stable, UX-friendly messages.
     * Stream/request budget → kind TIMEOUT; connect/DNS/refused → TRANSPORT (unreachable UX).
     * [partialContent] preserves any tokens already streamed before the failure.
     */
    private fun wrapTransportException(
        e: Exception,
        partialContent: String? = null
    ): GabApiException {
        if (e is GabApiException) {
            return if (e.partialContent.isNullOrBlank() && !partialContent.isNullOrBlank()) {
                e.withPartialContent(partialContent)
            } else {
                e
            }
        }
        val msg = e.message?.trim().orEmpty()
        val cls = e.javaClass.simpleName
        val kind = classifyTransportKind(cls, msg, e)
        val partial = partialContent?.takeIf { it.isNotBlank() }
        if (kind == GabApiException.Kind.TIMEOUT) {
            return GabApiException(
                message = "Chat timed out after ${streamTimeoutSeconds}s " +
                    "(provider=${provider.displayName}). " +
                    "Long reasoning or slow local generation can exceed the stream budget — " +
                    "raise Settings → Chat stream timeout or retry. " +
                    "Partial tokens are kept when available — send “continue” or retry Send.",
                body = msg.ifBlank { cls },
                kind = GabApiException.Kind.TIMEOUT,
                partialContent = partial
            )
        }
        return GabApiException(
            message = "Chat transport failed: ${msg.ifBlank { cls }}",
            body = msg.ifBlank { null },
            kind = GabApiException.Kind.TRANSPORT,
            partialContent = partial
        )
    }

    /** Backward-compatible simple chat (no tools). */
    suspend fun chat(
        model: String,
        messages: List<ChatMessage>,
        tools: List<Any> = emptyList()
    ): Pair<String, Usage> {
        val toolsJson = if (tools.isEmpty()) "" else tools.joinToString(",") { it.toString() }
        val result = chatCompletion(model, messages, toolsJson)
        return (result.content ?: "(no content)") to result.usage
    }

    private suspend fun get(url: String): String {
        // Short request timeout: listModels/getCredits must not hang tool-window open when offline.
        // Connect uses client-level connectTimeout (30s); response budget is tighter for GETs.
        val req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Accept", "application/json")
            .GET()
            .timeout(Duration.ofSeconds(if (isLocalLlm) 8 else 20))
            .apply { applyProviderAuth(this, modelForOverride = null) }
            .build()

        val resp = try {
            client.send(req, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            // Surface connect/timeout class names when message is blank (common for ConnectException).
            val detail = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            throw GabApiException(
                message = "Request failed: $detail",
                body = detail,
                kind = classifyTransportOrTimeout(e)
            )
        }
        if (resp.statusCode() !in 200..299) {
            log(LogLevel.ERROR, "GET $url → HTTP ${resp.statusCode()}: ${resp.body().take(300)}")
            throw httpStatusException("Request failed", resp.statusCode(), resp.body())
        }
        return resp.body()
    }

    private fun classifyTransportOrTimeout(error: Throwable): GabApiException.Kind =
        classifyTransportKind(
            classSimpleName = error.javaClass.simpleName,
            message = error.message,
            throwable = error
        )

    /**
     * Applies Bearer auth and, for [ModelProvider.GROK_BUILD], the cli-chat-proxy headers
     * documented by Grok Build (`X-XAI-Token-Auth`, client version/surface, optional model override).
     * Header name→value contract is locked by [GrokBuildAuth.requestHeaders].
     *
     * Local LLM and cloud OpenAI paths always send `Authorization: Bearer …`.
     * A blank [apiKey] still sets the header (as `Bearer `) so the server can return a
     * clear 401 rather than behaving as "no key / open" when config requires a key.
     */
    private fun applyProviderAuth(builder: HttpRequest.Builder, modelForOverride: String?) {
        if (isGrokBuild) {
            for ((name, value) in GrokBuildAuth.requestHeaders(apiKey, modelForOverride)) {
                builder.header(name, value)
            }
            return
        }
        builder.header("Authorization", "Bearer $apiKey")
    }

    /**
     * Map non-2xx HTTP status (+ body) to [GabApiException].
     * Auth failures (401 / invalid_api_key) get [GabApiException.Kind.AUTH] and a
     * message that UI formatters can surface without swallowing the reply bubble.
     */
    private fun httpStatusException(prefix: String, status: Int, body: String?): GabApiException {
        val snippet = body?.trim().orEmpty()
        if (status == 401 || isInvalidApiKeyBody(snippet)) {
            val serverMsg = extractOpenAiErrorMessage(snippet)
            val detail = serverMsg?.takeIf { it.isNotBlank() } ?: "invalid or missing API key"
            return GabApiException(
                message = "$prefix: HTTP 401 — $detail " +
                    "(check Settings → Local LLM API key matches data/localllm/config.json openai.apiKey; " +
                    "default localllm-local)",
                body = body,
                kind = GabApiException.Kind.AUTH
            )
        }
        return GabApiException("$prefix: HTTP $status", body)
    }

    private fun parseModels(body: String): List<ModelInfo> {
        val ids = Regex(""""id"\s*:\s*"([^"]+)"""").findAll(body).map { it.groupValues[1] }.toList()
        return ids.map { id -> parseModelBlock(body, id) }.distinctBy { it.id }
    }

    private fun parseModelBlock(body: String, id: String): ModelInfo {
        val idIndex = body.indexOf("\"id\":\"$id\"")
            .takeIf { it >= 0 }
            ?: body.indexOf("\"id\": \"$id\"")
        val block = if (idIndex >= 0) {
            body.substring(idIndex.coerceAtLeast(0), (idIndex + 800).coerceAtMost(body.length))
        } else {
            body
        }

        val contextWindow = Regex(""""(?:context_window|max_context_tokens|context_length)"\s*:\s*(\d+)""")
            .find(block)?.groupValues?.get(1)?.toIntOrNull()
        val supportsTools = Regex(""""function_calling"\s*:\s*true""").containsMatchIn(block)
            || Regex(""""supports_tools"\s*:\s*true""").containsMatchIn(block)
        val supportsThinking = Regex(""""thinking"\s*:\s*true""").containsMatchIn(block)
        val ownedBy = Regex(""""owned_by"\s*:\s*"([^"]+)"""").find(block)?.groupValues?.get(1)

        return ModelInfo(
            id = id,
            ownedBy = ownedBy,
            contextWindow = contextWindow,
            supportsTools = supportsTools,
            supportsThinking = supportsThinking
        )
    }

    private fun parseCredits(body: String): CreditsInfo {
        fun num(vararg keys: String): Double? {
            for (key in keys) {
                Regex(""""$key"\s*:\s*([0-9.]+)""").find(body)?.groupValues?.get(1)?.toDoubleOrNull()?.let { return it }
            }
            return null
        }

        val balance = num("balance", "remaining", "available", "credits_remaining") ?: 0.0
        return CreditsInfo(
            balance = balance,
            monthlyAllotment = num("monthly_allotment", "monthly_credits", "allotment", "limit"),
            purchased = num("purchased", "purchased_credits"),
            usedThisPeriod = num("used", "credits_used", "used_this_period")
        )
    }

    private fun parseChatCompletion(body: String): ChatCompletionResult {
        val messageBlock = extractMessageBlock(body)
        val content = extractMessageContent(messageBlock)
        val toolCalls = extractToolCalls(messageBlock)
        // Quoted string only — JSON null / missing leave finishReason null (not the string "null").
        val finishReason = Regex(""""finish_reason"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1)
        val usage = extractUsage(body)
        return ChatCompletionResult(
            content = content,
            toolCalls = toolCalls,
            finishReason = finishReason,
            usage = usage
        )
    }

    private fun extractMessageBlock(body: String): String {
        val start = body.indexOf("\"message\"")
        if (start < 0) return body
        val braceStart = body.indexOf('{', start)
        if (braceStart < 0) return body
        var depth = 0
        for (i in braceStart until body.length) {
            when (body[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return body.substring(braceStart, i + 1)
                }
            }
        }
        return body.substring(braceStart)
    }

    private fun extractMessageContent(messageBlock: String): String? {
        val key = "\"content\""
        val idx = messageBlock.indexOf(key)
        if (idx < 0) return null
        val colon = messageBlock.indexOf(':', idx)
        if (colon < 0) return null
        var pos = colon + 1
        while (pos < messageBlock.length && messageBlock[pos].isWhitespace()) pos++
        if (pos >= messageBlock.length) return null
        if (messageBlock[pos] == 'n') return null // null
        if (messageBlock[pos] != '"') return null
        return readJsonString(messageBlock, pos + 1)
    }

    private fun extractToolCalls(messageBlock: String): List<ToolCall> {
        val key = "\"tool_calls\""
        val idx = messageBlock.indexOf(key)
        if (idx < 0) return emptyList()

        val arrayStart = messageBlock.indexOf('[', idx)
        if (arrayStart < 0) return emptyList()

        val arrayEnd = findMatchingBracket(messageBlock, arrayStart, '[', ']') ?: return emptyList()
        val arrayBody = messageBlock.substring(arrayStart + 1, arrayEnd)

        val results = mutableListOf<ToolCall>()
        var searchFrom = 0
        while (searchFrom < arrayBody.length) {
            val objStart = arrayBody.indexOf('{', searchFrom)
            if (objStart < 0) break
            val objEnd = findMatchingBracket(arrayBody, objStart, '{', '}') ?: break
            parseToolCallObject(arrayBody.substring(objStart, objEnd + 1))?.let { results.add(it) }
            searchFrom = objEnd + 1
        }
        return results
    }

    internal fun parseToolCallObject(objectJson: String): ToolCall? {
        val id = Regex(""""id"\s*:\s*"([^"]+)"""").find(objectJson)?.groupValues?.get(1) ?: return null
        val name = Regex(""""name"\s*:\s*"([^"]+)"""").find(objectJson)?.groupValues?.get(1) ?: return null
        val argsKey = objectJson.indexOf("\"arguments\"")
        val args = if (argsKey >= 0) {
            val colon = objectJson.indexOf(':', argsKey)
            if (colon < 0) return ToolCall(id, name, "{}")
            var pos = colon + 1
            while (pos < objectJson.length && objectJson[pos].isWhitespace()) pos++
            if (pos >= objectJson.length) return ToolCall(id, name, "{}")
            if (objectJson[pos] == '"') {
                readJsonString(objectJson, pos + 1) ?: "{}"
            } else {
                val end = findJsonValueEnd(objectJson, pos)
                objectJson.substring(pos, end).trim()
            }
        } else {
            "{}"
        }
        return ToolCall(id, name, args)
    }

    internal fun findMatchingBracket(source: String, openIndex: Int, open: Char, close: Char): Int? {
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

    private fun findJsonValueEnd(source: String, start: Int): Int {
        var inString = false
        var escaped = false
        var depth = 0
        for (i in start until source.length) {
            val c = source[i]
            if (inString) {
                if (escaped) escaped = false else when (c) {
                    '\\' -> escaped = true
                    '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"', '\'' -> inString = true
                '{', '[' -> depth++
                '}', ']' -> if (depth > 0) depth--
                ',', '}' -> if (depth == 0) return i
            }
        }
        return source.length
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

    /**
     * Builds the chat/completions JSON body.
     *
     * Cloud providers ([ModelProvider.GROK], [ModelProvider.GROK_BUILD], [ModelProvider.GAB_AI])
     * emit a strict OpenAI-compatible body: `model`, `messages`, `stream`, and optionally
     * `tools` / `tool_choice`. Local-only fields (`localllm` preset, forced small `max_tokens`)
     * are emitted **only** when [provider] is [ModelProvider.LOCAL_LLM].
     */
    internal fun buildJsonChatRequest(
        model: String,
        messages: List<ChatMessage>,
        stream: Boolean,
        toolsJson: String,
        includeTools: Boolean = true,
        presetOverride: String? = null
    ): String {
        val msgs = messages.joinToString(",") { messageToJson(it) }
        val toolsPart = if (!includeTools || toolsJson.isBlank()) {
            ""
        } else {
            """, "tools": [$toolsJson], "tool_choice": "auto" """
        }
        // Local-only extensions — never attach to Gab or Grok (xAI) payloads.
        val localllmPart: String
        val maxTokensPart: String
        if (isLocalLlm) {
            val preset = presetOverride?.takeIf { it.isNotBlank() } ?: localLlmPreset
            localllmPart = preset?.takeIf { it.isNotBlank() }?.let { p ->
                """, "localllm": {"preset":${ToolDefinition.jsonString(p)}} """
            }.orEmpty()
            maxTokensPart = """, "max_tokens": 384 """
        } else {
            localllmPart = ""
            maxTokensPart = ""
        }

        return """
            {
              "model": "$model",
              "stream": $stream,
              "messages": [$msgs]
              $toolsPart$localllmPart$maxTokensPart
            }
        """.trimIndent()
    }

    internal fun messageToJson(m: ChatMessage): String = buildString {
        append("""{"role":"${m.role.name}"""")
        when (m.role) {
            ChatMessage.Role.tool -> {
                append(""","tool_call_id":${ToolDefinition.jsonString(m.toolCallId.orEmpty())}""")
                append(""","content":${ToolDefinition.jsonString(m.content)}""")
            }
            ChatMessage.Role.assistant -> {
                if (m.content.isNotEmpty()) {
                    append(""","content":${ToolDefinition.jsonString(m.content)}""")
                }
                if (m.toolCalls.isNotEmpty()) {
                    append(""","tool_calls":[""")
                    m.toolCalls.forEachIndexed { i, tc ->
                        if (i > 0) append(',')
                        append(
                            """{"id":${ToolDefinition.jsonString(tc.id)},"type":"function","function":{""" +
                                """"name":${ToolDefinition.jsonString(tc.name)},""" +
                                """"arguments":${ToolDefinition.jsonString(tc.arguments)}}}"""
                        )
                    }
                    append(']')
                }
            }
            else -> {
                append(""","content":${ToolDefinition.jsonString(m.content)}""")
            }
        }
        append('}')
    }

    private fun extractUsage(body: String): Usage {
        val prompt = Regex("\"prompt_tokens\"\\s*:\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val completion = Regex("\"completion_tokens\"\\s*:\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val total = Regex("\"total_tokens\"\\s*:\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull() ?: (prompt + completion)
        val credits = Regex("\"credits_used\"\\s*:\\s*([0-9.]+)").find(body)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        return Usage(prompt, completion, total, credits)
    }

    class GabApiException(
        message: String,
        val body: String? = null,
        val kind: Kind = Kind.API,
        /**
         * Tokens already streamed before the failure (timeout / transport).
         * Surfaced to the user so a mid-stream timeout does not wipe the reply.
         */
        val partialContent: String? = null
    ) : Exception(message) {
        enum class Kind {
            /** HTTP 4xx/5xx or SSE server error. */
            API,
            /** Missing/wrong Bearer or server invalid_api_key (401). Not retryable. */
            AUTH,
            /** Client request/stream budget exhausted. */
            TIMEOUT,
            /** Connect/IO/other transport failure. */
            TRANSPORT
        }

        fun withPartialContent(partial: String?): GabApiException {
            val p = partial?.takeIf { it.isNotBlank() } ?: return this
            if (partialContent == p) return this
            return GabApiException(message = message.orEmpty(), body = body, kind = kind, partialContent = p)
        }
    }

    companion object {
        private const val MAX_RETRIES = 3
        private val RETRYABLE_STATUS_CODES = setOf(429, 502, 503, 504)

        /** True when body looks like OpenAI-style invalid_api_key / unauthorized. */
        fun isInvalidApiKeyBody(body: String?): Boolean {
            val b = body?.lowercase().orEmpty()
            if (b.isBlank()) return false
            return b.contains("invalid_api_key") ||
                b.contains("\"invalid api key\"") ||
                (b.contains("invalid") && b.contains("api key")) ||
                b.contains("unauthorized")
        }

        /** Extract `error.message` from OpenAI error JSON when present. */
        fun extractOpenAiErrorMessage(body: String?): String? {
            if (body.isNullOrBlank()) return null
            Regex(""""message"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1)?.let { return it }
            Regex(""""error"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1)?.let { return it }
            return null
        }
        private val RETRY_BACKOFF_MS = longArrayOf(2_000, 5_000, 10_000)

        /** Cloud (Gab / Grok / Grok Build) default stream budget — was hard-coded 210s. */
        const val DEFAULT_CLOUD_STREAM_TIMEOUT_SECONDS: Long = 15L * 60L

        /** Local LLM chat default — pure-Go generate often exceeds a few minutes. */
        const val DEFAULT_LOCAL_STREAM_TIMEOUT_SECONDS: Long = 30L * 60L

        /**
         * Minimum useful partial length to skip timeout retries (avoids re-POSTing
         * after the model already spent tokens).
         */
        const val USEFUL_PARTIAL_MIN_CHARS: Int = 24

        /** Clamp for constructor / settings wiring. */
        fun resolveStreamTimeoutSeconds(requested: Long, provider: ModelProvider): Long {
            if (requested > 0) return requested.coerceIn(60L, 7_200L)
            return if (provider == ModelProvider.LOCAL_LLM) {
                DEFAULT_LOCAL_STREAM_TIMEOUT_SECONDS
            } else {
                DEFAULT_CLOUD_STREAM_TIMEOUT_SECONDS
            }
        }

        /**
         * Timeout retries only when no useful partial was streamed.
         * Pure / unit-testable policy for empty hang vs mid-stream stall.
         */
        fun shouldRetryTimeout(partialContent: String?): Boolean {
            val p = partialContent?.trim().orEmpty()
            return p.length < USEFUL_PARTIAL_MIN_CHARS
        }

        /**
         * Overall multi-turn agent session budget from a single-stream timeout.
         * Covers tool rounds without letting a loop run for hours unbounded.
         */
        fun resolveSessionTimeoutMs(streamTimeoutSeconds: Long): Long {
            val streamMs = streamTimeoutSeconds.coerceIn(60L, 7_200L) * 1_000L
            // 4× one stream (multi-tool), floor 30m, cap 4h.
            return (streamMs * 4L).coerceIn(30L * 60L * 1_000L, 4L * 60L * 60L * 1_000L)
        }

        /**
         * True for cold-server / connect-class failures that must surface as
         * unreachable TRANSPORT — never stream TIMEOUT (even if the message says "timed out").
         */
        fun isConnectClassTransport(classSimpleName: String?, message: String?): Boolean {
            val c = classSimpleName?.lowercase().orEmpty()
            val m = message?.lowercase().orEmpty()
            if (c.contains("connecttimeoutexception") || c.contains("connectexception")) return true
            if (c.contains("unknownhost")) return true
            if (m.contains("http connect timed out") || m.contains("connect timed out")) return true
            if (m.contains("connection timed out") && !m.contains("read timed out")) return true
            if (m.contains("connection refused") || m.contains("failed to connect")) return true
            if (m.contains("no route to host") || m.contains("network is unreachable")) return true
            if (m.contains("unknown host") || m.contains("unknownhost")) return true
            return false
        }

        /**
         * Classify raw transport throwables for UX routing.
         * Connect/DNS/refused → [GabApiException.Kind.TRANSPORT];
         * request/stream budget → [GabApiException.Kind.TIMEOUT].
         * Pure / unit-testable (does not invent AUTH — HTTP status path owns that).
         */
        fun classifyTransportKind(
            classSimpleName: String?,
            message: String?,
            throwable: Throwable? = null
        ): GabApiException.Kind {
            if (throwable is GabApiException) return throwable.kind
            // java.net.http.HttpConnectTimeoutException extends HttpTimeoutException — check first.
            if (throwable != null) {
                val name = throwable.javaClass.name
                if (name.contains("HttpConnectTimeoutException") ||
                    name.contains("ConnectException")
                ) {
                    return GabApiException.Kind.TRANSPORT
                }
            }
            if (isConnectClassTransport(classSimpleName, message)) {
                return GabApiException.Kind.TRANSPORT
            }
            val cls = classSimpleName.orEmpty()
            val msg = message?.trim().orEmpty()
            val isTimeout = throwable is java.net.http.HttpTimeoutException ||
                cls.contains("Timeout", ignoreCase = true) ||
                msg.contains("timed out", ignoreCase = true) ||
                msg.contains("timeout", ignoreCase = true)
            return if (isTimeout) GabApiException.Kind.TIMEOUT else GabApiException.Kind.TRANSPORT
        }
    }
}
