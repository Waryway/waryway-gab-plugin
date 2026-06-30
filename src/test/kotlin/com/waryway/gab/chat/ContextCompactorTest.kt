package com.waryway.gab.chat

import com.waryway.gab.model.ChatMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContextCompactorTest {

    @Test
    fun `compact folds old turns and keeps recent verbatim`() {
        val messages = buildHistory(8)
        val result = ContextCompactor.compact(
            messages = messages,
            config = ContextCompactor.Config(
                contextTokenLimit = 4096,
                completionReserve = 512,
                keepRecentTurns = 2,
                triggerRatio = 0.01
            )
        )
        assertTrue(result.didCompact)
        assertEquals(5, result.apiMessages.size) // summary + 2 turns (user+assistant each)
        assertTrue(result.apiMessages.first().content.startsWith(ContextCompactor.SUMMARY_PREFIX))
        assertTrue(result.compactSummary.orEmpty().contains("Turn 1"))
        assertEquals(messages.last().content, result.apiMessages.last().content)
    }

    @Test
    fun `compact within budget leaves messages unchanged`() {
        val messages = listOf(
            ChatMessage(ChatMessage.Role.user, "Hi"),
            ChatMessage(ChatMessage.Role.assistant, "Hello")
        )
        val result = ContextCompactor.compact(
            messages = messages,
            config = ContextCompactor.Config(triggerRatio = 0.99)
        )
        assertEquals(messages, result.apiMessages)
        assertTrue(!result.didCompact)
    }

    @Test
    fun `mergeSummary accumulates prior summary`() {
        val merged = ContextCompactor.mergeSummary(
            existing = "Earlier: fixed streaming bug",
            newlyFolded = listOf(
                ChatMessage(ChatMessage.Role.user, "Add activity log"),
                ChatMessage(ChatMessage.Role.assistant, "Added Activity log panel in 0.1.20")
            ),
            maxChars = 2000
        )
        assertTrue(merged.contains("Earlier: fixed streaming bug"))
        assertTrue(merged.contains("Add activity log"))
        assertTrue(merged.contains("Activity log"))
    }

    private fun buildHistory(turnCount: Int): List<ChatMessage> =
        buildList {
            repeat(turnCount) { i ->
                add(ChatMessage(ChatMessage.Role.user, "Question number ${i + 1} about the local LLM plugin"))
                add(
                    ChatMessage(
                        ChatMessage.Role.assistant,
                        "Answer number ${i + 1}: implemented feature with tests and build verification."
                    )
                )
            }
        }
}