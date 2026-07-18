package com.waryway.gab.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Unit tests for pure [AttachmentPayload] path + message builders. */
class AttachmentPayloadTest {

    // --- resolveAttachmentPath / relativization ---

    @Test
    fun `resolve prefers non-blank relativeFromVfs`() {
        val path = AttachmentPayload.resolveAttachmentPath(
            absolutePath = """C:\proj\src\main.go""",
            projectBasePath = """C:\proj""",
            relativeFromVfs = "src/main.go",
            fallbackName = "main.go"
        )
        assertEquals("src/main.go", path)
    }

    @Test
    fun `resolve strips project base prefix when under base`() {
        val path = AttachmentPayload.resolveAttachmentPath(
            absolutePath = """C:\Users\dev\stack\apps\retronium\main.go""",
            projectBasePath = """C:\Users\dev\stack""",
            relativeFromVfs = null,
            fallbackName = "main.go"
        )
        assertEquals("apps/retronium/main.go", path)
    }

    @Test
    fun `resolve keeps absolute path outside project base`() {
        val abs = """C:\Outside\other\file.txt"""
        val path = AttachmentPayload.resolveAttachmentPath(
            absolutePath = abs,
            projectBasePath = """C:\Users\dev\stack""",
            relativeFromVfs = null,
            fallbackName = "file.txt"
        )
        assertEquals(AttachmentPayload.normalizeSeparators(abs), path)
        assertTrue(path.startsWith("C:/Outside") || path.contains("Outside"))
    }

    @Test
    fun `resolve normalizes backslashes to forward slashes`() {
        val path = AttachmentPayload.resolveAttachmentPath(
            absolutePath = """C:\proj\a\b.kt""",
            projectBasePath = """C:\proj""",
            relativeFromVfs = null,
            fallbackName = "b.kt"
        )
        assertEquals("a/b.kt", path)
        assertFalse(path.contains('\\'))
    }

    @Test
    fun `resolve never returns empty — fallbackName then unknown`() {
        assertEquals(
            "leaf.kt",
            AttachmentPayload.resolveAttachmentPath(null, null, null, "leaf.kt")
        )
        assertEquals(
            "unknown",
            AttachmentPayload.resolveAttachmentPath(null, null, "  ", "  ")
        )
        assertEquals(
            "unknown",
            AttachmentPayload.resolveAttachmentPath("", "", "", "")
        )
    }

    @Test
    fun `resolve blank relativeFromVfs falls through to absolute strip`() {
        val path = AttachmentPayload.resolveAttachmentPath(
            absolutePath = "/home/u/proj/pkg/x.go",
            projectBasePath = "/home/u/proj",
            relativeFromVfs = "   ",
            fallbackName = "x.go"
        )
        assertEquals("pkg/x.go", path)
    }

    // --- formatAttachmentBlock ---

    @Test
    fun `format with content present has header and fenced body`() {
        val block = AttachmentPayload.formatAttachmentBlock("src/a.kt", "fun main() {}")
        assertTrue(block.startsWith("[Attached: src/a.kt]"))
        assertTrue(block.contains("```\nfun main() {}\n```"))
        assertFalse(block.contains(AttachmentPayload.CONTENT_UNAVAILABLE))
    }

    @Test
    fun `format with null content has read_file instruction and no empty fence`() {
        val block = AttachmentPayload.formatAttachmentBlock("bin/app.png", null)
        assertTrue(block.startsWith("[Attached: bin/app.png]"))
        assertTrue(block.contains(AttachmentPayload.CONTENT_UNAVAILABLE))
        assertTrue(block.contains("read_file"))
        assertFalse(block.contains("```"))
    }

    @Test
    fun `format with blank content treated as unavailable`() {
        val block = AttachmentPayload.formatAttachmentBlock("empty.txt", "   \n  ")
        assertTrue(block.contains(AttachmentPayload.CONTENT_UNAVAILABLE))
        assertFalse(block.contains("```"))
    }

    @Test
    fun `format blank path label becomes unknown`() {
        val block = AttachmentPayload.formatAttachmentBlock("  ", null)
        assertTrue(block.startsWith("[Attached: unknown]"))
        assertTrue(block.contains(AttachmentPayload.CONTENT_UNAVAILABLE))
    }

    // --- buildMessagePayload ---

    @Test
    fun `buildMessagePayload empty attachments returns bare user text`() {
        assertEquals("hello", AttachmentPayload.buildMessagePayload("hello", emptyList()))
    }

    @Test
    fun `buildMessagePayload injects workspace context with content and null cases`() {
        val payload = AttachmentPayload.buildMessagePayload(
            userText = "Explain this",
            attachments = listOf(
                "src/ok.go" to "package main",
                "img.png" to null
            )
        )
        assertTrue(payload.startsWith("Explain this"))
        assertTrue(payload.contains("--- Workspace context ---"))
        assertTrue(payload.contains("[Attached: src/ok.go]"))
        assertTrue(payload.contains("```\npackage main\n```"))
        assertTrue(payload.contains("[Attached: img.png]"))
        assertTrue(payload.contains(AttachmentPayload.CONTENT_UNAVAILABLE))
        // null content must not produce empty fences
        val imgSection = payload.substringAfter("[Attached: img.png]")
        assertFalse(imgSection.contains("```\n```") || imgSection.startsWith("\n```\n```"))
    }

    // --- preview / binary helpers ---

    @Test
    fun `looksBinary detects null bytes`() {
        assertTrue(AttachmentPayload.looksBinary(byteArrayOf(1, 2, 0, 3)))
        assertFalse(AttachmentPayload.looksBinary("hello".toByteArray()))
        assertFalse(AttachmentPayload.looksBinary(byteArrayOf()))
    }

    @Test
    fun `previewTextFromBytes returns text or null for binary`() {
        assertEquals("hello", AttachmentPayload.previewTextFromBytes("hello".toByteArray()))
        assertNull(AttachmentPayload.previewTextFromBytes(byteArrayOf(0x89.toByte(), 0, 0x50)))
        assertNull(AttachmentPayload.previewTextFromBytes(byteArrayOf()))
    }

    @Test
    fun `previewTextFromBytes truncates with marker`() {
        val long = "x".repeat(100)
        val preview = AttachmentPayload.previewTextFromBytes(long.toByteArray(), maxChars = 20)
        requireNotNull(preview)
        assertTrue(preview.startsWith("x".repeat(20)))
        assertTrue(preview.contains("truncated"))
    }
}
