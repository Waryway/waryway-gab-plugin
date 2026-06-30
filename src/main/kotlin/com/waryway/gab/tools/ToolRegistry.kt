package com.waryway.gab.tools

/**
 * Built-in IDE tools exposed to the model via Gab AI function calling.
 * Schemas inspired by mcps/goland1 reference tools.
 */
object ToolRegistry {

    val all: List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "read_file",
            description = "Read a project file by path (relative to project root or absolute). Returns numbered lines.",
            parametersJson = """{"type":"object","properties":{"file_path":{"type":"string","description":"Path to the file"},"offset":{"type":"integer","description":"1-based line to start from"},"limit":{"type":"integer","description":"Max lines to return (default 500)"}},"required":["file_path"]}"""
        ),
        ToolDefinition(
            name = "search_text",
            description = "Search for a text substring in project source files. Returns matching file paths and line numbers.",
            parametersJson = """{"type":"object","properties":{"query":{"type":"string","description":"Text to search for"},"limit":{"type":"integer","description":"Max results (default 30)"}},"required":["query"]}"""
        ),
        ToolDefinition(
            name = "get_open_files",
            description = "List file paths currently open in the IDE editor, plus the active file and selected text if any.",
            parametersJson = """{"type":"object","properties":{}}"""
        ),
        ToolDefinition(
            name = "list_directory",
            description = "List files and directories under a project-relative path (non-recursive).",
            parametersJson = """{"type":"object","properties":{"path":{"type":"string","description":"Directory path relative to project root (default: project root)"}},"required":[]}"""
        ),
        ToolDefinition(
            name = "replace_text_in_file",
            description = "Replace exact text in a file. Use for targeted edits. Saves the file automatically.",
            parametersJson = """{"type":"object","properties":{"path":{"type":"string","description":"File path relative to project root"},"old_text":{"type":"string","description":"Exact text to find"},"new_text":{"type":"string","description":"Replacement text"},"replace_all":{"type":"boolean","description":"Replace all occurrences (default true)"}},"required":["path","old_text","new_text"]}"""
        ),
        ToolDefinition(
            name = "write_file",
            description = "Create or overwrite a file with the given content. Use for new files or full rewrites.",
            parametersJson = """{"type":"object","properties":{"path":{"type":"string","description":"File path relative to project root"},"content":{"type":"string","description":"Full file content"}},"required":["path","content"]}"""
        ),
        ToolDefinition(
            name = "run_shell_command",
            description = "Run a shell command in the project directory (e.g. build.bat, gradlew.bat build, go test). Returns stdout+stderr.",
            parametersJson = """{"type":"object","properties":{"command":{"type":"string","description":"Command to run"},"timeout_seconds":{"type":"integer","description":"Timeout in seconds (default 120)"}},"required":["command"]}"""
        )
    )

    fun openAiToolsJson(): String = all.joinToString(",") { it.toOpenAiToolJson() }
}