package com.waryway.gab.chat

import com.waryway.gab.model.ChatMessage
import com.waryway.gab.skills.InputNormalizer

/**
 * Grok-style context compaction for small local models.
 *
 * Full [messages] stay in the conversation for the UI; this builds a smaller API view:
 * rolling summary of older turns + recent verbatim exchanges.
 */
object ContextCompactor {

    const val SUMMARY_PREFIX = "[Earlier conversation summary]"

    data class Config(
        val contextTokenLimit: Int = 4096,
        val completionReserve: Int = 512,
        val keepRecentTurns: Int = 4,
        val summaryMaxChars: Int = 2800,
        val triggerRatio: Double = 0.72,
        val enabled: Boolean = true
    )

    data class Result(
        val apiMessages: List<ChatMessage>,
        val compactSummary: String?,
        val compactedMessageCount: Int,
        val didCompact: Boolean,
        val stats: String
    )

    fun compact(
        messages: List<ChatMessage>,
        existingSummary: String? = null,
        compactedMessageCount: Int = 0,
        config: Config = Config(),
        force: Boolean = false
    ): Result {
        if (!config.enabled || messages.isEmpty()) {
            return Result(messages, existingSummary, compactedMessageCount, false, "compaction off")
        }

        val budget = (config.contextTokenLimit - config.completionReserve).coerceAtLeast(512)
        val triggerTokens = (budget * config.triggerRatio).toInt()
        val estimated = TokenEstimator.estimateMessages(messages)

        if (!force && estimated <= triggerTokens && existingSummary.isNullOrBlank()) {
            return Result(
                apiMessages = messages,
                compactSummary = existingSummary,
                compactedMessageCount = compactedMessageCount,
                didCompact = false,
                stats = "within budget (~$estimated / $triggerTokens est. tokens)"
            )
        }

        val turns = extractTurns(messages)
        if (turns.isEmpty()) {
            return Result(messages, existingSummary, compactedMessageCount, false, "no turns")
        }

        val keep = config.keepRecentTurns.coerceIn(1, turns.size)
        if (!force && turns.size <= keep && existingSummary.isNullOrBlank()) {
            return Result(messages, existingSummary, compactedMessageCount, false, "only $keep turn(s), nothing to fold")
        }
        val recentTurns = turns.takeLast(keep)
        val foldCount = (turns.size - keep).coerceAtLeast(0)

        val alreadyFolded = compactedMessageCount.coerceAtMost(messages.size)
        val newFoldEnd = messageIndexAfterTurns(messages, turns.dropLast(keep))

        val newlyFolded = if (newFoldEnd > alreadyFolded) {
            messages.subList(alreadyFolded, newFoldEnd)
        } else {
            emptyList()
        }

        val newSummary = mergeSummary(
            existing = existingSummary,
            newlyFolded = newlyFolded,
            maxChars = config.summaryMaxChars
        )

        val apiMessages = buildList {
            if (newSummary.isNotBlank()) {
                add(ChatMessage(ChatMessage.Role.user, "$SUMMARY_PREFIX\n$newSummary"))
            }
            recentTurns.forEach { turn ->
                add(ChatMessage(ChatMessage.Role.user, turn.user))
                if (turn.assistant.isNotBlank()) {
                    add(ChatMessage(ChatMessage.Role.assistant, turn.assistant))
                }
            }
        }

        val apiEstimate = TokenEstimator.estimateMessages(apiMessages)
        val didCompact = foldCount > 0 || !existingSummary.isNullOrBlank()
        return Result(
            apiMessages = apiMessages,
            compactSummary = newSummary.ifBlank { null },
            compactedMessageCount = newFoldEnd.coerceAtLeast(alreadyFolded),
            didCompact = didCompact,
            stats = buildString {
                append("folded $foldCount turn(s), kept $keep recent")
                append(", ~$estimated→~$apiEstimate est. tokens")
                if (newlyFolded.isNotEmpty()) append(", +${newlyFolded.size} msgs into summary")
            }
        )
    }

