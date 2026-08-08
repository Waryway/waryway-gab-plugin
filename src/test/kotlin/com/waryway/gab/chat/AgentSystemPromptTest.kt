package com.waryway.gab.chat

import com.waryway.gab.model.ModelProvider
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Full acceptance matrix for session identity + tool-use + anti-thrash policy.
 * Pure unit tests against [AgentSystemPrompt.build] — no Project/IDE fixtures.
 */
class AgentSystemPromptTest {

    // --- Cloud tools-on / tools-off regression (no identity) ---

    @Test
    fun `cloud with tools claims MCP access`() {
        val prompt = AgentSystemPrompt.build(
            projectRoot = "C:/dev/demo",
            localLlm = false,
            toolsAvailable = true
        )
        assertContains(prompt, "MCP Server")
        assertContains(prompt, "read_file")
        assertContains(prompt, "You CAN and MUST")
    }

    @Test
    fun `cloud without tools does not claim MCP access`() {
        val prompt = AgentSystemPrompt.build(
            projectRoot = "C:/dev/demo",
            localLlm = false,
            toolsAvailable = false
        )
        assertContains(prompt, "IDE tools are NOT available")
        assertFalse(prompt.contains("You CAN and MUST"))
        assertFalse(prompt.contains("replace_text_in_file"))
        assertFalse(prompt.contains("MCP Server"))
    }

    @Test
    fun `cloud with tools mentions native fallback`() {
        val prompt = AgentSystemPrompt.build(
            projectRoot = "C:/dev/demo",
            localLlm = false,
            toolsAvailable = true
        )
        assertContains(prompt, "native tools")
        assertContains(prompt, "execute_terminal_command")
    }

    @Test
    fun `identity omitted keeps backward compatible cloud tools-on prompt`() {
        val prompt = AgentSystemPrompt.build(
            projectRoot = "C:/dev/demo",
            localLlm = false,
            toolsAvailable = true,
        )
        assertFalse(prompt.contains("Session facts"))
        assertContains(prompt, "Project root: C:/dev/demo")
        assertContains(prompt, "You CAN and MUST")
        assertContains(prompt, "read_file")
        assertContains(prompt, "search_in_files_by_text")
        // Anti-thrash still applies without identity
        assertContainsAntiThrash(prompt)
    }

    // --- Anti-thrash (cloud tools-on) ---

    @Test
    fun `cloud tools-on includes anti-thrash prefer-anchors policy`() {
        val prompt = AgentSystemPrompt.build(
            projectRoot = "C:/dev/demo",
            localLlm = false,
            toolsAvailable = true,
        )
        assertContainsAntiThrash(prompt)
        // Still tools-on competent
        assertContains(prompt, "You CAN and MUST")
        assertContains(prompt, "read_file")
        assertContains(prompt, "replace_text_in_file")
    }

    @Test
    fun `GROK_BUILD tools on includes anti-thrash and keeps coding competence`() {
        val prompt = AgentSystemPrompt.build(
            projectRoot = "C:/dev/demo",
            localLlm = false,
            toolsAvailable = true,
            provider = ModelProvider.GROK_BUILD,
            modelId = "grok-4.5",
            toolsBackend = "native",
        )
        assertContainsAntiThrash(prompt)

        // Explicit open-files gate for general goals
        assertTrue(
            prompt.contains("Do NOT start with get_all_open_file_paths") ||
                prompt.contains("Do not start with get_all_open_file_paths"),
            "must discourage opening with get_all_open_file_paths for general goals"
        )
        assertTrue(
            prompt.contains("open editors") || prompt.contains("what is open"),
            "must state when get_all_open_file_paths is OK"
        )

        // How-to / skill language
        assertTrue(
            prompt.contains("how-to") || prompt.contains("how-to / where-is"),
            "must mention how-to / where-is style goals"
        )
        assertTrue(
            prompt.contains("[skill:") || prompt.contains("skill rails") || prompt.contains("skill body"),
            "must instruct following skill rails/body before exploratory search"
        )

        // Prefer anchors present (constants + prompt text)
        for (anchor in AgentSystemPrompt.PREFER_PROJECT_ANCHORS) {
            assertTrue(
                prompt.contains(anchor),
                "prefer-anchors policy should mention $anchor"
            )
        }

        // Tools-on competence intact
        assertContains(prompt, "You CAN and MUST")
        assertContains(prompt, "read_file")
        assertContains(prompt, "replace_text_in_file")
        assertContains(prompt, "execute_terminal_command")
    }

