package com.waryway.gab.model

/**
 * Core domain models for the Waryway Gab plugin.
 * These will be expanded as we implement agentic features.
 */

/** A single chat message in the conversation. */
data class ChatMessage(
    val role: Role,
    val content: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null
) {
    enum class Role { system, user, assistant, tool }
}

/** Tool call emitted by the model (OpenAI shape). */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String // JSON string
)

/** Usage returned from Gab AI responses. */
data class Usage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
    val creditsUsed: Double = 0.0
) {
    fun plus(other: Usage): Usage = Usage(
        promptTokens = promptTokens + other.promptTokens,
        completionTokens = completionTokens + other.completionTokens,
        totalTokens = totalTokens + other.totalTokens,
        creditsUsed = creditsUsed + other.creditsUsed
    )

    companion object {
        val ZERO = Usage()
    }
}

/** Account credit balance from GET /v1/credits. */
data class CreditsInfo(
    val balance: Double = 0.0,
    val monthlyAllotment: Double? = null,
    val purchased: Double? = null,
    val usedThisPeriod: Double? = null
) {
    fun displayBalance(): String = when {
        monthlyAllotment != null -> "${balance.toInt()} / ${monthlyAllotment.toInt()}"
        else -> balance.toInt().toString()
    }
}

/** Context attachment (file, selection, symbol, etc.). */
data class ContextAttachment(
    val type: Type,
    val path: String? = null,
    val content: String? = null,
    val displayName: String
) {
    enum class Type { FILE, SELECTION, SYMBOL, DIRECTORY_SUMMARY, ERROR }
}

/** Simple skill definition (used by dropdown + form). */
data class Skill(
    val id: String,
    val name: String,
    val description: String,
    val category: String = "general",
    val parameters: List<SkillParam> = emptyList()
)

data class SkillParam(
    val name: String,
    val type: ParamType,
    val label: String = name,
    val required: Boolean = true,
    val defaultValue: String? = null
) {
    enum class ParamType { STRING, TEXT, BOOLEAN, SELECT, FILE_PATH }
}

/** Conversation with history and usage tracking. */
data class Conversation(
    val id: String = java.util.UUID.randomUUID().toString(),
    var title: String = "New chat",
    val createdAt: Long = System.currentTimeMillis(),
    var lastActiveAt: Long = System.currentTimeMillis(),
    val messages: MutableList<ChatMessage> = mutableListOf(),
    val attachments: MutableList<ContextAttachment> = mutableListOf(),
    var usage: Usage = Usage.ZERO,
    /** Rolling summary of compacted older turns (Grok-style); UI keeps full messages. */
    var compactSummary: String? = null,
    /** How many leading messages have been folded into [compactSummary]. */
    var compactedMessageCount: Int = 0
) {
    fun addMessage(msg: ChatMessage) {
        messages.add(msg)
        lastActiveAt = System.currentTimeMillis()
        if (title == "New chat" && msg.role == ChatMessage.Role.user) {
            title = msg.content.lineSequence().firstOrNull()?.take(36)?.trim()?.ifBlank { "New chat" } ?: "New chat"
        }
    }

    fun touch() {
        lastActiveAt = System.currentTimeMillis()
    }
}