package com.waryway.gab.skills

import kotlin.test.Test
import kotlin.test.assertEquals

class InputNormalizerTest {

    @Test
    fun `removes articles`() {
        val input = "Fix the bug in the handler for a null pointer"
        val result = InputNormalizer.normalize(input)
        assertEquals("Fix bug in handler for null pointer", result)
    }

    @Test
    fun `removes i figured it out phrasing`() {
        val input = "I figured it out - we need to change the timeout"
        val result = InputNormalizer.normalize(input)
        // Filler phrase is stripped; leading dash separator after "out" is left as-is.
        assertEquals("- we need to change timeout", result)
    }

    @Test
    fun `removes stacked filler`() {
        val input = "I think basically we should refactor the service"
        val result = InputNormalizer.normalize(input)
        assertEquals("we should refactor service", result)
    }

    @Test
    fun `preserves substantive content`() {
        val input = "Add retry logic with exponential backoff to GabClient"
        assertEquals(input, InputNormalizer.normalize(input))
    }

    @Test
    fun `handles multiline input`() {
        val input = "Line one\n\nI believe line two has the error"
        val result = InputNormalizer.normalize(input)
        assertEquals("Line one\n\nline two has error", result)
    }
}