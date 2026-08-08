package com.waryway.gab.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeIdeToolExecutorTest {

    @Test
    fun `JsonArgs parses string int and bool`() {
        val args = NativeIdeToolExecutor.JsonArgs(
            """{"command":"build.bat","maxDepth":3,"overwrite":true,"file_path":"src\\a.kt"}"""
        )
        assertEquals("build.bat", args.string("command"))
        assertEquals(3, args.int("maxDepth"))
        assertEquals(true, args.bool("overwrite"))
        assertEquals("src\\a.kt", args.string("file_path"))
        assertNull(args.string("missing"))
    }

    @Test
    fun `JsonArgs unescapes common sequences`() {
        val args = NativeIdeToolExecutor.JsonArgs(
            """{"oldText":"line1\nline2","newText":"a\"b"}"""
        )
        assertEquals("line1\nline2", args.string("oldText"))
        assertEquals("a\"b", args.string("newText"))
    }

    @Test
    fun `supported tools include command and file ops`() {
        assertTrue(NativeIdeToolExecutor.SUPPORTED.contains("execute_terminal_command"))
        assertTrue(NativeIdeToolExecutor.SUPPORTED.contains("read_file"))
        assertTrue(NativeIdeToolExecutor.SUPPORTED.contains("replace_text_in_file"))
        assertFalse(NativeIdeToolExecutor.SUPPORTED.contains("apply_patch"))
    }
}
