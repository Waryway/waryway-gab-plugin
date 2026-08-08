package com.waryway.gab.ui

import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for pure path / uri-list helpers extracted from [FileDropUtil].
 * No Project / DnDEvent / VFS required.
 */
class FileDropUtilTest {

    @Test
    fun `parsePathLines splits lines and strips quotes`() {
        val paths = parsePathLines(
            """
            C:\proj\a.kt
            "C:\proj\b.kt"
            'C:\proj\c.kt'
            
            /home/user/d.go
            """.trimIndent()
        )
        assertEquals(
            listOf(
                """C:\proj\a.kt""",
                """C:\proj\b.kt""",
                """C:\proj\c.kt""",
                "/home/user/d.go"
            ),
            paths
        )
    }

    @Test
    fun `parsePathLines empty blank and whitespace-only lines do not throw`() {
        assertTrue(parsePathLines("").isEmpty())
        assertTrue(parsePathLines("   \n  \n").isEmpty())
        assertTrue(parsePathLines("\t\n\r\n").isEmpty())
        assertEquals(listOf("only"), parsePathLines("\n  only  \n\n"))
    }

    @Test
    fun `parsePathLines single path without newline`() {
        assertEquals(listOf("""C:\repo\main.go"""), parsePathLines("""C:\repo\main.go"""))
        assertEquals(listOf("/tmp/x"), parsePathLines("\"/tmp/x\""))
    }

    @Test
    fun `parseUriList converts file URIs and skips comments and non-file schemes`() {
        val paths = parseUriList(
            """
            # comment
            file:///C:/Users/test/file.kt
            file:///home/user/x.go
            https://example.com/skip
            http://evil.example/path
            /bare/path.txt
            """.trimIndent()
        )
        assertTrue(paths.any { it.replace('\\', '/').endsWith("Users/test/file.kt") || it.contains("file.kt") })
        assertTrue(paths.any { it.replace('\\', '/').endsWith("home/user/x.go") || it.contains("x.go") })
        assertTrue(paths.contains("/bare/path.txt"))
        assertTrue(paths.none { it.contains("example.com") })
        assertTrue(paths.none { it.contains("evil") })
    }

    @Test
    fun `parseUriList empty and garbage inputs do not throw`() {
        assertTrue(parseUriList("").isEmpty())
        assertTrue(parseUriList("   \n# only comment\n").isEmpty())
        assertTrue(parseUriList("not-a-uri-with-scheme://x").isEmpty() || parseUriList("ftp://host/f").isEmpty())
        assertTrue(parseUriList("ftp://host/file.txt").isEmpty())
        // malformed file URI should not throw
        val result = parseUriList("file://")
        assertTrue(result.isEmpty() || result.all { it.isNotBlank() })
    }

    @Test
    fun `uriLineToLocalPath handles file and rejects http`() {
        val win = uriLineToLocalPath("file:///C:/tmp/a.txt")
        assertTrue(
            win != null && (
                win.replace('\\', '/').contains("tmp/a.txt") ||
                    win.contains("a.txt")
                )
        )

        assertNull(uriLineToLocalPath("https://example.com/a.txt"))
        assertNull(uriLineToLocalPath("http://example.com/a.txt"))
        assertEquals("/local/path", uriLineToLocalPath("/local/path"))
        assertNull(uriLineToLocalPath(""))
        assertNull(uriLineToLocalPath("   "))
    }

    @Test
    fun `uriLineToLocalPath strips surrounding quotes on bare paths`() {
        assertEquals("""C:\proj\z.go""", uriLineToLocalPath("\"C:\\proj\\z.go\""))
    }

    @Test
    fun `isFileishMime recognizes Project View and OS drop mimes`() {
        assertTrue(isFileishMime("text/uri-list"))
        assertTrue(isFileishMime("text/uri-list; class=java.lang.String"))
        assertTrue(isFileishMime("application/x-java-file-list"))
        assertTrue(isFileishMime("application/x-java-jvm-local-objectref;class=com.intellij.ide.dnd.TransferableWrapper"))
        assertTrue(isFileishMime("text/plain; charset=unicode"))
        assertFalse(isFileishMime("image/png"))
        assertFalse(isFileishMime(null))
        assertFalse(isFileishMime(""))
        assertFalse(isFileishMime("   "))
    }

    @Test
    fun `collectPathStringsFromList handles File Path String and ignores null garbage`() {
        val items: List<Any?> = listOf(
            File("""C:\proj\a.go"""),
            Path.of("relative", "b.kt"),
            """  "C:\proj\c.go"  """,
            null,
            42,
            ""
        )
        val paths = collectPathStringsFromList(items)
        assertTrue(paths.any { it.replace('\\', '/').endsWith("proj/a.go") || it.contains("a.go") })
        assertTrue(paths.any { it.replace('\\', '/').contains("relative") && it.contains("b.kt") })
        assertTrue(paths.any { it.replace('\\', '/').endsWith("proj/c.go") || it.contains("c.go") })
        assertTrue(paths.none { it == "42" })
        assertTrue(paths.none { it.isBlank() })
    }

    @Test
    fun `collectPathStringsFromList empty and null do not throw`() {
        assertTrue(collectPathStringsFromList(null).isEmpty())
        assertTrue(collectPathStringsFromList(emptyList<Any>()).isEmpty())
        assertTrue(collectPathStringsFromList(listOf(null, null)).isEmpty())
    }

    // --- What remains manual (IDE / VFS) ---
    // canAccept(DnDEvent), extractFiles with live Project View TransferableWrapper,
    // FileCopyPasteUtil, LocalFileSystem resolution, and panel DnD wiring require full IDE;
    // see ide-attach-live-notes.md (R2) / ide-attach-notes.md (R1).
}
