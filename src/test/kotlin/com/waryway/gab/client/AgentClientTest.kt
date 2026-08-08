package com.waryway.gab.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Offline parse/serialize checks for [AgentClient] against shapes from
 * pkg/localllm/agent/types.go and probe-agent-run-sample.json.
 *
 * Pure JSON tests — no IDE fixture or live HTTP.
 */
class AgentClientTest {

    private val client = AgentClient(rootUrlOverride = "http://127.0.0.1:7400")

    // ── Root URL normalization (Local LLM agent/health) ────────────────────

    @Test
    fun `normalizeLocalRootUrl strips trailing slash then v1`() {
        assertEquals(
            "http://127.0.0.1:7400",
            AgentClient.normalizeLocalRootUrl("http://127.0.0.1:7400/v1/")
        )
        assertEquals(
            "http://127.0.0.1:7400",
            AgentClient.normalizeLocalRootUrl("http://127.0.0.1:7400/v1")
        )
        assertEquals(
            "http://127.0.0.1:7400",
            AgentClient.normalizeLocalRootUrl("http://127.0.0.1:7400/")
        )
        assertEquals(
            "http://127.0.0.1:7400",
            AgentClient.normalizeLocalRootUrl("  ")
        )
    }

    @Test
    fun `rootUrlOverride with trailing v1 slash strips correctly`() {
        val c = AgentClient(rootUrlOverride = "http://127.0.0.1:7400/v1/")
        assertEquals("http://127.0.0.1:7400", c.rootUrl)
    }

    // ── Start body serialization ───────────────────────────────────────────

    @Test
    fun `buildStartBody always includes goal and explicit dryRun boolean`() {
        val withTrue = client.buildStartBody(
            goal = "goal text",
            dryRun = true,
            preset = "agent-plan",
            model = null,
            maxSteps = 8
        )
        assertTrue(withTrue.contains("\"goal\":\"goal text\""), withTrue)
        assertTrue(withTrue.contains("\"dryRun\":true"), withTrue)
        assertTrue(withTrue.contains("\"preset\":\"agent-plan\""), withTrue)
        assertTrue(withTrue.contains("\"maxSteps\":8"), withTrue)

        val withFalse = client.buildStartBody(
            goal = "g",
            dryRun = false,
            preset = null,
            model = "m1",
            maxSteps = null
        )
        assertTrue(withFalse.contains("\"goal\":\"g\""), withFalse)
        assertTrue(withFalse.contains("\"dryRun\":false"), withFalse)
        assertTrue(withFalse.contains("\"model\":\"m1\""), withFalse)
        assertFalse(withFalse.contains("maxSteps"), withFalse)
        assertFalse(withFalse.contains("preset"), withFalse)
    }

    @Test
    fun `buildStartBody omits blank preset and zero maxSteps`() {
        val body = client.buildStartBody(
            goal = "x",
            dryRun = true,
            preset = "  ",
            model = "",
            maxSteps = 0
        )
        assertTrue(body.contains("\"dryRun\":true"), body)
        assertFalse(body.contains("preset"), body)
        assertFalse(body.contains("model"), body)
        assertFalse(body.contains("maxSteps"), body)
    }

    @Test
    fun `buildStartBody escapes quotes and newlines in goal`() {
        val body = client.buildStartBody(
            goal = "Say \"hi\"\nthen stop",
            dryRun = true,
            preset = null,
            model = null,
            maxSteps = null
        )
        assertTrue(body.contains("\\\"hi\\\""), body)
        assertTrue(body.contains("\\n"), body)
        assertTrue(body.startsWith("{") && body.endsWith("}"), body)
    }

    // ── Run snapshot parse ─────────────────────────────────────────────────

