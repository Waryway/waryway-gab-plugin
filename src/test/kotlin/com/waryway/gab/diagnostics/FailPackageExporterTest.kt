package com.waryway.gab.diagnostics

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FailPackageExporterTest {

    @Test
    fun `sanitizeFilename strips unsafe characters`() {
        assertEquals("user_stop", FailPackageExporter.sanitizeFilename("user stop"))
        assertEquals("max_iterations", FailPackageExporter.sanitizeFilename("max/iterations??"))
        assertTrue(FailPackageExporter.sanitizeFilename("a".repeat(100)).length <= 48)
    }

    @Test
    fun `writeFailPackage creates markdown with trigger logs and paths`() {
        val tmp = Files.createTempDirectory("waryway-fail-test")
        try {
            val sessionLog = tmp.resolve("session.log")
            Files.writeString(sessionLog, "line1\n")
            val meta = FailPackageMeta(
                trigger = "user_stop",
                provider = "Local LLM",
                model = "test-model",
                skillId = "none",
                pathLabel = "Chat",
                projectBase = tmp.toString(),
                lastUserQuestion = "why is it looping?",
                lastAssistantAnswer = "partial…",
                extra = mapOf("note" to "test")
            )
            val out = FailPackageExporter.writeFailPackage(
                meta = meta,
                logLines = listOf("[12:00:00] SYS  hello", "[12:00:01] ERR  boom"),
                sessionLogPath = sessionLog
            )
            assertTrue(Files.exists(out))
            val text = Files.readString(out)
            assertTrue(text.contains("user_stop"))
            assertTrue(text.contains("why is it looping?"))
            assertTrue(text.contains("[12:00:01] ERR  boom"))
            assertTrue(text.contains("Session log file"))
            assertTrue(text.contains("test-model"))
            assertTrue(text.contains(sessionLog.toAbsolutePath().normalize().toString()) || text.contains("session.log"))
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun `logsRoot creates directory under project base`() {
        val tmp = Files.createTempDirectory("waryway-logs-root")
        try {
            val root = FailPackageExporter.logsRoot(tmp.toString())
            assertTrue(Files.isDirectory(root))
            assertTrue(root.toString().contains(".waryway-gab"))
            assertTrue(root.toString().endsWith("logs") || root.fileName.toString() == "logs")
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }
}
