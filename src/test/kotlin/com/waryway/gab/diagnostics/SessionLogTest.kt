package com.waryway.gab.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionLogTest {

    @Test
    fun `log emits formatted lines`() {
        val lines = mutableListOf<String>()
        val log = SessionLog(onLine = { line -> lines.add(line) })
        log.system("hello")
        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("SYS"))
        assertTrue(lines[0].contains("hello"))
    }

    @Test
    fun `clear notifies listener`() {
        val lines = mutableListOf<String>()
        val log = SessionLog(onLine = { line -> lines.add(line) })
        log.system("a")
        log.clear()
        assertEquals(SessionLog.CLEAR_SENTINEL, lines.last())
    }
}