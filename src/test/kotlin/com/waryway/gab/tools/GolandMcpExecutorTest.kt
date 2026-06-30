package com.waryway.gab.tools

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals


class GolandMcpExecutorTest {

    @Test
    fun `injectProjectPath adds field to empty object`() {
        val result = GolandMcpExecutor.injectProjectPath("{}", "C:/proj")
        assertContains(result, """"projectPath": "C:/proj"""")
    }

    @Test
    fun `injectProjectPath appends to existing args`() {
        val result = GolandMcpExecutor.injectProjectPath(
            """{"file_path":"src/main.kt"}""",
            "C:/proj"
        )
        assertContains(result, "file_path")
        assertContains(result, "projectPath")
    }

    @Test
    fun `injectProjectPath skips when already present`() {
        val input = """{"projectPath":"C:/existing","file_path":"a.kt"}"""
        assertEquals(input, GolandMcpExecutor.injectProjectPath(input, "C:/proj"))
    }
}