    // --- Local LLM ---

    @Test
    fun `local llm prompt stays short and tool-free`() {
        val prompt = AgentSystemPrompt.build(
            projectRoot = "C:/dev/demo",
            localLlm = true,
            toolsAvailable = true
        )
        assertContains(prompt, "local offline LLM")
        assertFalse(prompt.contains("MCP Server"))
        assertFalse(prompt.contains("You CAN and MUST"))
        assertFalse(prompt.contains("Session facts"))
        assertFalse(prompt.contains("ANTI-THRASH"))
        assertFalse(prompt.contains("get_all_open_file_paths"))
    }

    @Test
    fun `local llm ignores identity params and stays short tool-free`() {
        val prompt = AgentSystemPrompt.build(
            projectRoot = "C:/dev/demo",
            localLlm = true,
            toolsAvailable = true,
            provider = ModelProvider.GROK_BUILD,
            modelId = "grok-4.5",
            toolsBackend = "native",
        )
        assertContains(prompt, "local offline LLM")
        assertFalse(prompt.contains("MCP Server"))
        assertFalse(prompt.contains("You CAN and MUST"))
        assertFalse(prompt.contains("Session facts"))
        assertFalse(prompt.contains("Tools backend"))
        // Must not switch to agentic Grok Build cloud prompt
        assertFalse(prompt.contains("META / IDENTITY"))
        assertFalse(prompt.contains("ANTI-THRASH"))
    }

    // --- GROK_BUILD + tools on + identity ---

    @Test
    fun `GROK_BUILD tools on includes session identity and meta policy`() {
        val prompt = AgentSystemPrompt.build(
            projectRoot = "C:/dev/demo",
            localLlm = false,
            toolsAvailable = true,
            provider = ModelProvider.GROK_BUILD,
            modelId = "grok-4.5",
            toolsBackend = "native",
        )
        // Identity
        assertContains(prompt, "Grok Build")
        assertContains(prompt, "grok-4.5")
        assertContains(prompt, "Tools backend: native")
        assertContains(prompt, "Session facts")
        assertContains(prompt, "Provider: Grok Build")
        assertContains(prompt, "Model: grok-4.5")

        // Meta / identity policy — answer from session; do not explore via tools
        assertContains(prompt, "META / IDENTITY")
        assertContains(prompt, "search_in_files_by_text")
        assertContains(prompt, "get_all_open_file_paths")
        assertTrue(
            prompt.contains("\"model\"") || prompt.contains("word \"model\""),
            "should explicitly discourage searching for the word model"
        )
        assertTrue(
            prompt.contains("Session facts") &&
                (prompt.contains("answer ONLY from") || prompt.contains("answer from")),
            "meta policy should steer identity Qs to Session facts"
        )

        // Coding tools guidance still present
        assertContains(prompt, "You CAN and MUST")
        assertContains(prompt, "read_file")
        assertContains(prompt, "execute_terminal_command")
        assertContains(prompt, "replace_text_in_file")
    }

    @Test
    fun `GROK_BUILD tools on with mcp backend labels backend`() {
        val prompt = AgentSystemPrompt.build(
            projectRoot = "C:/dev/demo",
            localLlm = false,
            toolsAvailable = true,
            provider = ModelProvider.GROK_BUILD,
            modelId = "grok-code-fast-1",
            toolsBackend = "mcp",
        )
        assertContains(prompt, "Tools backend: mcp")
        assertContains(prompt, "Grok Build")
        assertContains(prompt, "You CAN and MUST")
        assertContains(prompt, "META / IDENTITY")
        assertContainsAntiThrash(prompt)
    }

    // --- GROK_BUILD + tools off + identity ---

