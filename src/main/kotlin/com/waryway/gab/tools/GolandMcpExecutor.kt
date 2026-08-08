package com.waryway.gab.tools

import com.intellij.openapi.project.Project
import com.waryway.gab.settings.WarywayGabSettings

/**
 * Executes agent tool calls via JetBrains built-in MCP Server when reachable,
 * otherwise via in-process [NativeIdeToolExecutor] so commands still flow when
 * MCP Server is disabled or MCP list_tools returns 404.
 */
class GolandMcpExecutor(
    private val project: Project,
    private val settings: WarywayGabSettings = WarywayGabSettings.getInstance()
) {
    private val client = GolandMcpClient(configuredPort = settings.golandMcpIdePort)
    private val native = NativeIdeToolExecutor(project)

    /** True when HTTP MCP list_tools is reachable. */
    fun isMcpAvailable(): Boolean = client.isAvailable()

    /**
     * Tools can run either via MCP or native in-process fallback.
     * Always true inside the IDE process (native covers the core set).
     */
    fun isAvailable(): Boolean = true

    /** `mcp` | `native` — which backend will handle the next call. */
    fun backendLabel(): String = if (isMcpAvailable()) "mcp" else "native"

    /** Base URL of the cached/discovered MCP endpoint, or null if unavailable. */
    fun discoveredEndpointHint(): String? =
        client.cachedEndpointOrNull()?.baseUrl
            ?: client.discoverEndpoint(forceRefresh = false, verifyCached = false)?.baseUrl

    /** Rich probe for session log / UI (IDE up but MCP off vs fully unreachable). */
    fun probeMcp(forceRefresh: Boolean = false): GolandMcpClient.DiscoveryResult =
        client.probeDiscovery(forceRefresh = forceRefresh)

    fun execute(name: String, argumentsJson: String): String {
        val projectPath = project.basePath.orEmpty()
        val args = injectProjectPath(argumentsJson, projectPath)

        if (isMcpAvailable()) {
            val result = client.callTool(name, args)
            if (!result.isError) return result.text
            // Transport / unknown-tool: fall back to native when we can.
            if (native.supports(name) &&
                (isTransportOrMissingTool(result.text) || looksLikeUnknownTool(result.text))
            ) {
                return native.execute(name, args)
            }
            return "error: ${result.text}"
        }

        if (native.supports(name)) {
            return native.execute(name, args)
        }
        return "error: tool '$name' requires GoLand MCP Server " +
            "(Settings → Tools → MCP Server). Native fallback does not implement this tool."
    }

    private fun isTransportOrMissingTool(message: String): Boolean {
        val m = message.lowercase()
        return m.contains("not reachable") ||
            m.contains("connection refused") ||
            m.contains("mcp call failed") ||
            m.contains("connect timed out") ||
            m.contains("connection reset") ||
            m.contains("http 404") ||
            m.contains("http 502") ||
            m.contains("http 503")
    }

    private fun looksLikeUnknownTool(message: String): Boolean {
        val m = message.lowercase()
        return m.contains("unknown tool") ||
            m.contains("tool not found") ||
            m.contains("no such tool") ||
            m.contains("http 404")
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
