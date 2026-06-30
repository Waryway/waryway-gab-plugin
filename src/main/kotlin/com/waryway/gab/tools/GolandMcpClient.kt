package com.waryway.gab.tools

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * HTTP client for JetBrains IDE built-in MCP Server (Settings → Tools → MCP Server).
 *
 * API (same as @jetbrains/mcp-jetbrains proxy):
 * - GET  http://127.0.0.1:{port}/api/mcp/list_tools
 * - POST http://127.0.0.1:{port}/api/mcp/{tool_name}  body: JSON args
 *
 * Port: [configuredPort] if set, else IDE_PORT env, else scan 63342–63352.
 */
class GolandMcpClient(
    private val configuredPort: Int = 0,
    private val host: String = "127.0.0.1"
) {
    data class Endpoint(val baseUrl: String) {
        val listToolsUrl: String get() = "$baseUrl/mcp/list_tools"
        fun callToolUrl(name: String): String = "$baseUrl/mcp/$name"
    }

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build()

    @Volatile
    private var cachedEndpoint: Endpoint? = null

    fun discoverEndpoint(forceRefresh: Boolean = false): Endpoint? {
        if (!forceRefresh) {
            cachedEndpoint?.let { ep ->
                if (ping(ep)) return ep
            }
        }

        val envPort = System.getenv("IDE_PORT")?.toIntOrNull()
        val portsToTry = buildList {
            if (configuredPort > 0) add(configuredPort)
            envPort?.let { if (it !in this) add(it) }
            for (p in PORT_RANGE) if (p !in this) add(p)
        }

        for (port in portsToTry) {
            val ep = Endpoint("http://$host:$port/api")
            if (ping(ep)) {
                cachedEndpoint = ep
                return ep
            }
        }
        return null
    }

    fun isAvailable(): Boolean = discoverEndpoint() != null

    fun listTools(): List<ToolDefinition> {
        val ep = discoverEndpoint() ?: return emptyList()
        val body = get(ep.listToolsUrl) ?: return emptyList()
        return ToolRegistry.parseMcpListToolsResponse(body)
    }

    fun callTool(name: String, argumentsJson: String): GolandMcpResult {
        val ep = discoverEndpoint(forceRefresh = true)
            ?: return GolandMcpResult.error(
                "GoLand MCP Server not reachable. Enable it in Settings → Tools → MCP Server."
            )

        val request = HttpRequest.newBuilder()
            .uri(URI.create(ep.callToolUrl(name)))
            .timeout(Duration.ofSeconds(120))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(argumentsJson.ifBlank { "{}" }))
            .build()

        return try {
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                return GolandMcpResult.error("MCP HTTP ${response.statusCode()}: ${response.body().take(500)}")
            }
            parseCallResponse(response.body())
        } catch (e: Exception) {
            GolandMcpResult.error("MCP call failed: ${e.message}")
        }
    }

    private fun ping(ep: Endpoint): Boolean = try {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(ep.listToolsUrl))
            .timeout(Duration.ofMillis(800))
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        response.statusCode() in 200..299 && response.body().isNotBlank()
    } catch (_: Exception) {
        false
    }

    private fun get(url: String): String? = try {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() in 200..299) response.body() else null
    } catch (_: Exception) {
        null
    }

    private fun parseCallResponse(body: String): GolandMcpResult {
        val status = extractJsonStringField(body, "status")
        val error = extractJsonStringField(body, "error")
        return when {
            !error.isNullOrBlank() -> GolandMcpResult.error(error)
            !status.isNullOrBlank() -> GolandMcpResult.ok(status)
            body.isBlank() -> GolandMcpResult.ok("(empty response)")
            else -> GolandMcpResult.ok(body.take(16_000))
        }
    }

    private fun extractJsonStringField(json: String, field: String): String? {
        val pattern = Regex("""\"$field\"\s*:\s*(null|"((?:[^"\\]|\\.)*)")""")
        val match = pattern.find(json) ?: return null
        val raw = match.groupValues[1]
        if (raw == "null") return null
        return raw
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }

    companion object {
        private val PORT_RANGE = 63342..63352
    }
}

data class GolandMcpResult(val text: String, val isError: Boolean) {
    companion object {
        fun ok(text: String) = GolandMcpResult(text, isError = false)
        fun error(text: String) = GolandMcpResult(text, isError = true)
    }
}