    @Test
    fun `parseRun maps state plan tasks events dryRun repoRoot finalAnswer`() {
        val body = """
            {
              "id":"60c02f27-eff9-460e-b667-b014dab36c09",
              "goal":"Read parse.go",
              "state":"done",
              "dryRun":true,
              "preset":"agent-plan",
              "step":2,
              "maxSteps":8,
              "repoRoot":"C:\\Users\\kawie\\waryway\\stack",
              "createdAt":"2026-07-17T12:21:46.7676286Z",
              "updatedAt":"2026-07-17T12:22:14.4804028Z",
              "plan":{
                "summary":"Read first lines",
                "tasks":[
                  {
                    "id":"t1",
                    "title":"Read file",
                    "description":"Inspect parser",
                    "tool":"read_file",
                    "toolArgs":{"file_path":"pkg/localllm/agent/parse.go"},
                    "status":"done",
                    "result":"1|package agent\n2|",
                    "error":null
                  }
                ]
              },
              "events":[
                {"kind":"plan","tool":null,"taskId":null,"detail":"Read first lines","at":"2026-07-17T12:21:59Z"},
                {"kind":"tool_call","detail":"read_file","at":"2026-07-17T12:21:59Z"}
              ],
              "finalAnswer":"Functions: ParsePlan",
              "error":null
            }
        """.trimIndent()

        val run = client.parseRun(body)
        assertEquals("60c02f27-eff9-460e-b667-b014dab36c09", run.id)
        assertEquals("done", run.state)
        assertTrue(run.isTerminal)
        assertTrue(run.dryRun)
        assertEquals("agent-plan", run.preset)
        assertEquals(2, run.step)
        assertEquals(8, run.maxSteps)
        assertEquals("C:\\Users\\kawie\\waryway\\stack", run.repoRoot)
        assertEquals("Functions: ParsePlan", run.finalAnswer)
        assertNull(run.error)

        val plan = assertNotNull(run.plan)
        assertEquals("Read first lines", plan.summary)
        assertEquals(1, plan.tasks.size)
        val task = plan.tasks[0]
        assertEquals("t1", task.id)
        assertEquals("read_file", task.tool)
        assertEquals("done", task.status)
        assertTrue(task.toolArgs!!.contains("file_path"))
        assertTrue(task.result!!.startsWith("1|package agent"))

        assertEquals(2, run.events.size)
        assertEquals("plan", run.events[0].kind)
        assertEquals("tool_call", run.events[1].kind)
    }

    @Test
    fun `parseRun prefers top-level error over nested task error`() {
        val body = """
            {
              "id":"run-1",
              "goal":"x",
              "state":"failed",
              "dryRun":false,
              "preset":"agent-plan",
              "step":1,
              "maxSteps":5,
              "repoRoot":"/repo",
              "plan":{"summary":"s","tasks":[{"id":"t1","title":"t","status":"failed","error":"task boom"}]},
              "events":[],
              "error":"run boom"
            }
        """.trimIndent()
        val run = client.parseRun(body)
        assertEquals("run boom", run.error)
        assertEquals("task boom", run.plan!!.tasks[0].error)
        assertFalse(run.dryRun)
        assertTrue(run.isTerminal)
    }

    @Test
    fun `parseRun handles in-progress snapshot without plan or finalAnswer`() {
        val body = """
            {
              "id":"run-planning",
              "goal":"do stuff",
              "state":"planning",
              "dryRun":true,
              "preset":"agent-plan",
              "step":0,
              "maxSteps":30,
              "repoRoot":"C:\\repo",
              "events":[],
              "createdAt":"2026-07-17T00:00:00Z",
              "updatedAt":"2026-07-17T00:00:00Z"
            }
        """.trimIndent()
        val run = client.parseRun(body)
        assertEquals("planning", run.state)
        assertFalse(run.isTerminal)
        assertNull(run.plan)
        assertNull(run.finalAnswer)
        assertNull(run.error)
        assertNull(run.message)
        assertTrue(run.events.isEmpty())
        assertTrue(run.dryRun)
        assertEquals("C:\\repo", run.repoRoot)
    }

    @Test
    fun `parseRun maps optional server message progress field`() {
        val body = """
            {
              "id":"run-msg",
              "goal":"g",
              "state":"planning",
              "dryRun":false,
              "step":0,
              "maxSteps":30,
              "repoRoot":"/r",
              "message":"generating plan (go-cpu)…",
              "events":[]
            }
        """.trimIndent()
        val run = client.parseRun(body)
        assertEquals("generating plan (go-cpu)…", run.message)
        assertEquals("planning", run.state)
        assertFalse(run.dryRun)
    }

