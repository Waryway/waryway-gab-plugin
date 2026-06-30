package com.waryway.gab.client

import com.waryway.gab.model.ChatMessage
import com.waryway.gab.model.ModelProvider
import com.waryway.gab.model.ToolCall
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class GabClientJsonTest {

    private val client = GabClient("test-key")

    @Test
    fun `messageToJson includes tool_calls for assistant`() {
        val json = client.messageToJson(
            ChatMessage(
                role = ChatMessage.Role.assistant,
                content = "",
                toolCalls = listOf(ToolCall("call_1", "read_file", """{"file_path":"src/main.kt"}"""))
            )
        )
        assertContains(json, """"tool_calls"""")
        assertContains(json, "read_file")
        assertContains(json, "call_1")
    }

    @Test
    fun `messageToJson includes tool role fields`() {
        val json = client.messageToJson(
            ChatMessage(
                role = ChatMessage.Role.tool,
                content = "ok",
                toolCallId = "call_1"
            )
        )
        assertContains(json, """"role":"tool"""")
        assertContains(json, """"tool_call_id":"call_1"""")
    }

    @Test
    fun `buildJsonChatRequest includes tools`() {
        val body = client.buildJsonChatRequest(
            model = "gpt-5",
            messages = listOf(ChatMessage(ChatMessage.Role.user, "hi")),
            stream = false,
            toolsJson = """{"type":"function","function":{"name":"read_file"}}"""
        )
        assertContains(body, """"tools"""")
        assertContains(body, "tool_choice")
        assertTrue(body.contains("read_file"))
    }

    @Test
    fun `local llm request includes preset and skips tools`() {
        val local = GabClient("localllm-local", ModelProvider.LOCAL_LLM, localLlmPreset = "gab-chat")
        val body = local.buildJsonChatRequest(
            model = "localllm-coder",
            messages = listOf(ChatMessage(ChatMessage.Role.user, "hi")),
            stream = true,
            toolsJson = """{"type":"function","function":{"name":"read_file"}}""",
            includeTools = false
        )
        assertContains(body, """"localllm"""")
        assertContains(body, "gab-chat")
        assertContains(body, "max_tokens")
        assertTrue(!body.contains("tool_choice"))
    }

    @Test
    fun `parseToolCallObject extracts nested argument json`() {
        val tool = client.parseToolCallObject(
            """{"id":"call_1","type":"function","function":{"name":"read_file","arguments":"{\"file_path\":\"src/main.kt\",\"offset\":1}"}}"""
        )
        assertTrue(tool != null)
        assertTrue(tool!!.arguments.contains("file_path"))
        assertTrue(tool.arguments.contains("src/main.kt"))
    }

    @Test
    fun `findMatchingBracket handles nested braces in strings`() {
        val source = """[{"id":"call_1","function":{"arguments":"{\"x\":\"}\"}"}}]"""
        val start = source.indexOf('[')
        val end = client.findMatchingBracket(source, start, '[', ']')
        assertTrue(end != null)
        assertTrue(source[end!!] == ']')
    }
}