package com.waryway.gab.tools

import com.intellij.openapi.project.Project
import com.waryway.gab.settings.WarywayGabSettings
import java.nio.charset.StandardCharsets

/**
 * OpenAI-compatible tool schemas from JetBrains GoLand MCP Server.
 * Live list from GET /api/mcp/list_tools when available; otherwise bundled goland-mcp tool JSON schemas.
 */
object ToolRegistry {

    private const val BUNDLED_PREFIX = "/goland-mcp/tools/"

    @Volatile
    private var cachedToolsKey: String? = null

    @Volatile
    private var cachedTools: List<ToolDefinition>? = null

    fun toolsForProject(project: Project): List<ToolDefinition> {
        val cacheKey = project.basePath.orEmpty()
        if (cachedToolsKey == cacheKey) {
            cachedTools?.let { return it }
        }
        val settings = WarywayGabSettings.getInstance()
        val client = GolandMcpClient(configuredPort = settings.golandMcpIdePort)
        val live = client.listTools()
        val resolved = if (live.isNotEmpty()) live else loadBundledTools()
        cachedToolsKey = cacheKey
        cachedTools = resolved
        return resolved
    }

    fun openAiToolsJson(project: Project): String =
        toolsForProject(project).joinToString(",") { it.toOpenAiToolJson() }

    fun invalidateCache() {
        cachedToolsKey = null
        cachedTools = null
    }

    fun loadBundledTools(): List<ToolDefinition> {
        val classLoader = ToolRegistry::class.java.classLoader
        val url = classLoader.getResource(BUNDLED_PREFIX)
            ?: return fallbackCoreTools()

        return try {
            val dirUrl = java.net.URL(url.toString().removeSuffix("/") + "/")
            val connection = dirUrl.openConnection()
            val entries = when (connection) {
                is java.net.JarURLConnection -> {
                    connection.jarFile.entries().asSequence()
                        .filter { !it.isDirectory && it.name.startsWith("goland-mcp/tools/") && it.name.endsWith(".json") }
                        .map { it.name.substringAfter("goland-mcp/tools/") }
                        .toList()
                }
                else -> {
                    val path = dirUrl.toURI()
                    java.nio.file.Files.walk(java.nio.file.Paths.get(path))
                        .filter { java.nio.file.Files.isRegularFile(it) && it.toString().endsWith(".json") }
                        .map { it.fileName.toString() }
                        .toList()
                }
            }
            entries.sorted().mapNotNull { fileName ->
                classLoader.getResourceAsStream("$BUNDLED_PREFIX$fileName")?.use { stream ->
                    parseMcpToolSchema(String(stream.readBytes(), StandardCharsets.UTF_8))
                }
            }
        } catch (_: Exception) {
            fallbackCoreTools()
        }
    }

    fun parseMcpListToolsResponse(body: String): List<ToolDefinition> {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return emptyList()

        val toolsArray = extractToolsArray(trimmed) ?: return emptyList()
        return parseToolsArray(toolsArray)
    }

    fun parseMcpToolSchema(json: String): ToolDefinition? {
        val name = extractJsonStringField(json, "name") ?: return null
        val description = extractJsonStringField(json, "description")?.trim().orEmpty()
            .ifEmpty { name }
        val inputSchema = extractInputSchema(json) ?: """{"type":"object","properties":{}}"""
        return ToolDefinition(name, description, inputSchema)
    }

    private fun fallbackCoreTools(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "read_file",
            description = "Read a project file by path. Returns numbered lines.",
            parametersJson = """{"type":"object","properties":{"file_path":{"type":"string"},"projectPath":{"type":"string"}},"required":["file_path"]}"""
        ),
        ToolDefinition(
            name = "search_in_files_by_text",
            description = "Search for text in project files.",
            parametersJson = """{"type":"object","properties":{"searchText":{"type":"string"},"projectPath":{"type":"string"}},"required":["searchText"]}"""
        ),
        ToolDefinition(
            name = "replace_text_in_file",
            description = "Replace exact text in a file.",
            parametersJson = """{"type":"object","properties":{"pathInProject":{"type":"string"},"oldText":{"type":"string"},"newText":{"type":"string"},"projectPath":{"type":"string"}},"required":["pathInProject","oldText","newText"]}"""
        ),
        ToolDefinition(
            name = "execute_terminal_command",
            description = "Run a shell command in the project directory.",
            parametersJson = """{"type":"object","properties":{"command":{"type":"string"},"projectPath":{"type":"string"}},"required":["command"]}"""
        ),
        ToolDefinition(
            name = "get_all_open_file_paths",
            description = "List paths of files open in the IDE editor.",
            parametersJson = """{"type":"object","properties":{"projectPath":{"type":"string"}}}"""
        )
    )

    private fun extractToolsArray(body: String): String? {
        val keyMatch = Regex("\"tools\"\\s*:\\s*(\\[)").find(body)
        if (keyMatch != null) {
            val start = keyMatch.range.last
            return extractBracketed(body, start, '[', ']')
        }
        if (body.startsWith("[")) {
            return extractBracketed(body, 0, '[', ']')
        }
        return null
    }

    private fun parseToolsArray(arrayJson: String): List<ToolDefinition> {
        val objects = splitTopLevelObjects(arrayJson)
        return objects.mapNotNull { parseMcpToolSchema(it) }
    }

    private fun splitTopLevelObjects(arrayJson: String): List<String> {
        val inner = arrayJson.trim().removePrefix("[").removeSuffix("]").trim()
        if (inner.isEmpty()) return emptyList()

        val result = mutableListOf<String>()
        var depth = 0
        var start = -1
        var inString = false
        var i = 0
        while (i < inner.length) {
            val c = inner[i]
            when {
                c == '"' && (i == 0 || inner[i - 1] != '\\') -> inString = !inString
                !inString && c == '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        result.add(inner.substring(start, i + 1))
                        start = -1
                    }
                }
            }
            i++
        }
        return result
    }

    private fun extractInputSchema(json: String): String? {
        val marker = Regex("\"inputSchema\"\\s*:\\s*(\\{)")
        val match = marker.find(json) ?: return null
        val start = match.range.last
        return extractBracketed(json, start, '{', '}')
    }

    private fun extractBracketed(source: String, openIndex: Int, open: Char, close: Char): String? {
        if (openIndex < 0 || openIndex >= source.length || source[openIndex] != open) return null
        var depth = 0
        var inString = false
        for (i in openIndex until source.length) {
            val c = source[i]
            when {
                c == '"' && (i == 0 || source[i - 1] != '\\') -> inString = !inString
                !inString && c == open -> depth++
                !inString && c == close -> {
                    depth--
                    if (depth == 0) return source.substring(openIndex, i + 1)
                }
            }
        }
        return null
    }

    private fun extractJsonStringField(json: String, field: String): String? {
        val pattern = Regex("""\"$field\"\s*:\s*"((?:[^"\\]|\\.)*)"""")
        val match = pattern.find(json) ?: return null
        return match.groupValues[1]
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }
}