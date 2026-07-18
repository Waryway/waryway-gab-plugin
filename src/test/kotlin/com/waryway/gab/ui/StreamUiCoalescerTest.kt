package com.waryway.gab.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pure unit tests for [StreamUiCoalescer] batching. */
class StreamUiCoalescerTest {

    @Test
    fun `offer empty delta is ignored`() {
        val c = StreamUiCoalescer(flushMaxChars = 10)
        assertFalse(c.offer(""))
        assertTrue(c.isEmpty())
        assertEquals(0, c.pendingLength())
    }

    @Test
    fun `offer accumulates without force until max`() {
        val c = StreamUiCoalescer(flushMaxChars = 10)
        assertFalse(c.offer("Hel"))
        assertFalse(c.offer("lo"))
        assertEquals(5, c.pendingLength())
        assertEquals("Hello", c.drain())
        assertTrue(c.isEmpty())
    }

    @Test
    fun `offer returns true when pending reaches flushMaxChars`() {
        val c = StreamUiCoalescer(flushMaxChars = 5)
        assertFalse(c.offer("Hi"))
        assertTrue(c.offer("!!!")) // 5 chars total
        assertEquals("Hi!!!", c.drain())
    }

    @Test
    fun `offer returns true when single delta exceeds max`() {
        val c = StreamUiCoalescer(flushMaxChars = 4)
        assertTrue(c.offer("12345"))
        assertEquals("12345", c.drain())
    }

    @Test
    fun `drain returns empty when nothing pending`() {
        val c = StreamUiCoalescer()
        assertEquals("", c.drain())
    }

    @Test
    fun `drain clears so subsequent drain is empty`() {
        val c = StreamUiCoalescer()
        c.offer("abc")
        assertEquals("abc", c.drain())
        assertEquals("", c.drain())
        assertTrue(c.isEmpty())
    }

    @Test
    fun `clear discards without return`() {
        val c = StreamUiCoalescer()
        c.offer("discard me")
        c.clear()
        assertTrue(c.isEmpty())
        assertEquals("", c.drain())
    }

    @Test
    fun `batches many small deltas into one drain`() {
        val c = StreamUiCoalescer(flushMaxChars = 10_000)
        val tokens = listOf("The", " ", "quick", " ", "brown", " ", "fox")
        tokens.forEach { assertFalse(c.offer(it)) }
        assertEquals("The quick brown fox", c.drain())
    }

    @Test
    fun `after force flush more offers accumulate again`() {
        val c = StreamUiCoalescer(flushMaxChars = 3)
        assertTrue(c.offer("abc"))
        assertEquals("abc", c.drain())
        assertFalse(c.offer("x"))
        assertEquals("x", c.drain())
    }
}
