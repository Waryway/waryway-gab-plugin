package com.waryway.gab.client

import com.waryway.gab.diagnostics.LogLevel
import com.waryway.gab.diagnostics.SessionLog
import com.waryway.gab.model.ChatMessage
import com.waryway.gab.model.CreditsInfo
import com.waryway.gab.model.ToolCall
import com.waryway.gab.model.ModelProvider
import com.waryway.gab.model.Usage
import com.waryway.gab.tools.ToolDefinition
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Client for Gab AI (OpenAI-compatible).
 *
 * Base URL: https://gab.ai/v1
 * Auth: Bearer <API_KEY>
 */
class GabClient(
    private val apiKey: String,
    val provider: ModelProvider = ModelProvider.GAB_AI,
    baseUrlOverride: String? = null,
    private val localLlmPreset: String? = null,
    private val sessionLog: SessionLog? = null
) {

    private fun log(level: LogLevel, message: String) = sessionLog?.log(level, message)

    private val baseUrl = baseUrlOverride?.trimEnd('/') ?: provider.baseUrl
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    data class ModelInfo(
        val id: String,
        val ownedBy: String? = null,
        val contextWindow: Int? = null,
        val supportsTools: Boolean = false,
        val supportsThinking: Boolean = false
    )

    data class ChatCompletionResult(
        val content: String?,
        val toolCalls: List<ToolCall>,
        val finishReason: String?,
        val usage: Usage
    )

    suspend fun listModels(): List<ModelInfo> = withContext(Dispatchers.IO) {
        log(LogLevel.HTTP, "GET $baseUrl/models")
        val body = get("$baseUrl/models")
        val models = parseModels(body)
        log(LogLevel.HTTP, "models: ${models.size} returned")
        models
    }

    suspend fun getCredits(): CreditsInfo? = withContext(Dispatchers.IO) {
        if (!provider.supportsCredits) return@withContext null
        val body = get("$baseUrl/credits")
        parseCredits(body)
    }

    fun getContextWindowForModel(modelId: String, models: List<ModelInfo>): Int? =
        models.find { it.id == modelId }?.contextWindow

    /**
     * Chat completion with optional tools. Uses SSE streaming internally to avoid gateway timeouts.
     */
    suspend fun chatCompletion(
        model: String,
        messages: List<ChatMessage>,
        toolsJson: String = "",
        includeTools: Boolean = provider != ModelProvider.LOCAL_LLM,
        presetOverride: String? = null,
        onStreamDelta: ((String) -> Unit)? = null,
        cancelled: () -> Boolean = { false }
    ): ChatCompletionResult = withContext(Dispatchers.IO) {
        var lastError: GabApiException? = null
        repeat(MAX_RETRIES) { attempt ->
            try {
                return@withContext chatCompletionStreaming(
                    model, messages, toolsJson, includeTools, presetOverride, onStreamDelta, cancelled
                )
            } catch (e: GabApiException) {
                lastError = e
                val detail = e.body?.take(400)?.replace('\n', ' ')?.trim()
                log(LogLevel.ERROR, "chat attempt ${attempt + 1}/$MAX_RETRIES failed: ${e.message}${detail?.let { " — $it" }.orEmpty()}")
                if (!isRetryable(e) || attempt == MAX_RETRIES - 1) throw e
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
        val preset = presetOverride?.takeIf { it.isNotBlank() } ?: localLlmPreset
        log(LogLevel.HTTP, "POST $url model=$model messages=${messages.size} stream=true${preset?.let { " preset=$it" }.orEmpty()}")

        val req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .timeout(Duration.ofSeconds(210))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream())
        log(LogLevel.HTTP, "response HTTP ${resp.statusCode()}")
        if (resp.statusCode() !in 200..299) {
            val err = resp.body().bufferedReader().readText()
            log(LogLevel.ERROR, "body: ${err.take(500).replace('\n', ' ')}")
            throw GabApiException("Chat failed: HTTP ${resp.statusCode()}", err)
        }

        val accumulator = GabSseAccumulator()
        var sseLines = 0
        var tokenChars = 0
        resp.body().bufferedReader().use { reader ->
            var line = reader.readLine()
            while (line != null) {
                if (cancelled()) {
                    log(LogLevel.SYSTEM, "SSE stream cancelled by user")
                    break
                }
                if (line.trim().startsWith("data:")) sseLines++
                GabSseAccumulator.processSseLine(line, accumulator) { event ->
                    when (event) {
                        is GabSseAccumulator.SseEvent.Delta -> {
                            tokenChars += event.text.length
                            onStreamDelta?.invoke(event.text)
                        }
                        is GabSseAccumulator.SseEvent.Error -> {
                            log(LogLevel.SSE, "server error: ${event.message}")
                        }
                        is GabSseAccumulator.SseEvent.Finish -> {
                            log(LogLevel.SSE, "finish_reason=${event.reason}")
                        }
                    }
                }
                line = reader.readLine()
            }
        }
        val result = accumulator.toResult()
        log(
            LogLevel.SSE,
            "stream done: $sseLines chunks, ~$tokenChars chars, tools=${result.toolCalls.size}, " +
                "tokens=${result.usage.totalTokens}"
        )
        return result
    }

    private fun isRetryable(e: GabApiException): Boolean {
        val message = e.message.orEmpty()
        return RETRYABLE_STATUS_CODES.any { message.contains("HTTP $it") }
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
        val req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .GET()
            .build()

        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) {
            log(LogLevel.ERROR, "GET $url → HTTP ${resp.statusCode()}: ${resp.body().take(300)}")
            throw GabApiException("Request failed: HTTP ${resp.statusCode()}", resp.body())
        }
        return resp.body()
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
        val finishReason = Regex(""""finish_reason"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1)
        val usage = extractUsage(body)
        return ChatCompletionResult(content, toolCalls, finishReason, usage)
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
        val preset = presetOverride?.takeIf { it.isNotBlank() } ?: localLlmPreset
        val localllmPart = preset?.takeIf { it.isNotBlank() }?.let { p ->
            """, "localllm": {"preset":${ToolDefinition.jsonString(p)}} """
        }.orEmpty()
        val maxTokensPart = if (provider == ModelProvider.LOCAL_LLM) {
            """, "max_tokens": 384 """
        } else {
            ""
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

    class GabApiException(message: String, val body: String? = null) : Exception(message)

    companion object {
        private const val MAX_RETRIES = 3
        private val RETRYABLE_STATUS_CODES = setOf(429, 502, 503, 504)
        private val RETRY_BACKOFF_MS = longArrayOf(2_000, 5_000, 10_000)
    }
}
