package com.waryway.gab.client

import com.waryway.gab.model.ChatMessage
import com.waryway.gab.model.ModelProvider
import com.waryway.gab.model.ToolCall
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GabClientJsonTest {

    private val client = GabClient("test-key")
    private val grok = GabClient("xai-test-key", ModelProvider.GROK)
    private val grokBuild = GabClient("session-token", ModelProvider.GROK_BUILD)
    private val gab = GabClient("gab-test-key", ModelProvider.GAB_AI)

    private val toolsJson = """{"type":"function","function":{"name":"read_file"}}"""
    private val userHi = listOf(ChatMessage(ChatMessage.Role.user, "hi"))

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
            messages = userHi,
            stream = false,
            toolsJson = toolsJson
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
            messages = userHi,
            stream = true,
            toolsJson = toolsJson,
            includeTools = false
        )
        assertContains(body, """"localllm"""")
        assertContains(body, "gab-chat")
        assertContains(body, "max_tokens")
        assertTrue(!body.contains("tool_choice"))
    }

    @Test
    fun `grok request is OpenAI-compatible with tools and stream`() {
        val body = grok.buildJsonChatRequest(
            model = "grok-4",
            messages = userHi,
            stream = true,
            toolsJson = toolsJson,
            includeTools = true
        )
        assertContains(body, """"model": "grok-4"""")
        assertContains(body, """"stream": true""")
        assertContains(body, """"tools"""")
        assertContains(body, "tool_choice")
        assertContains(body, "read_file")
        assertContains(body, """"role":"user"""")
        // Must not emit Local-only fields that xAI would reject
        assertFalse(body.contains("localllm"), "Grok body must not include localllm")
        assertFalse(body.contains("max_tokens"), "Grok body must not force Local max_tokens")
    }

    @Test
    fun `grok request honors stream false without local fields`() {
        val body = grok.buildJsonChatRequest(
            model = "grok-3",
            messages = userHi,
            stream = false,
            toolsJson = "",
            includeTools = true
        )
        assertContains(body, """"stream": false""")
        assertFalse(body.contains("tool_choice"))
        assertFalse(body.contains("localllm"))
        assertFalse(body.contains("max_tokens"))
    }

    @Test
    fun `grok ignores accidental preset override`() {
        // Even if a caller passes a local preset, cloud Grok must stay clean OpenAI shape.
        val body = grok.buildJsonChatRequest(
            model = "grok-4",
            messages = userHi,
            stream = true,
            toolsJson = toolsJson,
            includeTools = true,
            presetOverride = "gab-chat"
        )
        assertFalse(body.contains("localllm"))
        assertFalse(body.contains("gab-chat"))
        assertContains(body, "tool_choice")
    }

    @Test
    fun `gab request remains OpenAI-compatible without local fields`() {
        val body = gab.buildJsonChatRequest(
            model = "gpt-5",
            messages = userHi,
            stream = true,
            toolsJson = toolsJson,
            includeTools = true,
            presetOverride = "should-not-appear"
        )
        assertContains(body, """"tools"""")
        assertContains(body, "tool_choice")
        assertFalse(body.contains("localllm"))
        assertFalse(body.contains("max_tokens"))
        assertFalse(body.contains("should-not-appear"))
    }

    @Test
    fun `grok build request is OpenAI-compatible with tools and stream`() {
        val body = grokBuild.buildJsonChatRequest(
            model = "grok-4.5",
            messages = userHi,
            stream = true,
            toolsJson = toolsJson,
            includeTools = true
        )
        assertContains(body, """"model": "grok-4.5"""")
        assertContains(body, """"stream": true""")
        assertContains(body, """"tools"""")
        assertContains(body, "tool_choice")
        assertContains(body, "read_file")
        assertContains(body, """"role":"user"""")
        // cli-chat-proxy expects the same OpenAI body shape as api.x.ai — no Local fields
        assertFalse(body.contains("localllm"), "GROK_BUILD body must not include localllm")
        assertFalse(body.contains("max_tokens"), "GROK_BUILD body must not force Local max_tokens")
    }

    @Test
    fun `grok build request honors stream false without local fields`() {
        val body = grokBuild.buildJsonChatRequest(
            model = "grok-4.5",
            messages = userHi,
            stream = false,
            toolsJson = "",
            includeTools = true
        )
        assertContains(body, """"stream": false""")
        assertFalse(body.contains("tool_choice"))
        assertFalse(body.contains("localllm"))
        assertFalse(body.contains("max_tokens"))
    }

    @Test
    fun `grok build ignores accidental preset override`() {
        val body = grokBuild.buildJsonChatRequest(
            model = "grok-4.5",
            messages = userHi,
            stream = true,
            toolsJson = toolsJson,
            includeTools = true,
            presetOverride = "gab-chat"
        )
        assertFalse(body.contains("localllm"))
        assertFalse(body.contains("gab-chat"))
        assertContains(body, "tool_choice")
    }

    @Test
    fun `grok build provider uses cli-chat-proxy base url and skips credits`() {
        assertEquals(ModelProvider.GROK_BUILD, grokBuild.provider)
        assertEquals("https://cli-chat-proxy.grok.com/v1", ModelProvider.GROK_BUILD.baseUrl)
        assertFalse(ModelProvider.GROK_BUILD.supportsCredits)
    }

    @Test
    fun `GabApiException retains body for non-2xx mapping`() {
        // chatCompletionStreaming throws GabApiException("Chat failed: HTTP …", errBody)
        // so callers can surface server detail — never empty content as success.
        val e = GabClient.GabApiException("Chat failed: HTTP 401", """{"error":{"message":"unauthorized"}}""")
        assertEquals("Chat failed: HTTP 401", e.message)
        assertEquals("""{"error":{"message":"unauthorized"}}""", e.body)
    }

    @Test
    fun `grok client uses xai base url and skips credits support`() {
        assertEquals(ModelProvider.GROK, grok.provider)
        assertEquals("https://api.x.ai/v1", ModelProvider.GROK.baseUrl)
        assertFalse(ModelProvider.GROK.supportsCredits)
        assertTrue(ModelProvider.GAB_AI.supportsCredits)
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
