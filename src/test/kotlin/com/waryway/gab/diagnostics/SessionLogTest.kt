package com.waryway.gab.diagnostics

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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

    @Test
    fun `attachLogFile writes durable lines and exposes path`() {
        val tmp = Files.createTempDirectory("waryway-session-log")
        try {
            val file = tmp.resolve("session.log")
            val log = SessionLog()
            val path = log.attachLogFile(file)
            assertNotNull(log.logFilePath())
            assertEquals(path, log.logFilePath())
            log.system("persisted line")
            log.error("boom")
            val text = Files.readString(file)
            assertTrue(text.contains("persisted line"))
            assertTrue(text.contains("boom"))
            assertTrue(text.contains("SYS") || text.contains("ERR"))
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun `writeSnapshotTo dumps memory buffer`() {
        val tmp = Files.createTempDirectory("waryway-snapshot")
        try {
            val log = SessionLog()
            log.http("GET /x")
            val out = log.writeSnapshotTo(tmp.resolve("snap.log"))
            val text = Files.readString(out)
            assertTrue(text.contains("GET /x"))
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }
}
