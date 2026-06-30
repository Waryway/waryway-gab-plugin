package com.waryway.gab.tools

import com.intellij.openapi.project.Project
import com.waryway.gab.settings.WarywayGabSettings

/**
 * Executes agent tool calls via JetBrains built-in MCP Server (not custom IntelliJ APIs).
 */
class GolandMcpExecutor(
    private val project: Project,
    private val settings: WarywayGabSettings = WarywayGabSettings.getInstance()
) {
    private val client = GolandMcpClient(configuredPort = settings.golandMcpIdePort)

    fun isAvailable(): Boolean = client.isAvailable()

    fun execute(name: String, argumentsJson: String): String {
        val projectPath = project.basePath.orEmpty()
        val args = injectProjectPath(argumentsJson, projectPath)
        val result = client.callTool(name, args)
        return if (result.isError) "error: ${result.text}" else result.text
    }

    companion object {
        fun injectProjectPath(argumentsJson: String, projectPath: String): String {
            if (projectPath.isBlank()) return argumentsJson.ifBlank { "{}" }
            val trimmed = argumentsJson.trim()
            if (trimmed.contains("\"projectPath\"")) return trimmed.ifBlank { "{}" }
            val escaped = ToolDefinition.jsonString(projectPath)
            return when {
                trimmed.isEmpty() || trimmed == "{}" -> """{"projectPath":$escaped}"""
                trimmed.endsWith("}") -> trimmed.dropLast(1) + """, "projectPath": $escaped}"""
                else -> trimmed
            }
        }
    }
}