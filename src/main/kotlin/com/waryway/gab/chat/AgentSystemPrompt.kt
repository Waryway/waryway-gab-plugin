package com.waryway.gab.chat

import com.intellij.openapi.project.Project
import com.waryway.gab.model.ModelProvider

/**
 * System prompt for cloud chat agents (Grok Build / Gab / Grok API).
 *
 * When [toolsAvailable] is false, the prompt must **not** claim IDE tool access —
 * otherwise the model invents tool_calls that never execute.
 * When tools are on, the plugin runs them via JetBrains MCP Server if reachable,
 * otherwise via in-process native tools (core read/search/edit/command set).
 *
 * Optional [provider] / [modelId] / [toolsBackend] inject a **Session facts** block so
 * identity/meta questions are answered from known session state instead of exploratory
 * tool chains (e.g. searching the project for "model").
 *
 * Tools-on prompts also include **ANTI-THRASH** guardrails so how-to / operator / skill
 * goals prefer known project anchors over `get_all_open_file_paths` + multi-grep thrash.
 */
object AgentSystemPrompt {

    /**
     * Preferred first-read paths for how-to / where-is / operator goals.
     * Kept as a pure list so unit tests and prompt copy stay aligned.
     */
    val PREFER_PROJECT_ANCHORS: List<String> = listOf(
        "AGENTS.md",
        ".github/AGENT.md",
        "apps/localllm",
        "pkg/localllm",
        "scripts/",
        "docs/",
    )

    fun build(
        project: Project,
        localLlm: Boolean = false,
        toolsAvailable: Boolean = true,
        provider: ModelProvider? = null,
        modelId: String? = null,
        toolsBackend: String? = null,
    ): String = build(
        projectRoot = project.basePath,
        localLlm = localLlm,
        toolsAvailable = toolsAvailable,
        provider = provider,
        modelId = modelId,
        toolsBackend = toolsBackend,
    )

