package com.waryway.gab.ui

import com.waryway.gab.model.ContextAttachment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Unit tests for [ContextAttachment.chipLabel] / [ContextAttachment.chipTooltip] (wo-03-02). */
class AttachmentChipLabelTest {

    @Test
    fun `chipLabel prefers non-blank displayName`() {
        val att = ContextAttachment(
            type = ContextAttachment.Type.FILE,
            path = "src/deep/main.go",
            displayName = "main.go"
        )
        assertEquals("main.go", att.chipLabel())
    }

    @Test
    fun `chipLabel blank displayName falls back to path basename forward slash`() {
        val att = ContextAttachment(
            type = ContextAttachment.Type.FILE,
            path = "apps/retronium/main.go",
            displayName = "   "
        )
        assertEquals("main.go", att.chipLabel())
    }

    @Test
    fun `chipLabel blank displayName falls back to path basename backslash`() {
        val att = ContextAttachment(
            type = ContextAttachment.Type.FILE,
            path = """C:\proj\pkg\util.kt""",
            displayName = ""
        )
        assertEquals("util.kt", att.chipLabel())
    }

    @Test
    fun `chipLabel never empty when path and displayName blank`() {
        val att = ContextAttachment(
            type = ContextAttachment.Type.FILE,
            path = null,
            displayName = "  "
        )
        val label = att.chipLabel()
        assertTrue(label.isNotBlank())
        assertEquals("(attached file)", label)
    }

    @Test
    fun `chipTooltip prefers full path`() {
        val att = ContextAttachment(
            type = ContextAttachment.Type.FILE,
            path = "src/a.kt",
            displayName = "a.kt"
        )
        assertEquals("src/a.kt", att.chipTooltip())
    }

    @Test
    fun `chipTooltip falls back to displayName then label`() {
        val withName = ContextAttachment(
            type = ContextAttachment.Type.SELECTION,
            path = null,
            displayName = "selection"
        )
        assertEquals("selection", withName.chipTooltip())

        val empty = ContextAttachment(
            type = ContextAttachment.Type.FILE,
            path = "  ",
            displayName = ""
        )
        assertEquals("(attached file)", empty.chipTooltip())
    }
}