    /** Merge newly folded messages into the rolling summary. */
    internal fun mergeSummary(
        existing: String?,
        newlyFolded: List<ChatMessage>,
        maxChars: Int
    ): String {
        val chunks = mutableListOf<String>()
        if (!existing.isNullOrBlank()) chunks.add(existing.trim())

        if (newlyFolded.isNotEmpty()) {
            chunks.add(summarizeMessages(newlyFolded))
        }

        return chunks.filter { it.isNotBlank() }
            .joinToString("\n\n")
            .let { trimToChars(it, maxChars) }
    }

    internal fun summarizeMessages(messages: List<ChatMessage>): String {
        val turns = extractTurns(messages)
        if (turns.isEmpty()) {
            return messages.mapNotNull { msg ->
                val role = when (msg.role) {
                    ChatMessage.Role.user -> "User"
                    ChatMessage.Role.assistant -> "Agent"
                    ChatMessage.Role.tool -> "Tool"
                    ChatMessage.Role.system -> "System"
                }
                val body = compressLine(msg.content, maxLen = 160)
                if (body.isBlank()) null else "$role: $body"
            }.joinToString("\n")
        }

        return turns.mapIndexed { index, turn ->
            val q = compressLine(InputNormalizer.normalize(turn.user), maxLen = 140)
            val a = compressLine(turn.assistant, maxLen = 220)
            buildString {
                append("• Turn ${index + 1}: ")
                append(q.ifBlank { "(question)" })
                if (a.isNotBlank()) {
                    append(" → ")
                    append(a)
                }
            }
        }.joinToString("\n")
    }

    internal data class Turn(val user: String, val assistant: String)

    internal fun extractTurns(messages: List<ChatMessage>): List<Turn> {
        val turns = mutableListOf<Turn>()
        var pendingUser: String? = null
        var pendingAssistant = StringBuilder()

        fun flush() {
            val user = pendingUser ?: return
            turns.add(Turn(user, pendingAssistant.toString().trim()))
            pendingUser = null
            pendingAssistant = StringBuilder()
        }

        for (msg in messages) {
            when (msg.role) {
                ChatMessage.Role.system -> continue
                ChatMessage.Role.user -> {
                    if (msg.content.startsWith(SUMMARY_PREFIX)) continue
                    if (pendingUser != null) flush()
                    pendingUser = msg.content
                }
                ChatMessage.Role.assistant -> {
                    if (pendingUser == null) continue
                    if (pendingAssistant.isNotEmpty()) pendingAssistant.append(' ')
                    pendingAssistant.append(msg.content.trim())
                }
                ChatMessage.Role.tool -> {
                    if (pendingUser != null) {
                        val snippet = compressLine(msg.content, maxLen = 100)
                        if (snippet.isNotBlank()) {
                            if (pendingAssistant.isNotEmpty()) pendingAssistant.append(' ')
                            pendingAssistant.append("[tool: $snippet]")
                        }
                    }
                }
            }
        }
        flush()
        return turns
    }

    private fun messageIndexAfterTurns(messages: List<ChatMessage>, turnsToFold: List<Turn>): Int {
        if (turnsToFold.isEmpty()) return 0
        val targetUsers = turnsToFold.map { it.user }.toSet()
        var seen = 0
        for ((index, msg) in messages.withIndex()) {
            if (msg.role == ChatMessage.Role.user && !msg.content.startsWith(SUMMARY_PREFIX)) {
                if (msg.content in targetUsers) {
                    seen++
                    if (seen >= turnsToFold.size) {
                        return (index + 1).coerceAtMost(messages.size)
                    }
                }
            }
        }
        return messages.size
    }

    private fun compressLine(text: String, maxLen: Int): String {
        val oneLine = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .replace(Regex("""\s+"""), " ")
        return if (oneLine.length <= maxLen) oneLine else oneLine.take(maxLen - 1) + "…"
    }

    private fun trimToChars(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        return text.take(maxChars - 20) + "\n… (summary truncated)"
    }
}