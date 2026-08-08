package com.waryway.gab.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SSE / tool-call / finish-reason fidelity + premature-stop regression tests.
 *
 * Contract (wo-s02-01-01 / ChatCompletionResult):
 *   content, toolCalls, finishReason, usage,
 *   cancelled = false, streamError = null, incompleteToolCallCount = 0
 *
 * Incomplete builders (blank id and/or name) are excluded from toolCalls and counted
 * in incompleteToolCallCount — never a silent zero-tool stop with no signal.
 *
 * Regression matrix (wo-04-01-02 / section-04): empty stream, error non-silent,
 * finish_reason variants (tool_calls / stop / length / absent-null), incomplete builders.
 * Fixtures are inline `data:` lines only — no network.
 */
class GabSseAccumulatorTest {

    // --- content / happy path ---

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
        assertEquals(0, result.incompleteToolCallCount)
        assertEquals(false, result.cancelled)
        assertNull(result.streamError)
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
        assertEquals(0, result.incompleteToolCallCount)
        assertEquals(10, result.usage.promptTokens)
        assertEquals(false, result.cancelled)
        assertNull(result.streamError)
    }

    // --- empty stream ---

    @Test
    fun `empty stream only DONE yields null content empty tools and null finish`() {
        val result = GabSseAccumulator.parseSseLines(sequenceOf("data: [DONE]"))
        assertNull(result.content)
        assertTrue(result.toolCalls.isEmpty())
        assertNull(result.finishReason)
        assertEquals(0, result.incompleteToolCallCount)
        assertEquals(false, result.cancelled)
        assertNull(result.streamError)
    }

    @Test
    fun `empty stream no data lines yields clean empty result`() {
        val result = GabSseAccumulator.parseSseLines(emptySequence())
        assertNull(result.content)
        assertTrue(result.toolCalls.isEmpty())
        assertNull(result.finishReason)
        assertEquals(0, result.incompleteToolCallCount)
        assertEquals(false, result.cancelled)
        assertNull(result.streamError)
    }

    @Test
    fun `blank and non data lines are ignored`() {
        val result = GabSseAccumulator.parseSseLines(
            sequenceOf("", "  ", "event: ping", ":", "data: [DONE]")
        )
        assertNull(result.content)
        assertTrue(result.toolCalls.isEmpty())
        assertNull(result.finishReason)
        assertEquals(0, result.incompleteToolCallCount)
    }

    // --- error event ---

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
        // Error must also land on the result — never silent empty success.
        val result = acc.toResult()
        assertEquals("inference failed", result.streamError)
        assertNull(result.finishReason)
        assertTrue(result.toolCalls.isEmpty())
        assertEquals(0, result.incompleteToolCallCount)
    }

    @Test
    fun `parseSseLines records streamError from error payload`() {
        // parseSseLines uses processSseLine with onEvent=null; streamError still recorded.
        val result = GabSseAccumulator.parseSseLines(
            sequenceOf(
                """data: {"error":{"message":"rate limited","type":"server_error"}}""",
                "data: [DONE]"
            )
        )
        assertEquals("rate limited", result.streamError)
        assertNull(result.content)
        assertNull(result.finishReason)
        assertEquals(false, result.cancelled)
    }

    // --- finish_reason variants ---

    @Test
    fun `finish_reason tool_calls is captured`() {
        val result = GabSseAccumulator.parseSseLines(
            sequenceOf(
                """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","type":"function","function":{"name":"f","arguments":"{}"}}]}}]}""",
                """data: {"choices":[{"finish_reason":"tool_calls"}]}""",
                "data: [DONE]"
            )
        )
        assertEquals("tool_calls", result.finishReason)
        assertEquals(1, result.toolCalls.size)
        assertEquals(0, result.incompleteToolCallCount)
    }

    @Test
    fun `finish_reason stop is captured`() {
        val result = GabSseAccumulator.parseSseLines(
            sequenceOf(
                """data: {"choices":[{"delta":{"content":"done."}}]}""",
                """data: {"choices":[{"finish_reason":"stop"}]}""",
                "data: [DONE]"
            )
        )
        assertEquals("stop", result.finishReason)
        assertEquals("done.", result.content)
        assertTrue(result.toolCalls.isEmpty())
        assertEquals(0, result.incompleteToolCallCount)
        assertNull(result.streamError)
    }

    @Test
    fun `finish_reason length is captured`() {
        val result = GabSseAccumulator.parseSseLines(
            sequenceOf(
                """data: {"choices":[{"delta":{"content":"truncated"}}]}""",
                """data: {"choices":[{"finish_reason":"length"}]}""",
                "data: [DONE]"
            )
        )
        assertEquals("length", result.finishReason)
        assertEquals("truncated", result.content)
        assertNull(result.streamError)
        assertEquals(false, result.cancelled)
    }

    @Test
    fun `finish_reason null stays null not stop`() {
        val lines = sequenceOf(
            """data: {"choices":[{"delta":{"content":"hi"},"finish_reason":null}]}""",
            "data: [DONE]"
        )
        val result = GabSseAccumulator.parseSseLines(lines)
        assertEquals("hi", result.content)
        assertNull(result.finishReason)
        assertEquals(false, result.cancelled)
        assertNull(result.streamError)
    }

    @Test
    fun `missing finish_reason stays null not string null`() {
        val result = GabSseAccumulator.parseSseLines(
            sequenceOf(
                """data: {"choices":[{"delta":{"content":"partial only"}}]}""",
                "data: [DONE]"
            )
        )
        assertEquals("partial only", result.content)
        assertNull(result.finishReason)
        // Ensure we did not invent the string "null"
        assertTrue(result.finishReason != "null")
    }

    // --- incomplete tool_calls ---

    @Test
    fun `incomplete tool builders are flagged not silent dropped`() {
        val lines = sequenceOf(
            // name present, id missing → incomplete
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"type":"function","function":{"name":"read_file","arguments":"{}"}}]}}]}""",
            """data: {"choices":[{"finish_reason":"tool_calls"}]}""",
            "data: [DONE]"
        )
        val result = GabSseAccumulator.parseSseLines(lines)
        assertEquals(0, result.toolCalls.size)
        assertEquals(1, result.incompleteToolCallCount)
        assertEquals("tool_calls", result.finishReason)
        assertEquals(false, result.cancelled)
        // Must not look like a clean zero-tool stop with no signal
        assertTrue(result.incompleteToolCallCount > 0)
    }

    @Test
    fun `incomplete tool with id but blank name is flagged`() {
        val result = GabSseAccumulator.parseSseLines(
            sequenceOf(
                """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_x","type":"function","function":{"arguments":"{}"}}]}}]}""",
                """data: {"choices":[{"finish_reason":"tool_calls"}]}""",
                "data: [DONE]"
            )
        )
        assertEquals(0, result.toolCalls.size)
        assertEquals(1, result.incompleteToolCallCount)
        assertEquals("tool_calls", result.finishReason)
    }

    @Test
    fun `incomplete tool with only arguments fragments is flagged`() {
        val result = GabSseAccumulator.parseSseLines(
            sequenceOf(
                """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"a\":1}"}}]}}]}""",
                """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":""}}]}}]}""",
                "data: [DONE]"
            )
        )
        assertEquals(0, result.toolCalls.size)
        assertEquals(1, result.incompleteToolCallCount)
        assertNull(result.finishReason)
        // Non-silent: incomplete count is the signal (no fake tool invented)
        assertTrue(result.incompleteToolCallCount > 0)
    }

    @Test
    fun `mixed complete and incomplete tool builders count incomplete only`() {
        val result = GabSseAccumulator.parseSseLines(
            sequenceOf(
                """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_ok","type":"function","function":{"name":"read_file","arguments":"{}"}}]}}]}""",
                """data: {"choices":[{"delta":{"tool_calls":[{"index":1,"type":"function","function":{"name":"missing_id","arguments":"{}"}}]}}]}""",
                """data: {"choices":[{"finish_reason":"tool_calls"}]}""",
                "data: [DONE]"
            )
        )
        assertEquals(1, result.toolCalls.size)
        assertEquals("read_file", result.toolCalls[0].name)
        assertEquals(1, result.incompleteToolCallCount)
        assertEquals("tool_calls", result.finishReason)
    }

    // --- cancel mapping (pure, no network) ---

    @Test
    fun `toResult cancelled flag does not invent finish stop`() {
        val acc = GabSseAccumulator()
        GabSseAccumulator.processSseLine(
            """data: {"choices":[{"delta":{"content":"partial"}}]}""",
            acc,
            null
        )
        val result = acc.toResult(cancelled = true)
        assertEquals(true, result.cancelled)
        assertNull(result.finishReason)
        assertEquals("partial", result.content)
        assertEquals(0, result.incompleteToolCallCount)
        assertNull(result.streamError)
    }

    @Test
    fun `toResult cancelled preserves observed finish_reason when present`() {
        val acc = GabSseAccumulator()
        GabSseAccumulator.processSseLine(
            """data: {"choices":[{"delta":{"content":"x"},"finish_reason":"stop"}]}""",
            acc,
            null
        )
        val result = acc.toResult(cancelled = true)
        assertEquals(true, result.cancelled)
        // Cancel must not clear or invent finish — keep what was observed
        assertEquals("stop", result.finishReason)
        assertEquals("x", result.content)
    }

    // --- complementary premature-stop regression locks (wo-04-01-02) ---

    @Test
    fun `processSseLine Finish event surfaces finish_reason stop`() {
        val events = mutableListOf<GabSseAccumulator.SseEvent>()
        val acc = GabSseAccumulator()
        GabSseAccumulator.processSseLine(
            """data: {"choices":[{"delta":{"content":"x"},"finish_reason":"stop"}]}""",
            acc,
            events::add
        )
        val finishes = events.filterIsInstance<GabSseAccumulator.SseEvent.Finish>()
        assertEquals(1, finishes.size)
        assertEquals("stop", finishes[0].reason)
        assertEquals("stop", acc.toResult().finishReason)
        assertEquals("x", acc.toResult().content)
    }

    @Test
    fun `error after partial content keeps content and flags streamError`() {
        // Partial deltas then SSE error — must not look like clean stop success.
        val lines = sequenceOf(
            """data: {"choices":[{"delta":{"content":"partial "}}]}""",
            """data: {"choices":[{"delta":{"content":"reply"}}]}""",
            """data: {"error":{"message":"stream aborted","type":"server_error"}}""",
            "data: [DONE]"
        )
        val events = mutableListOf<GabSseAccumulator.SseEvent>()
        val acc = GabSseAccumulator()
        for (line in lines) {
            GabSseAccumulator.processSseLine(line, acc, events::add)
        }
        val result = acc.toResult()
        assertEquals("partial reply", result.content)
        assertEquals("stream aborted", result.streamError)
        assertNull(result.finishReason)
        assertEquals(false, result.cancelled)
        assertTrue(events.any { it is GabSseAccumulator.SseEvent.Error })
        assertTrue(events.any { it is GabSseAccumulator.SseEvent.Delta })
    }

    @Test
    fun `null finish_reason is distinguishable from stop string`() {
        val nullFinish = GabSseAccumulator.parseSseLines(
            sequenceOf(
                """data: {"choices":[{"delta":{"content":"a"},"finish_reason":null}]}""",
                "data: [DONE]"
            )
        )
        val stopFinish = GabSseAccumulator.parseSseLines(
            sequenceOf(
                """data: {"choices":[{"delta":{"content":"a"}}]}""",
                """data: {"choices":[{"finish_reason":"stop"}]}""",
                "data: [DONE]"
            )
        )
        assertNull(nullFinish.finishReason)
        assertEquals("stop", stopFinish.finishReason)
        assertTrue(nullFinish.finishReason != stopFinish.finishReason)
        assertEquals(nullFinish.content, stopFinish.content)
    }

    @Test
    fun `empty data payload after data colon is ignored like DONE`() {
        val result = GabSseAccumulator.parseSseLines(
            sequenceOf("data:", "data: ", "data: [DONE]")
        )
        assertNull(result.content)
        assertTrue(result.toolCalls.isEmpty())
        assertNull(result.finishReason)
        assertNull(result.streamError)
        assertEquals(0, result.incompleteToolCallCount)
        assertEquals(false, result.cancelled)
    }

    @Test
    fun `incomplete tools plus streamError both surface on result`() {
        val result = GabSseAccumulator.parseSseLines(
            sequenceOf(
                """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"type":"function","function":{"name":"orphan","arguments":"{}"}}]}}]}""",
                """data: {"error":{"message":"tool stream truncated","type":"server_error"}}""",
                "data: [DONE]"
            )
        )
        assertEquals(0, result.toolCalls.size)
        assertEquals(1, result.incompleteToolCallCount)
        assertEquals("tool stream truncated", result.streamError)
        assertNull(result.finishReason)
        // Dual signal: incomplete builders and stream error must both be non-silent
        assertTrue(result.incompleteToolCallCount > 0)
        assertTrue(!result.streamError.isNullOrBlank())
    }

    // --- snapshot-safe content merge (WO-02) ---

    @Test
    fun `cumulative snapshot chunks do not quadratic append`() {
        val lines = sequenceOf(
            """data: {"choices":[{"delta":{"content":"H"}}]}""",
            """data: {"choices":[{"delta":{"content":"He"}}]}""",
            """data: {"choices":[{"delta":{"content":"Hel"}}]}""",
            """data: {"choices":[{"delta":{"content":"Hello"}}]}""",
            "data: [DONE]"
        )
        val result = GabSseAccumulator.parseSseLines(lines)
        assertEquals("Hello", result.content)
    }

    @Test
    fun `true delta chunks still append`() {
        val lines = sequenceOf(
            """data: {"choices":[{"delta":{"content":"Hel"}}]}""",
            """data: {"choices":[{"delta":{"content":"lo"}}]}""",
            "data: [DONE]"
        )
        val result = GabSseAccumulator.parseSseLines(lines)
        assertEquals("Hello", result.content)
    }

    @Test
    fun `processSseLine Delta events emit only new visible suffix for snapshots`() {
        val events = mutableListOf<GabSseAccumulator.SseEvent>()
        val acc = GabSseAccumulator()
        val lines = listOf(
            """data: {"choices":[{"delta":{"content":"H"}}]}""",
            """data: {"choices":[{"delta":{"content":"He"}}]}""",
            """data: {"choices":[{"delta":{"content":"Hello"}}]}"""
        )
        for (line in lines) {
            GabSseAccumulator.processSseLine(line, acc, events::add)
        }
        val deltas = events.filterIsInstance<GabSseAccumulator.SseEvent.Delta>().map { it.text }
        assertEquals(listOf("H", "e", "llo"), deltas)
        assertEquals("Hello", acc.toResult().content)
    }

    @Test
    fun `processSseLine Delta events emit full fragment for true deltas`() {
        val events = mutableListOf<GabSseAccumulator.SseEvent>()
        val acc = GabSseAccumulator()
        for (line in listOf(
            """data: {"choices":[{"delta":{"content":"Hel"}}]}""",
            """data: {"choices":[{"delta":{"content":"lo"}}]}"""
        )) {
            GabSseAccumulator.processSseLine(line, acc, events::add)
        }
        val deltas = events.filterIsInstance<GabSseAccumulator.SseEvent.Delta>().map { it.text }
        assertEquals(listOf("Hel", "lo"), deltas)
        assertEquals("Hello", acc.toResult().content)
    }

    @Test
    fun `empty content delta does not change accumulated content`() {
        val result = GabSseAccumulator.parseSseLines(
            sequenceOf(
                """data: {"choices":[{"delta":{"content":"hi"}}]}""",
                """data: {"choices":[{"delta":{"content":""}}]}""",
                "data: [DONE]"
            )
        )
        assertEquals("hi", result.content)
    }

    @Test
    fun `tool call stream unaffected by content merger`() {
        val lines = sequenceOf(
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"read_file","arguments":""}}]}}]}""",
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"file_path\":\"a.kt\"}"}}]}}]}""",
            """data: {"choices":[{"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}""",
            "data: [DONE]"
        )
        val result = GabSseAccumulator.parseSseLines(lines)
        assertEquals(1, result.toolCalls.size)
        assertEquals("read_file", result.toolCalls[0].name)
        assertTrue(result.toolCalls[0].arguments.contains("a.kt"))
        assertEquals(0, result.incompleteToolCallCount)
        assertNull(result.content)
    }
}
