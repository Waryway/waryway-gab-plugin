package com.waryway.gab.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GabSseAccumulatorTest {

    @Test
    fun `parseSseLines accumulates content deltas`() {
        val lines = sequenceOf(
            """data: {"choices":[{"delta":{"content":"Hello"}}]}""",
            """data: {"choices":[{"delta":{"content":" world"}}]}""",
            "data: [DONE]"
        )
        val result = GabSseAccumulator.parseSseLines(lines)
        assertEquals("Hello world", result.content)
        assertTrue(result.toolCalls.isEmpty())
    }

    @Test
    fun `processSseLine surfaces server error events`() {
        val events = mutableListOf<GabSseAccumulator.SseEvent>()
        val acc = GabSseAccumulator()
        GabSseAccumulator.processSseLine(
            """data: {"error":{"message":"inference failed","type":"server_error"}}""",
            acc,
            events::add
        )
        assertEquals(1, events.size)
        assertTrue(events[0] is GabSseAccumulator.SseEvent.Error)
        assertEquals("inference failed", (events[0] as GabSseAccumulator.SseEvent.Error).message)
    }

    @Test
    fun `parseSseLines accumulates fragmented tool calls`() {
        val lines = sequenceOf(
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"read_file","arguments":""}}]}}]}""",
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"file"}}]}}]}""",
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"_path\":\"a.kt\"}"}}]}}]}""",
            """data: {"choices":[{"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}""",
            "data: [DONE]"
        )
        val result = GabSseAccumulator.parseSseLines(lines)
        assertEquals("tool_calls", result.finishReason)
        assertEquals(1, result.toolCalls.size)
        assertEquals("read_file", result.toolCalls[0].name)
        assertTrue(result.toolCalls[0].arguments.contains("a.kt"))
        assertEquals(10, result.usage.promptTokens)
    }
}