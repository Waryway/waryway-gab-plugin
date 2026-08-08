package com.waryway.gab.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure merge rules for SSE content: true deltas vs cumulative snapshots.
 */
class StreamContentMergerTest {

    @Test
    fun `normal deltas append`() {
        assertEquals("Hello", StreamContentMerger.merge("Hel", "lo"))
        assertEquals("lo", StreamContentMerger.visibleDelta("Hel", "lo"))
    }

    @Test
    fun `snapshot extension replaces with full incoming`() {
        var acc = ""
        for (snap in listOf("H", "He", "Hel", "Hello")) {
            acc = StreamContentMerger.merge(acc, snap)
        }
        assertEquals("Hello", acc)
    }

    @Test
    fun `snapshot visibleDelta emits only new suffix`() {
        assertEquals("e", StreamContentMerger.visibleDelta("H", "He"))
        assertEquals("llo", StreamContentMerger.visibleDelta("He", "Hello"))
        assertEquals("", StreamContentMerger.visibleDelta("Hello", "Hello"))
    }

    @Test
    fun `stale shorter snapshot keeps existing`() {
        assertEquals("Hello", StreamContentMerger.merge("Hello", "Hel"))
        assertEquals("", StreamContentMerger.visibleDelta("Hello", "Hel"))
    }

    @Test
    fun `empty incoming keeps existing`() {
        assertEquals("hi", StreamContentMerger.merge("hi", ""))
        assertEquals("", StreamContentMerger.visibleDelta("hi", ""))
    }

    @Test
    fun `empty existing takes incoming`() {
        assertEquals("hi", StreamContentMerger.merge("", "hi"))
        assertEquals("hi", StreamContentMerger.visibleDelta("", "hi"))
    }

    @Test
    fun `equal snapshot is replace not double append`() {
        assertEquals("Hello", StreamContentMerger.merge("Hello", "Hello"))
        assertEquals("", StreamContentMerger.visibleDelta("Hello", "Hello"))
    }

    @Test
    fun `snapshot sequence does not quadratic grow`() {
        var acc = ""
        val snaps = (1..50).map { "x".repeat(it) }
        for (s in snaps) {
            acc = StreamContentMerger.merge(acc, s)
        }
        assertEquals("x".repeat(50), acc)
        // Blind append would be 50*51/2 = 1275
        assertTrue(acc.length == 50)
    }

    @Test
    fun `safety cap truncates pathological growth`() {
        val huge = "a".repeat(StreamContentMerger.MAX_MERGED_CHARS - 10)
        val merged = StreamContentMerger.merge(huge, "bbbbbbbbbbbbbbbbbbbb")
        assertTrue(merged.contains(StreamContentMerger.TRUNCATION_MARKER))
        assertTrue(merged.length <= StreamContentMerger.MAX_MERGED_CHARS + StreamContentMerger.TRUNCATION_MARKER.length)
        // Second merge after truncation must not grow further
        val again = StreamContentMerger.merge(merged, "more")
        assertEquals(merged, again)
    }

    @Test
    fun `mergeUncapped has no cap`() {
        val a = "x".repeat(100)
        val b = "y".repeat(100)
        assertEquals(a + b, StreamContentMerger.mergeUncapped(a, b))
    }
}
