package com.waryway.gab.client

import com.waryway.gab.model.ToolCall
import com.waryway.gab.model.Usage

/**
 * Accumulates OpenAI-compatible SSE chat completion chunks into a final result.
 *
 * Incomplete tool builders (blank id and/or name at stream end) are excluded from
 * [GabClient.ChatCompletionResult.toolCalls] and counted in
 * [GabClient.ChatCompletionResult.incompleteToolCallCount] — never silently discarded
 * without a signal. Fake tool names are not invented.
 *
 * SSE error payloads are recorded on [streamError] so callers can fail the completion
 * instead of treating an empty stream as success.
 */
internal class GabSseAccumulator {
    private val content = StringBuilder()
    private val toolCallsByIndex = linkedMapOf<Int, ToolCallBuilder>()
    private var finishReason: String? = null
    private var usage = Usage.ZERO
    /** Last SSE `error.message` seen; null if no error event was parsed. */
    private var streamError: String? = null

    /**
     * Ingest one SSE JSON payload.
     *
     * Content is merged via [StreamContentMerger] (snapshot-safe). Returns the
     * **visible** new fragment for UI streaming (empty/null if nothing new to show).
     */
    fun acceptChunk(json: String): String? {
        var visible: String? = null
        extractDeltaContent(json)?.let { incoming ->
            val existing = content.toString()
            visible = StreamContentMerger.visibleDelta(existing, incoming)
            val merged = StreamContentMerger.merge(existing, incoming)
            if (merged != existing) {
                content.setLength(0)
                content.append(merged)
            }
        }
        extractDeltaToolCalls(json).forEach { (index, id, name, argsFragment) ->
            val builder = toolCallsByIndex.getOrPut(index) { ToolCallBuilder() }
            if (!id.isNullOrBlank()) builder.id = id
            if (!name.isNullOrBlank()) builder.name = name
            if (!argsFragment.isNullOrBlank()) builder.arguments.append(argsFragment)
        }
        extractFinishReason(json)?.let { finishReason = it }
        extractUsage(json)?.let { usage = it }
        return visible
    }

    /** Records a server-reported SSE error message (last write wins). */
    fun recordStreamError(message: String) {
        if (message.isNotBlank()) {
            streamError = message
        }
    }

    /** Current merged assistant text (may be partial mid-stream). Blank → null. */
    fun peekContent(): String? = content.toString().ifBlank { null }

    /**
     * @param cancelled when true, marks user cancel mid-stream; never invents finishReason="stop".
     */
    fun toResult(cancelled: Boolean = false): GabClient.ChatCompletionResult {
        var incomplete = 0
        val toolCalls = toolCallsByIndex.entries
            .sortedBy { it.key }
            .mapNotNull { (_, builder) ->
                val toolCall = builder.toToolCall()
                if (toolCall == null) {
                    incomplete++
                    null
                } else {
                    toolCall
                }
            }
        return GabClient.ChatCompletionResult(
            content = content.toString().ifEmpty { null },
            toolCalls = toolCalls,
            // null = no non-null string finish_reason observed (distinct from "stop")
            finishReason = finishReason,
            usage = usage,
            cancelled = cancelled,
            streamError = streamError,
            incompleteToolCallCount = incomplete
        )
    }

    private data class ToolCallBuilder(
        var id: String = "",
        var name: String = "",
        val arguments: StringBuilder = StringBuilder()
    ) {
        fun toToolCall(): ToolCall? {
            // Require both id and name — incomplete builders are flagged via incompleteToolCallCount.
            if (id.isBlank() || name.isBlank()) return null
            return ToolCall(id, name, arguments.toString().ifEmpty { "{}" })
        }
    }

    sealed class SseEvent {
        data class Delta(val text: String) : SseEvent()
        data class Error(val message: String) : SseEvent()
        data class Finish(val reason: String) : SseEvent()
    }

