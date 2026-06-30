package com.waryway.gab.skills

/**
 * Guided skills keep the agent on rails with templates + short rules per task.
 */
object SkillRegistry {

    data class GuidedSkill(
        val id: String,
        val name: String,
        val category: String,
        val template: String,
        val rails: String,
        val hint: String,
        val localLlmPreset: String? = null
    )

    val all: List<GuidedSkill> = listOf(
        GuidedSkill(
            id = "none",
            name = "(free chat)",
            category = "general",
            template = "{input}",
            rails = "Answer briefly and stay on topic.",
            hint = "No template — type your own prompt."
        ),
        GuidedSkill(
            id = "explain",
            name = "Explain code",
            category = "code",
            template = "Explain this code or area:\n\n{input}",
            rails = "Plain language. Reference paths and symbols. Max 8 sentences. No filler.",
            hint = "Attach a file or paste a symbol/path."
        ),
        GuidedSkill(
            id = "tests",
            name = "Write tests",
            category = "code",
            template = "Write tests for:\n\n{input}",
            rails = "Name the test file and framework (JUnit/Go test/Bazel). Outline cases first, then code.",
            hint = "Point at the code under test."
        ),
        GuidedSkill(
            id = "implement",
            name = "Implement feature",
            category = "code",
            template = "Implement this feature:\n\n{input}",
            rails = "Steps: 1) files to touch 2) minimal change plan 3) code. Stay in project conventions.",
            hint = "Describe the feature in one paragraph."
        ),
        GuidedSkill(
            id = "refactor",
            name = "Refactor",
            category = "code",
            template = "Refactor safely:\n\n{input}",
            rails = "Preserve behavior. List files first. Small diffs. No drive-by changes.",
            hint = "What to refactor and why."
        ),
        GuidedSkill(
            id = "llm-status",
            name = "LLM status",
            category = "llm",
            template = "Summarize local LLM readiness: server, model, corpus, and what I should do next.",
            rails = "One short paragraph. Actionable. No role-play.",
            hint = "Checks apps/localllm health.",
            localLlmPreset = "concise"
        ),
        GuidedSkill(
            id = "llm-collect",
            name = "Draft corpus example",
            category = "llm",
            template = "Create a training example from this interaction topic:\n\n{input}",
            rails = "Format exactly:\nInstruction: <one line>\nOutput: <factual answer>",
            hint = "Use after a good answer — then click Collect in LLM panel.",
            localLlmPreset = "corpus"
        ),
        GuidedSkill(
            id = "llm-grow",
            name = "Grow / improve model",
            category = "llm",
            template = "How should I grow the local LLM for this project?\n\nContext:\n{input}",
            rails = "Cover: corpus examples, rebuild, presets. Bullet list. Under 10 lines.",
            hint = "Ask how to improve offline model behavior.",
            localLlmPreset = "stack"
        ),
        GuidedSkill(
            id = "debug-build",
            name = "Fix build errors",
            category = "code",
            template = "Fix these build/test errors:\n\n{input}",
            rails = "Root cause first. One fix at a time. Show exact commands to verify.",
            hint = "Paste errors or attach problems view."
        )
    )

    fun find(id: String?): GuidedSkill? =
        if (id.isNullOrBlank() || id == "none") null else all.find { it.id == id }

    fun apply(skill: GuidedSkill?, userInput: String): String {
        if (skill == null || skill.id == "none") return userInput.trim()
        val body = skill.template.replace("{input}", userInput.trim())
        return buildString {
            append(skill.rails)
            append("\n\n")
            append(body)
        }
    }

    fun categories(): List<String> = all.map { it.category }.distinct().sorted()
}