    @Test
    fun `GROK_BUILD tools off stays honest and includes session identity`() {
        val prompt = AgentSystemPrompt.build(
            projectRoot = "C:/dev/demo",
            localLlm = false,
            toolsAvailable = false,
            provider = ModelProvider.GROK_BUILD,
            modelId = "grok-4.5",
            toolsBackend = "off",
        )
        // Honest tools-off
        assertContains(prompt, "IDE tools are NOT available")
        assertFalse(prompt.contains("You CAN and MUST"))
        assertFalse(prompt.contains("replace_text_in_file"))
        assertFalse(prompt.contains("MCP Server"))
        assertFalse(prompt.contains("create_new_file"))
        assertFalse(prompt.contains("ANTI-THRASH"))

        // Identity still present for meta Qs
        assertContains(prompt, "Grok Build")
        assertContains(prompt, "grok-4.5")
        assertContains(prompt, "Session facts")
        assertContains(prompt, "Tools backend: off")
        assertTrue(
            prompt.contains("Session facts") &&
                (prompt.contains("model") || prompt.contains("provider")),
            "tools-off prompt should still guide identity answers from session facts"
        )
    }

    // --- Optional: other providers + blank modelId ---

    @Test
    fun `other cloud provider with model id gets light identity without breaking tools-on`() {
        val prompt = AgentSystemPrompt.build(
            projectRoot = "C:/dev/demo",
            localLlm = false,
            toolsAvailable = true,
            provider = ModelProvider.GROK,
            modelId = "grok-3",
            toolsBackend = "mcp",
        )
        assertContains(prompt, "Session facts")
        assertContains(prompt, "Provider: Grok (API)")
        assertContains(prompt, "Model: grok-3")
        assertContains(prompt, "Tools backend: mcp")
        // Not the Grok Build-only mandatory META block, but light guidance
        assertFalse(prompt.contains("META / IDENTITY — tool-use policy (mandatory)"))
        assertTrue(
            prompt.contains("Session facts") && prompt.contains("answer from"),
            "light identity guidance should reference Session facts"
        )
        // Tools-on wording intact + anti-thrash for all cloud tools-on
        assertContains(prompt, "You CAN and MUST")
        assertContains(prompt, "read_file")
        assertContains(prompt, "MCP Server")
        assertContainsAntiThrash(prompt)
    }

    @Test
    fun `blank modelId does not print empty model line`() {
        val prompt = AgentSystemPrompt.build(
            projectRoot = "C:/dev/demo",
            localLlm = false,
            toolsAvailable = true,
            provider = ModelProvider.GROK_BUILD,
            modelId = "   ",
            toolsBackend = "native",
        )
        assertContains(prompt, "Session facts")
        assertContains(prompt, "Provider: Grok Build")
        assertContains(prompt, "Tools backend: native")
        assertFalse(
            prompt.contains("Model:"),
            "blank modelId must not emit an empty Model line"
        )
        assertContains(prompt, "You CAN and MUST")
    }

    @Test
    fun `null modelId with provider still builds session facts`() {
        val prompt = AgentSystemPrompt.build(
            projectRoot = "C:/dev/demo",
            localLlm = false,
            toolsAvailable = true,
            provider = ModelProvider.GROK_BUILD,
            modelId = null,
            toolsBackend = null,
        )
        assertContains(prompt, "Session facts")
        assertContains(prompt, "Provider: Grok Build")
        assertFalse(prompt.contains("Model:"))
        assertFalse(prompt.contains("Tools backend:"))
        assertContains(prompt, "META / IDENTITY")
        assertContains(prompt, "You CAN and MUST")
        assertContainsAntiThrash(prompt)
    }

    /** Shared asserts for anti-thrash / prefer-anchors language on cloud tools-on prompts. */
    private fun assertContainsAntiThrash(prompt: String) {
        assertContains(prompt, "ANTI-THRASH")
        assertTrue(
            prompt.contains("Do NOT start with get_all_open_file_paths") ||
                prompt.contains("Do not start with get_all_open_file_paths"),
            "must discourage starting with get_all_open_file_paths"
        )
        assertTrue(
            prompt.contains("Prefer project anchors") || prompt.contains("prefer project anchors") ||
                prompt.contains("Prefer known anchors"),
            "must prefer project anchors language"
        )
        assertContains(prompt, "AGENTS.md")
        assertTrue(
            prompt.contains("at most one focused search") ||
                prompt.contains("multi-keyword") ||
                prompt.contains("dir/findstr"),
            "must discourage multi-search / shell thrash"
        )
        assertTrue(
            prompt.contains("how-to") || prompt.contains("where-is") || prompt.contains("operator"),
            "must cover how-to / operator style goals"
        )
    }
}
