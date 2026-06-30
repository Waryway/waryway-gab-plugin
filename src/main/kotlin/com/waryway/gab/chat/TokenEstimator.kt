package com.waryway.gab.chat

import com.waryway.gab.model.ChatMessage
import com.waryway.gab.model.ContextAttachment

/**
 * Fast heuristic token estimator (~4 characters per token for English/code mix).
 * Used for context budget display before Gab returns actual usage.
 */
object TokenEstimator {

    private const val CHARS_PER_TOKEN = 4.0

    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        return (text.length / CHARS_PER_TOKEN).toInt().coerceAtLeast(1)
    }

    fun estimateMessages(messages: List<ChatMessage>): Int =
        messages.sumOf { estimateTokens(it.content) + it.toolCalls.sumOf { tc -> estimateTokens(tc.arguments) } }

    fun estimateAttachments(attachments: List<ContextAttachment>): Int =
        attachments.sumOf { att ->
            estimateTokens(att.path.orEmpty()) + estimateTokens(att.content.orEmpty()) + estimateTokens(att.displayName)
        }

    fun formatTokenCount(tokens: Int): String = when {
        tokens >= 1_000_000 -> String.format("%.1fM", tokens / 1_000_000.0)
        tokens >= 1_000 -> String.format("%.1fk", tokens / 1_000.0)
        else -> tokens.toString()
    }

    fun contextPercent(used: Int, limit: Int?): Int {
        if (limit == null || limit <= 0) return 0
        return ((used.toDouble() / limit) * 100).toInt().coerceIn(0, 100)
    }
}