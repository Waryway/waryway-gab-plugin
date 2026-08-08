package com.waryway.gab.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandOutputUiTest {

    @Test
    fun `headerSummary includes command exit and line count`() {
        val out = "exit=0\nhello\nworld\n"
        val h = CommandOutputUi.headerSummary("dir /b", out)
        assertContains(h, "▸ cmd: dir /b")
        assertContains(h, "exit=0")
        assertContains(h, "3 lines") // exit=0 + hello + world
    }

    @Test
    fun `headerSummary handles empty output`() {
        val h = CommandOutputUi.headerSummary("echo hi", "")
        assertContains(h, "echo hi")
        assertContains(h, "no output")
    }

    @Test
    fun `headerSummary truncates long commands`() {
        val long = "x".repeat(100)
        val h = CommandOutputUi.headerSummary(long, "exit=1\nok")
        assertTrue(h.contains("…"))
        assertTrue(h.length < 100 + 40)
    }

    @Test
    fun `extractExitCode reads first line`() {
        assertEquals(0, CommandOutputUi.extractExitCode("exit=0\nbody"))
        assertEquals(1, CommandOutputUi.extractExitCode("exit=1"))
        assertNull(CommandOutputUi.extractExitCode("ok\nexit=0"))
    }

    @Test
    fun `bodyForUi caps long output`() {
        val body = "a".repeat(100)
        val capped = CommandOutputUi.bodyForUi(body, maxChars = 40)
        assertTrue(capped.startsWith("a".repeat(40)))
        assertContains(capped, "truncated")
    }

    @Test
    fun `bodyForUi empty becomes placeholder`() {
        assertEquals("(no output)", CommandOutputUi.bodyForUi("   "))
    }
}