    @Test
    fun `parseRun maps dependsOn and multi-task DAG`() {
        val body = """
            {
              "id":"run-dag",
              "goal":"g",
              "state":"executing",
              "dryRun":true,
              "repoRoot":"/r",
              "plan":{
                "summary":"two steps",
                "tasks":[
                  {"id":"t1","title":"Read","tool":"read_file","status":"done","dependsOn":[]},
                  {"id":"t2","title":"Test","tool":"bazel_test","status":"running","dependsOn":["t1"]}
                ]
              },
              "events":[{"kind":"tool_call","taskId":"t2","tool":"bazel_test","detail":"//pkg/..."}]
            }
        """.trimIndent()
        val run = client.parseRun(body)
        assertEquals(2, run.plan!!.tasks.size)
        assertEquals(emptyList(), run.plan!!.tasks[0].dependsOn)
        assertEquals(listOf("t1"), run.plan!!.tasks[1].dependsOn)
        assertEquals("running", run.plan!!.tasks[1].status)
        assertEquals("t2", run.events[0].taskId)
        assertEquals("bazel_test", run.events[0].tool)
        assertFalse(run.isTerminal)
    }

    @Test
    fun `parseRun defaults dryRun true when field omitted`() {
        val body = """{"id":"r","goal":"g","state":"done","repoRoot":"/r","events":[]}"""
        val run = client.parseRun(body)
        assertTrue(run.dryRun)
    }

    // ── Terminal state helper ──────────────────────────────────────────────

    @Test
    fun `isTerminal true only for done failed cancelled`() {
        fun runWith(state: String) = client.parseRun(
            """{"id":"r","goal":"g","state":"$state","dryRun":true,"repoRoot":"/r","events":[]}"""
        )

        assertTrue(runWith("done").isTerminal)
        assertTrue(runWith("failed").isTerminal)
        assertTrue(runWith("cancelled").isTerminal)

        assertFalse(runWith("planning").isTerminal)
        assertFalse(runWith("executing").isTerminal)
        assertFalse(runWith("replanning").isTerminal)
        assertFalse(runWith("paused").isTerminal)
        assertFalse(runWith("").isTerminal)

        assertEquals(
            setOf("done", "failed", "cancelled"),
            AgentClient.TERMINAL_STATES
        )
    }

    // ── Tools catalog ──────────────────────────────────────────────────────

    @Test
    fun `parseToolsResponse lists tools`() {
        val body = """
            {"tools":[
              {"name":"read_file","description":"Read a file","category":"fs","local":true,"inputSchema":{"type":"object"}},
              {"name":"bazel_test","description":"Run tests","category":"build","local":false}
            ]}
        """.trimIndent()
        val tools = client.parseToolsResponse(body)
        assertEquals(2, tools.size)
        assertEquals("read_file", tools[0].name)
        assertEquals("fs", tools[0].category)
        assertTrue(tools[0].local)
        assertNotNull(tools[0].inputSchema)
        assertEquals("bazel_test", tools[1].name)
        assertFalse(tools[1].local)
    }

    @Test
    fun `parseToolsResponse empty or missing tools`() {
        assertTrue(client.parseToolsResponse("""{"tools":[]}""").isEmpty())
        assertTrue(client.parseToolsResponse("""{}""").isEmpty())
        assertTrue(client.parseToolsResponse("""{"tools":[{"name":"","description":"skip"}]}""").isEmpty())
    }

    // ── Base URL ───────────────────────────────────────────────────────────

    @Test
    fun `rootUrl strips trailing v1 and slash`() {
        val withV1 = AgentClient(rootUrlOverride = "http://127.0.0.1:7400/v1")
        assertEquals("http://127.0.0.1:7400", withV1.rootUrl)

        val withSlash = AgentClient(rootUrlOverride = "http://127.0.0.1:7400/")
        assertEquals("http://127.0.0.1:7400", withSlash.rootUrl)

        val plain = AgentClient(rootUrlOverride = "http://127.0.0.1:7400")
        assertEquals("http://127.0.0.1:7400", plain.rootUrl)
    }
}
