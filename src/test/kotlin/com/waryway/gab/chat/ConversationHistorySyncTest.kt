package com.waryway.gab.chat

import com.waryway.gab.model.ChatMessage
import com.waryway.gab.model.ToolCall
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConversationHistorySyncTest {

    @Test
    fun `messagesToPersist ignores system prepend and does not re-copy user`() {
        val user = ChatMessage(ChatMessage.Role.user, "hello")
        val before = listOf(user)

        // AgentSession prepends system then appends assistant — index-based suffix would re-add user.
        val system = ChatMessage(ChatMessage.Role.system, "You are an agent…")
        val assistant = ChatMessage(ChatMessage.Role.assistant, "Hi there")
        val after = listOf(system, user, assistant)

        val persist = ConversationHistorySync.messagesToPersist(before, after)
        assertEquals(listOf(assistant), persist)
    }

    @Test
    fun `messagesToPersist keeps tool call chain for multi-turn agent history`() {
        val user = ChatMessage(ChatMessage.Role.user, "read main.go")
        val before = listOf(user)

        val system = ChatMessage(ChatMessage.Role.system, "sys")
        val withTools = ChatMessage(
            role = ChatMessage.Role.assistant,
            content = "",
            toolCalls = listOf(ToolCall("1", "read_file", """{"path":"main.go"}"""))
        )
        val toolResult = ChatMessage(
            role = ChatMessage.Role.tool,
            content = "package main",
            toolCallId = "1"
        )
        val finalReply = ChatMessage(ChatMessage.Role.assistant, "main.go is a Go package.")
        val after = listOf(system, user, withTools, toolResult, finalReply)

        val persist = ConversationHistorySync.messagesToPersist(before, after)
        assertEquals(listOf(withTools, toolResult, finalReply), persist)
    }

    @Test
    fun `messagesToPersist skips local LLM rewritten user turn`() {
        val user = ChatMessage(ChatMessage.Role.user, "hello")
        val before = listOf(user)
        // prepareLocalLlmMessages replaces last user with a project-prefixed copy.
        val rewritten = ChatMessage(ChatMessage.Role.user, "[Project: C:/dev/x]\nhello")
        val assistant = ChatMessage(ChatMessage.Role.assistant, "ok")
        val after = listOf(rewritten, assistant)

        val persist = ConversationHistorySync.messagesToPersist(before, after)
        assertEquals(listOf(assistant), persist)
    }

    @Test
    fun `toDisplayEntries hides tool traffic and empty tool-only assistants`() {
        val messages = listOf(
            ChatMessage(ChatMessage.Role.system, "api system"),
            ChatMessage(ChatMessage.Role.user, "fix the bug"),
            ChatMessage(
                role = ChatMessage.Role.assistant,
                content = "",
                toolCalls = listOf(ToolCall("1", "search_text", "{}"))
            ),
            ChatMessage(ChatMessage.Role.tool, "matches: 3", toolCallId = "1"),
            ChatMessage(ChatMessage.Role.assistant, "Found 3 matches — here is the fix.")
        )

        val display = ConversationHistorySync.toDisplayEntries(messages)
        assertEquals(2, display.size)
        assertEquals(ConversationHistorySync.DisplayRole.USER, display[0].role)
        assertEquals("fix the bug", display[0].text)
        assertEquals(ConversationHistorySync.DisplayRole.ASSISTANT, display[1].role)
        assertTrue(display[1].text.contains("Found 3 matches"))
    }

    @Test
    fun `toDisplayEntries keeps multi-turn user and assistant pairs`() {
        val messages = listOf(
            ChatMessage(ChatMessage.Role.user, "first"),
            ChatMessage(ChatMessage.Role.assistant, "reply one"),
            ChatMessage(ChatMessage.Role.user, "second"),
            ChatMessage(ChatMessage.Role.assistant, "reply two")
        )
        val display = ConversationHistorySync.toDisplayEntries(messages)
        assertEquals(4, display.size)
        assertEquals(listOf("first", "reply one", "second", "reply two"), display.map { it.text })
    }
}
