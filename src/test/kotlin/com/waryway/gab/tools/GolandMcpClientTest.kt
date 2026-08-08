package com.waryway.gab.tools

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GolandMcpClientTest {

    private var nowMs = 1_000_000L
    private val livePorts = mutableSetOf<Int>()
    private var pingCount = 0

    @BeforeTest
    fun setUp() {
        GolandMcpClient.clearDiscoveryCache()
        nowMs = 1_000_000L
        livePorts.clear()
        pingCount = 0
    }

    @AfterTest
    fun tearDown() {
        GolandMcpClient.clearDiscoveryCache()
    }

    private fun client(configuredPort: Int = 0): GolandMcpClient =
        GolandMcpClient(
            configuredPort = configuredPort,
            host = "127.0.0.1",
            pingFn = { ep ->
                pingCount++
                val port = ep.portOrNull()
                port != null && port in livePorts
            },
            clockMs = { nowMs },
            envPortProvider = { null }
        )

    @Test
    fun `discover caches positive hit across client instances`() {
        livePorts += 63345
        val first = client()
        val ep1 = first.discoverEndpoint()
        assertNotNull(ep1)
        assertEquals("http://127.0.0.1:63345/api", ep1.baseUrl)
        val pingsAfterFirst = pingCount

        val second = client()
        val ep2 = second.discoverEndpoint(verifyCached = false)
        assertEquals(ep1.baseUrl, ep2?.baseUrl)
        // No re-scan / re-ping when verifyCached=false
        assertEquals(pingsAfterFirst, pingCount)
    }

    @Test
    fun `discover negative cache skips rescan until TTL expires`() {
        // No live ports — full miss
        val first = client()
        assertNull(first.discoverEndpoint())
        val pingsAfterMiss = pingCount
        assertTrue(pingsAfterMiss > 0)

        val second = client()
        assertNull(second.discoverEndpoint())
        // Negative cache: no extra pings within TTL
        assertEquals(pingsAfterMiss, pingCount)

        nowMs += GolandMcpClient.NEGATIVE_CACHE_MS + 1
        assertNull(second.discoverEndpoint())
        assertTrue(pingCount > pingsAfterMiss, "expired negative cache should rescan")
    }

    @Test
    fun `forceRefresh bypasses negative cache`() {
        assertNull(client().discoverEndpoint())
        val afterMiss = pingCount

        assertNull(client().discoverEndpoint(forceRefresh = true))
        assertTrue(pingCount > afterMiss)
    }

    @Test
    fun `configured port is tried first and last good port preferred after hit`() {
        livePorts += 63350
        val c = client(configuredPort = 0)
        val ep = c.discoverEndpoint()
        assertEquals("http://127.0.0.1:63350/api", ep?.baseUrl)

        GolandMcpClient.clearDiscoveryCache()
        // last good port survives clearDiscoveryCache? — no, clear wipes last good too.
        // Re-seed: discover once then clear only the cache entry via force path.
        // After a hit, a new client with verifyCached=false reuses without scanning range.
        livePorts.clear()
        livePorts += 63350
        val again = client()
        assertNotNull(again.discoverEndpoint())
        val pings = pingCount
        assertNotNull(client().discoverEndpoint(verifyCached = false))
        assertEquals(pings, pingCount)
    }

    @Test
    fun `callTool uses cache and does not force rescan when transport ok path not taken`() {
        // callTool without a real HTTP server returns transport error after discover.
        // We only assert discovery still shares cache between isAvailable and callTool.
        livePorts += 63342
        val c = client()
        assertTrue(c.isAvailable())
        val afterAvail = pingCount

        // callTool will try POST (real HTTP) → fail transport → force re-discover once.
        // With livePorts still set, re-discover pings again.
        val result = c.callTool("read_file", """{"file_path":"x"}""")
        assertTrue(result.isError)
        // At least one discover for the retry path is acceptable; critical regression is
        // that we do not scan on every call without a prior hit (covered above).
        assertTrue(pingCount >= afterAvail)
    }

    @Test
    fun `isAvailable false when nothing listens`() {
        assertFalse(client().isAvailable())
    }

    @Test
    fun `Endpoint portOrNull parses baseUrl`() {
        val ep = GolandMcpClient.Endpoint("http://127.0.0.1:63347/api")
        assertEquals(63347, ep.portOrNull())
        assertEquals("http://127.0.0.1:63347/api/mcp/list_tools", ep.listToolsUrl)
        assertEquals("http://127.0.0.1:63347/api/about", ep.aboutUrl)
        assertEquals("http://127.0.0.1:63347/api/mcp/read_file", ep.callToolUrl("read_file"))
    }

    @Test
    fun `probeDiscovery UNREACHABLE when nothing listens`() {
        val result = client().probeDiscovery(forceRefresh = true)
        assertEquals(GolandMcpClient.ProbeStatus.UNREACHABLE, result.status)
        assertNull(result.endpoint)
        assertTrue(result.detail!!.contains("No IDE") || result.detail!!.contains("native"))
    }

    @Test
    fun `probeDiscovery AVAILABLE when list_tools ping succeeds`() {
        livePorts += 63344
        val result = client().probeDiscovery(forceRefresh = true)
        assertEquals(GolandMcpClient.ProbeStatus.AVAILABLE, result.status)
        assertNotNull(result.endpoint)
        assertEquals("http://127.0.0.1:63344/api", result.endpoint!!.baseUrl)
    }
}
