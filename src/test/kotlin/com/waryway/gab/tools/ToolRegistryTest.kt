package com.waryway.gab.tools

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolRegistryTest {

    @Test
    fun `parseMcpToolSchema reads goland1 shape`() {
        val json = """
            {
              "name": "read_file",
              "description": "Reads a file",
              "inputSchema": {
                "type": "object",
                "properties": {"file_path": {"type": "string"}},
                "required": ["file_path"]
              }
            }
        """.trimIndent()
        val tool = ToolRegistry.parseMcpToolSchema(json)
        assertTrue(tool != null)
        assertEquals("read_file", tool!!.name)
        assertContains(tool.parametersJson, "file_path")
    }

    @Test
    fun `parseMcpListToolsResponse extracts tools array`() {
        val body = """
            {"tools":[
              {"name":"read_file","description":"Read","inputSchema":{"type":"object","properties":{}}},
              {"name":"build_project","description":"Build","inputSchema":{"type":"object","properties":{}}}
            ]}
        """.trimIndent()
        val tools = ToolRegistry.parseMcpListToolsResponse(body)
        assertEquals(2, tools.size)
        assertTrue(tools.any { it.name == "read_file" })
        assertTrue(tools.any { it.name == "build_project" })
    }

    @Test
    fun `loadBundledTools includes read_file when resources present`() {
        val tools = ToolRegistry.loadBundledTools()
        assertTrue(tools.isNotEmpty())
        assertTrue(tools.any { it.name == "read_file" })
    }
}