package com.waryway.gab.chat

import com.waryway.gab.model.ChatMessage
import java.util.IdentityHashMap

/**
 * Sync helpers for multi-turn agent history.
 *
 * [AgentSession] mutates the API message list in place: it may **prepend** a system
 * prompt and append assistant/tool turns. Index-based "new suffix" detection is wrong
 * after a prepend (it re-copies the last user turn and duplicates bubbles).
 */
object ConversationHistorySync {

    /**
     * Messages that should be persisted into the conversation after [AgentSession.run].
     * Uses reference identity against [beforeSnapshot] so prepends/replaces are handled.
     *
     * Never persists:
     * - [ChatMessage.Role.system] (API-only system / compaction prompts)
     * - [ChatMessage.Role.user] (already stored on Send; local LLM may rewrite the last
     *   user turn for the request without intending a second bubble)
     */
    fun messagesToPersist(
        beforeSnapshot: List<ChatMessage>,
        afterRun: List<ChatMessage>
    ): List<ChatMessage> {
        val known = IdentityHashMap<ChatMessage, Boolean>()
        beforeSnapshot.forEach { known[it] = true }
        return afterRun.filter { msg ->
            !known.containsKey(msg) &&
                msg.role != ChatMessage.Role.system &&
                msg.role != ChatMessage.Role.user
        }
    }

    /**
     * Project stored messages into user-facing chat rows.
     * Hides tool traffic and empty/tool-only assistant stubs so reloads match the
     * live "one user bubble + one agent bubble" UX.
     */
    fun toDisplayEntries(messages: List<ChatMessage>): List<DisplayEntry> {
        val out = ArrayList<DisplayEntry>(messages.size)
        for (msg in messages) {
            when (msg.role) {
                ChatMessage.Role.user -> {
                    val text = msg.content.trim()
                    if (text.isNotEmpty()) {
                        out.add(DisplayEntry(DisplayRole.USER, text))
                    }
                }
                ChatMessage.Role.assistant -> {
                    // Tool-only intermediate turns (content empty, toolCalls set) stay hidden.
                    if (msg.toolCalls.isNotEmpty() && msg.content.isBlank()) continue
                    val text = msg.content.trim()
                    if (text.isNotEmpty()) {
                        out.add(DisplayEntry(DisplayRole.ASSISTANT, text))
                    }
                }
                ChatMessage.Role.tool -> {
                    // Tool results are agent-internal; not shown as chat bubbles.
                }
                ChatMessage.Role.system -> {
                    // API system prompts and compact summaries are not chat UI.
                }
            }
        }
        return out
    }

    data class DisplayEntry(val role: DisplayRole, val text: String)

    enum class DisplayRole { USER, ASSISTANT }
}
