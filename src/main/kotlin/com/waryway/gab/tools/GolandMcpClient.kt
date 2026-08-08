package com.waryway.gab.tools

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/**
 * HTTP client for JetBrains IDE built-in MCP Server (Settings → Tools → MCP Server).
 *
 * API (same as @jetbrains/mcp-jetbrains proxy):
 * - GET  http://127.0.0.1:{port}/api/mcp/list_tools
 * - POST http://127.0.0.1:{port}/api/mcp/{tool_name}  body: JSON args
 *
 * Port: [configuredPort] if set, else IDE_PORT env, else scan 63342–63352.
 *
 * Discovery is process-wide: once an endpoint is found (or a miss is recorded), later
 * [GolandMcpClient] instances reuse that result so each agent Send does not re-scan ports.
 */
class GolandMcpClient(
    private val configuredPort: Int = 0,
    private val host: String = "127.0.0.1",
    /** Test seam: override liveness check. Null = real HTTP ping. */
    private val pingFn: ((Endpoint) -> Boolean)? = null,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val envPortProvider: () -> Int? = { System.getenv("IDE_PORT")?.toIntOrNull() }
) {
    data class Endpoint(val baseUrl: String) {
        val listToolsUrl: String get() = "$baseUrl/mcp/list_tools"
        val aboutUrl: String get() = "$baseUrl/about"
        fun callToolUrl(name: String): String = "$baseUrl/mcp/$name"

        /** Extract port from `http://host:port/api` style base URL. */
        fun portOrNull(): Int? {
            val m = Regex(""":(\d+)/api/?$""").find(baseUrl) ?: return null
            return m.groupValues[1].toIntOrNull()
        }
    }

    /**
     * Why discovery failed or succeeded — for UI / session log (not just a boolean).
     *
     * Common real-world case: built-in server answers [Endpoint.aboutUrl] (IDE up)
     * but [Endpoint.listToolsUrl] is HTTP 404 because **Settings → Tools → MCP Server**
     * is not enabled.
     */
    enum class ProbeStatus {
        AVAILABLE,
        IDE_UP_MCP_OFF,
        UNREACHABLE
    }

    data class DiscoveryResult(
        val status: ProbeStatus,
        val endpoint: Endpoint? = null,
        val detail: String? = null
    )

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build()

    private val cacheKey: String = "$host|${configuredPort.coerceAtLeast(0)}"

    /**
     * Resolve the MCP HTTP endpoint.
     *
     * @param forceRefresh when true, ignore positive/negative cache and re-scan.
     * @param verifyCached when true (default for availability checks), re-ping a cached
     *   positive hit before trusting it. Tool calls use [callTool] which skips verify
     *   unless the call fails.
     */
    fun discoverEndpoint(forceRefresh: Boolean = false, verifyCached: Boolean = true): Endpoint? {
        if (!forceRefresh) {
            sharedCache.get()?.takeIf { it.key == cacheKey }?.let { hit ->
                when (hit) {
                    is CacheEntry.Hit -> {
                        if (!verifyCached || ping(hit.endpoint)) return hit.endpoint
                        // Stale hit — drop and rescan below.
                        sharedCache.compareAndSet(hit, null)
                    }
                    is CacheEntry.Miss -> {
                        val age = clockMs() - hit.atMs
                        if (age in 0 until NEGATIVE_CACHE_MS) return null
                        // Expired miss — rescan below.
                        sharedCache.compareAndSet(hit, null)
                    }
                }
            }
        }

        val envPort = envPortProvider()
        val preferredPort = sharedLastGoodPort.get()
        val portsToTry = buildList {
            if (configuredPort > 0) add(configuredPort)
            envPort?.let { if (it !in this) add(it) }
            preferredPort?.let { if (it !in this) add(it) }
            for (p in PORT_RANGE) if (p !in this) add(p)
        }

        for (port in portsToTry) {
            val ep = Endpoint("http://$host:$port/api")
            if (ping(ep)) {
                sharedCache.set(CacheEntry.Hit(cacheKey, ep))
                sharedLastGoodPort.set(port)
                return ep
            }
        }

        sharedCache.set(CacheEntry.Miss(cacheKey, clockMs()))
        return null
    }

    fun isAvailable(): Boolean = discoverEndpoint(verifyCached = true) != null

    /** Last discovered endpoint for this cache key, if any (no network). */
    fun cachedEndpointOrNull(): Endpoint? =
        (sharedCache.get() as? CacheEntry.Hit)?.takeIf { it.key == cacheKey }?.endpoint

    /**
     * Human-oriented discovery with IDE-up / MCP-off distinction.
     * Does not change the positive/negative cache unless a working MCP endpoint is found.
     */
    fun probeDiscovery(forceRefresh: Boolean = false): DiscoveryResult {
        val ep = discoverEndpoint(forceRefresh = forceRefresh, verifyCached = true)
        if (ep != null) {
            return DiscoveryResult(ProbeStatus.AVAILABLE, ep, "MCP tools at ${ep.baseUrl}")
        }

        // Scan for /api/about so we can say "enable MCP Server" instead of "IDE offline".
        val portsToTry = buildList {
            if (configuredPort > 0) add(configuredPort)
            envPortProvider()?.let { if (it !in this) add(it) }
            sharedLastGoodPort.get()?.let { if (it !in this) add(it) }
            for (p in PORT_RANGE) if (p !in this) add(p)
        }
        for (port in portsToTry) {
            val candidate = Endpoint("http://$host:$port/api")
            when (probeListTools(candidate)) {
                HttpProbe.OK -> {
                    // Race: became available mid-scan
                    sharedCache.set(CacheEntry.Hit(cacheKey, candidate))
                    sharedLastGoodPort.set(port)
                    return DiscoveryResult(ProbeStatus.AVAILABLE, candidate, "MCP tools at ${candidate.baseUrl}")
                }
                HttpProbe.NOT_FOUND -> {
                    if (aboutOk(candidate)) {
                        return DiscoveryResult(
                            ProbeStatus.IDE_UP_MCP_OFF,
                            candidate,
                            "IDE built-in server on port $port answers /api/about, but " +
                                "/api/mcp/list_tools is HTTP 404. Enable Settings → Tools → MCP Server " +
                                "(or rely on in-process native tools)."
                        )
                    }
                }
                HttpProbe.OTHER -> {
                    // Port may be something else; keep scanning
                }
            }
        }
        return DiscoveryResult(
            ProbeStatus.UNREACHABLE,
            detail = "No IDE on $host ports ${PORT_RANGE.first}–${PORT_RANGE.last} " +
                "(or IDE_PORT / configured port). Enable MCP Server or use native tools."
        )
    }

    fun listTools(): List<ToolDefinition> {
        // Prefer cached hit without re-ping; fall back to full discover if empty cache.
        val ep = discoverEndpoint(forceRefresh = false, verifyCached = false) ?: return emptyList()
        val body = get(ep.listToolsUrl) ?: return emptyList()
        return ToolRegistry.parseMcpListToolsResponse(body)
    }

    fun callTool(name: String, argumentsJson: String): GolandMcpResult {
        // Reuse discovery — do not force-refresh on every tool call.
        val first = discoverEndpoint(forceRefresh = false, verifyCached = false)
            ?: return GolandMcpResult.error(
                "GoLand MCP Server not reachable. Enable it in Settings → Tools → MCP Server."
            )

        val firstResult = postTool(first, name, argumentsJson)
        if (!firstResult.isError || !isTransportError(firstResult.text)) {
            return firstResult
        }

        // Transport failure: invalidate and re-discover once.
        invalidateSharedCache(cacheKey)
        val second = discoverEndpoint(forceRefresh = true, verifyCached = false)
            ?: return GolandMcpResult.error(
                "GoLand MCP Server not reachable. Enable it in Settings → Tools → MCP Server."
            )
        return postTool(second, name, argumentsJson)
    }

    private fun postTool(ep: Endpoint, name: String, argumentsJson: String): GolandMcpResult {
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

    private fun isTransportError(message: String): Boolean {
        val m = message.lowercase()
        return m.contains("mcp call failed") ||
            m.contains("connection refused") ||
            m.contains("connect timed out") ||
            m.contains("connection reset") ||
            m.contains("failed to connect")
    }

    private fun ping(ep: Endpoint): Boolean {
        pingFn?.let { return it(ep) }
        return probeListTools(ep) == HttpProbe.OK
    }

    private enum class HttpProbe { OK, NOT_FOUND, OTHER }

    private fun probeListTools(ep: Endpoint): HttpProbe {
        pingFn?.let { return if (it(ep)) HttpProbe.OK else HttpProbe.OTHER }
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(ep.listToolsUrl))
                .timeout(Duration.ofMillis(800))
                .GET()
                .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            when {
                response.statusCode() in 200..299 && response.body().isNotBlank() -> HttpProbe.OK
                response.statusCode() == 404 -> HttpProbe.NOT_FOUND
                else -> HttpProbe.OTHER
            }
        } catch (_: Exception) {
            HttpProbe.OTHER
        }
    }

    private fun aboutOk(ep: Endpoint): Boolean = try {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(ep.aboutUrl))
            .timeout(Duration.ofMillis(600))
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        response.statusCode() in 200..299 && response.body().contains("productName")
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

        /** Avoid re-scanning every Send when MCP is disabled / offline. */
        const val NEGATIVE_CACHE_MS: Long = 30_000L

        private sealed class CacheEntry {
            abstract val key: String

            data class Hit(override val key: String, val endpoint: Endpoint) : CacheEntry()
            data class Miss(override val key: String, val atMs: Long) : CacheEntry()
        }

        private val sharedCache = AtomicReference<CacheEntry?>(null)
        private val sharedLastGoodPort = AtomicReference<Int?>(null)

        /** Test / settings hook: drop process-wide discovery state. */
        fun clearDiscoveryCache() {
            sharedCache.set(null)
            sharedLastGoodPort.set(null)
        }

        private fun invalidateSharedCache(key: String) {
            val cur = sharedCache.get()
            if (cur != null && cur.key == key) {
                sharedCache.compareAndSet(cur, null)
            }
        }
    }
}

data class GolandMcpResult(val text: String, val isError: Boolean) {
    companion object {
        fun ok(text: String) = GolandMcpResult(text, isError = false)
        fun error(text: String) = GolandMcpResult(text, isError = true)
    }
}