    /** Pure entry for tests and non-IDE call sites. */
    fun build(
        projectRoot: String?,
        localLlm: Boolean = false,
        toolsAvailable: Boolean = true,
        provider: ModelProvider? = null,
        modelId: String? = null,
        toolsBackend: String? = null,
    ): String {
        val root = projectRoot?.takeIf { it.isNotBlank() } ?: "(unknown)"
        if (localLlm) {
            return """
                You are Waryway Agent (local offline LLM). Project: $root.
                Answer briefly in plain text. One message only — no fake User/Assistant dialogue, no repeated goodbyes.
                For status questions, give a short direct answer.
            """.trimIndent()
        }

        val os = System.getProperty("os.name", "unknown")
        val buildHint = if (os.lowercase().contains("win")) {
            "On Windows use `build.bat` or `gradlew.bat` via execute_terminal_command."
        } else {
            "Use `./gradlew build` or project-specific build scripts via execute_terminal_command."
        }

        val sessionFacts = sessionFactsBlock(
            root = root,
            os = os,
            provider = provider,
            modelId = modelId,
            toolsBackend = toolsBackend,
        )
        val isGrokBuild = provider == ModelProvider.GROK_BUILD

        if (!toolsAvailable) {
            val identityHint = if (sessionFacts != null) {
                """

                Answer questions about model, provider, session, or tools backend from the Session facts above only.
                Do not invent tool calls to discover identity information.
                """.trimIndent()
            } else {
                ""
            }
            return buildString {
                appendLine(
                    """
                    You are Waryway Agent, an AI coding assistant in JetBrains GoLand/IDEA (Waryway Agent plugin).

                    IDE tools are NOT available this turn (disabled for this provider/session).
                    Do NOT call tools or claim you can read/write files, run builds, or execute shell commands.
                    Answer from conversation context and general knowledge.
                    """.trimIndent()
                )
                if (sessionFacts != null) {
                    appendLine()
                    appendLine(sessionFacts)
                    appendLine(identityHint)
                } else {
                    appendLine()
                    appendLine("Project root: $root")
                    appendLine("OS: $os")
                }
            }.trimEnd()
        }

        val persona = if (isGrokBuild) {
            "You are Waryway Agent (Grok Build), an agentic AI coding assistant running inside JetBrains GoLand/IDEA via the Waryway Agent plugin."
        } else {
            "You are Waryway Agent, an agentic AI coding assistant running inside JetBrains GoLand/IDEA via the Waryway Agent plugin."
        }

        val metaPolicy = if (isGrokBuild) {
            """

            META / IDENTITY — tool-use policy (mandatory):
            - Questions about which model, provider, session, tools backend, or similar identity/meta facts → answer ONLY from the Session facts block below.
            - Do NOT call search_in_files_by_text, get_all_open_file_paths, shell/execute_terminal_command, or any other tool to "discover" the active model by searching the project for the word "model" (or similar).
            - Session identity is already known; exploratory IDE tool chains are wrong for meta questions.
            """.trimIndent()
        } else if (sessionFacts != null) {
            """

            For questions about model, provider, session, or tools backend, answer from the Session facts block below — do not search the project to discover them.
            """.trimIndent()
        } else {
            ""
        }

        val anchorsLine = PREFER_PROJECT_ANCHORS.joinToString(", ")
        val antiThrashPolicy = """

            ANTI-THRASH / EXPLORE POLICY (mandatory):
            - Do NOT start with get_all_open_file_paths unless the user asks about open editors, current files, or what is open in the IDE.
            - Prefer project anchors before multi-keyword project-wide search: $anchorsLine
            - For how-to / where-is / what-is / operator workflow questions: read known docs and scripts first (read_file on anchors above); at most one focused search; avoid chains of full-phrase → shortened greps → dir/findstr thrash.
            - If the user message contains skill rails, [skill:…], or a skill body: follow those skill instructions first before exploratory search.
            - Real code-change goals still use tools (read relevant files, replace_text_in_file, build) — do not skip edits; just avoid open-files-first and multi-search thrash when the goal is how-to/meta/skill.
            """.trimIndent()

        val projectSection = if (sessionFacts != null) {
            """
            $sessionFacts
            $buildHint
            """.trimIndent()
        } else {
            """
            Project root: $root
            OS: $os
            $buildHint
            """.trimIndent()
        }

        return """
            $persona

            CRITICAL — you have DIRECT workspace access (JetBrains MCP Server when enabled, otherwise in-process native tools). You CAN and MUST:
            - Read and search project files (read_file, search_in_files_by_text, list_directory_tree, get_all_open_file_paths)
            - Modify files directly (replace_text_in_file, create_new_file; apply_patch when MCP is on)
            - Build and inspect (build_project, get_file_problems when MCP is on)
            - Run shell commands including builds (execute_terminal_command)

            Always pass projectPath="$root" when a tool accepts it.

            Do NOT tell the user you cannot modify files, run build.bat, or execute commands. Do NOT ask them to paste code snippets or apply patches manually unless a tool failed. Use tools first, then summarize what you did.
            $metaPolicy
            $antiThrashPolicy

            $projectSection

            Workflow for code changes:
            1. Prefer known anchors (read_file on AGENTS.md / docs / package paths) over get_all_open_file_paths and multi-keyword search thrash; only then use focused search_in_files_by_text if needed
            2. Apply changes with replace_text_in_file (small edits) or create_new_file (new files)
            3. Run build_project or execute_terminal_command when appropriate
            4. Briefly report results to the user

            Prefer acting via tools over outputting manual diffs. Paths are relative to the project root unless absolute.
        """.trimIndent()
    }

    /**
     * Session facts when any identity param is present; always includes project root + OS
     * so the block can replace the standalone Project root / OS lines.
     * Returns null when no identity was provided (backward-compatible prompt shape).
     */
    private fun sessionFactsBlock(
        root: String,
        os: String,
        provider: ModelProvider?,
        modelId: String?,
        toolsBackend: String?,
    ): String? {
        val model = modelId?.takeIf { it.isNotBlank() }
        val backend = toolsBackend?.takeIf { it.isNotBlank() }
        if (provider == null && model == null && backend == null) return null

        return buildString {
            appendLine("Session facts:")
            if (provider != null) {
                appendLine("- Provider: ${provider.displayName}")
            }
            if (model != null) {
                appendLine("- Model: $model")
            }
            if (backend != null) {
                appendLine("- Tools backend: $backend")
            }
            appendLine("- Project root: $root")
            append("- OS: $os")
        }
    }
}