    companion object {
        fun parseSseLines(lines: Sequence<String>): GabClient.ChatCompletionResult {
            val accumulator = GabSseAccumulator()
            for (line in lines) {
                processSseLine(line, accumulator, null)
            }
            return accumulator.toResult()
        }

        fun processSseLine(
            line: String,
            accumulator: GabSseAccumulator,
            onEvent: ((SseEvent) -> Unit)? = null
        ) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("data:")) return
            val payload = trimmed.removePrefix("data:").trim()
            if (payload.isEmpty() || payload == "[DONE]") return
            // Prefer recording parseable SSE errors even if later chunk handling fails.
            val sseError = extractSseError(payload)
            if (sseError != null) {
                accumulator.recordStreamError(sseError)
                onEvent?.invoke(SseEvent.Error(sseError))
            }
            runCatching {
                // Merge first so Delta carries only the new visible fragment
                // (snapshot chunks would otherwise re-emit the full cumulative text).
                val visible = accumulator.acceptChunk(payload)
                if (!visible.isNullOrEmpty()) {
                    onEvent?.invoke(SseEvent.Delta(visible))
                }
                extractFinishReason(payload)?.let { onEvent?.invoke(SseEvent.Finish(it)) }
            }
        }

        fun deltaFromChunk(json: String): String? = extractDeltaContent(json)

        private fun extractSseError(json: String): String? {
            val key = "\"error\""
            val idx = json.indexOf(key)
            if (idx < 0) return null
            val messageKey = "\"message\""
            val msgIdx = json.indexOf(messageKey, idx)
            if (msgIdx < 0) return null
            val colon = json.indexOf(':', msgIdx)
            if (colon < 0) return null
            var pos = colon + 1
            while (pos < json.length && json[pos].isWhitespace()) pos++
            if (pos >= json.length || json[pos] != '"') return null
            return readJsonString(json, pos + 1)
        }

        private fun extractDeltaContent(json: String): String? {
            val key = "\"content\""
            val deltaIdx = json.indexOf("\"delta\"")
            if (deltaIdx < 0) return null
            val contentIdx = json.indexOf(key, deltaIdx)
            if (contentIdx < 0) return null
            val colon = json.indexOf(':', contentIdx)
            if (colon < 0) return null
            var pos = colon + 1
            while (pos < json.length && json[pos].isWhitespace()) pos++
            if (pos >= json.length || json[pos] != '"') return null
            return readJsonString(json, pos + 1)
        }

        private fun extractDeltaToolCalls(json: String): List<ToolCallDelta> {
            val key = "\"tool_calls\""
            val deltaIdx = json.indexOf("\"delta\"")
            if (deltaIdx < 0) return emptyList()
            val toolCallsIdx = json.indexOf(key, deltaIdx)
            if (toolCallsIdx < 0) return emptyList()
            val arrayStart = json.indexOf('[', toolCallsIdx)
            if (arrayStart < 0) return emptyList()
            val arrayEnd = findMatchingBracket(json, arrayStart, '[', ']') ?: return emptyList()
            val arrayBody = json.substring(arrayStart + 1, arrayEnd)

            val results = mutableListOf<ToolCallDelta>()
            var searchFrom = 0
            while (searchFrom < arrayBody.length) {
                val objStart = arrayBody.indexOf('{', searchFrom)
                if (objStart < 0) break
                val objEnd = findMatchingBracket(arrayBody, objStart, '{', '}') ?: break
                val obj = arrayBody.substring(objStart, objEnd + 1)
                parseToolCallDelta(obj)?.let { results.add(it) }
                searchFrom = objEnd + 1
            }
            return results
        }

        private fun parseToolCallDelta(objectJson: String): ToolCallDelta? {
            val index = Regex(""""index"\s*:\s*(\d+)""").find(objectJson)?.groupValues?.get(1)?.toIntOrNull()
                ?: return null
            val id = Regex(""""id"\s*:\s*"([^"]+)"""").find(objectJson)?.groupValues?.get(1)
            val name = Regex(""""name"\s*:\s*"([^"]+)"""").find(objectJson)?.groupValues?.get(1)
            val argsKey = objectJson.indexOf("\"arguments\"")
            val args = if (argsKey >= 0) {
                val colon = objectJson.indexOf(':', argsKey)
                if (colon < 0) null else {
                    var pos = colon + 1
                    while (pos < objectJson.length && objectJson[pos].isWhitespace()) pos++
                    if (pos >= objectJson.length) null
                    else if (objectJson[pos] == '"') readJsonString(objectJson, pos + 1)
                    else null
                }
            } else {
                null
            }
            return ToolCallDelta(index, id, name, args)
        }

        /**
         * Captures string finish_reason values (`tool_calls`, `stop`, `length`, etc.).
         * Explicit JSON null / missing does not match — leaves field null (not the string `"null"`).
         */
        private fun extractFinishReason(json: String): String? =
            Regex(""""finish_reason"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)

        private fun extractUsage(json: String): Usage? {
            val prompt = Regex("\"prompt_tokens\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull()
                ?: return null
            val completion = Regex("\"completion_tokens\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val total = Regex("\"total_tokens\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: (prompt + completion)
            val credits = Regex("\"credits_used\"\\s*:\\s*([0-9.]+)").find(json)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            return Usage(prompt, completion, total, credits)
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

        private data class ToolCallDelta(
            val index: Int,
            val id: String?,
            val name: String?,
            val argumentsFragment: String?
        )
    }
}
