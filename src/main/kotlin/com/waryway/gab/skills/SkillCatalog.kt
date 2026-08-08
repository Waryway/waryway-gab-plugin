package com.waryway.gab.skills

import java.nio.file.Path

/**
 * Unified skill catalog: discover from bundled + user + project sources, merge by id,
 * and apply/inject into outbound text with a stable `[skill:<id>]` marker for section-04 HeuristicPlan.
 *
 * ## Merge order
 *
 * When the same [SkillRef.id] appears in more than one source:
 * **project > user > bundled**.
 *
 * Unique ids are never dropped. The free-chat sentinel id `none` remains available
 * and [apply] leaves user input unchanged for it.
 *
 * Pure helpers - no Swing / IDE project required.
 */
object SkillCatalog {

    /** Default max characters of skill body/rails embedded into the outbound goal. */
    const val DEFAULT_MAX_BODY_CHARS: Int = 12_000

    /** Stable marker prefix consumed later by HeuristicPlan (section-04), e.g. `[skill:ceo]`. */
    fun skillMarker(id: String): String = "[skill:${id.trim()}]"

    /**
     * Convert a bundled [SkillRegistry.GuidedSkill] into a [SkillRef].
     * [bodyOrRails] stores rails; [template] keeps the `{input}` template.
     */
    fun fromGuided(skill: SkillRegistry.GuidedSkill): SkillRef =
        SkillRef(
            id = skill.id,
            name = skill.name,
            source = SkillSource.BUNDLED,
            path = null,
            description = skill.hint,
            bodyOrRails = skill.rails,
            template = skill.template,
            category = skill.category,
            hint = skill.hint,
            localLlmPreset = skill.localLlmPreset
        )

    /**
     * Discover and merge skills from three sources.
     *
     * @param projectBasePath optional project root; when null/blank, project source is skipped
     * @param userSkillsRoot override user skills directory (tests inject temp dirs here)
     * @param projectSkillsRoot override project skills directory (tests inject temp dirs here)
     * @param bundled guided skills; defaults to [SkillRegistry.all]
     */
    fun discover(
        projectBasePath: String? = null,
        userSkillsRoot: Path? = SkillDiscovery.defaultUserSkillsRoot(),
        projectSkillsRoot: Path? = SkillDiscovery.projectSkillsRoot(projectBasePath),
        bundled: List<SkillRegistry.GuidedSkill> = SkillRegistry.all
    ): List<SkillRef> {
        val bundledRefs = bundled.map { fromGuided(it) }
        val userRefs = SkillDiscovery.discoverFromDir(userSkillsRoot, SkillSource.USER)
        val projectRefs = SkillDiscovery.discoverFromDir(projectSkillsRoot, SkillSource.PROJECT)
        return merge(bundled = bundledRefs, user = userRefs, project = projectRefs)
    }

    /**
     * Merge three source lists.
     *
     * **Precedence:** project overrides user overrides bundled for the same [SkillRef.id].
     * Order of the result: `none` first (if present), then remaining sorted by id (case-insensitive).
     */
    fun merge(
        bundled: List<SkillRef>,
        user: List<SkillRef>,
        project: List<SkillRef>
    ): List<SkillRef> {
        // Lower precedence first so higher sources overwrite
        val byId = linkedMapOf<String, SkillRef>()
        for (ref in bundled) {
            if (ref.id.isNotBlank()) byId[ref.id] = ref
        }
        for (ref in user) {
            if (ref.id.isNotBlank()) byId[ref.id] = ref
        }
        for (ref in project) {
            if (ref.id.isNotBlank()) byId[ref.id] = ref
        }
        val none = byId.remove("none")
        val rest = byId.values.sortedBy { it.id.lowercase() }
        return buildList {
            if (none != null) add(none)
            addAll(rest)
        }
    }

    fun find(skills: List<SkillRef>, id: String?): SkillRef? {
        if (id.isNullOrBlank() || id == "none") return null
        return skills.find { it.id == id }
    }

    fun findByName(skills: List<SkillRef>, name: String?): SkillRef? {
        if (name.isNullOrBlank()) return null
        return skills.find { it.name == name }
    }

    /**
     * Build outbound text for chat payload / LocalLLM agent goal.
     *
     * Shape:
     * ```
     * [skill:<id>]
     * <path-or-empty>
     * <rails+template  OR  skill body excerpt>
     *
     * <userInput>
     * ```
     *
     * For free-chat (`null` / `none`): returns trimmed [userInput] only.
     *
     * Bundled skills keep rails + `{input}` template behavior (compatible with
     * [SkillRegistry.apply] body content; marker + optional path lines are added for section-04).
     *
     * FS skills inject path (when known) and full body (truncated to [maxBodyChars]), never
     * reduced to bare keywords.
     *
     * Caller should apply [InputNormalizer.normalize] to [userInput] before calling when desired.
     */
    fun apply(
        skill: SkillRef?,
        userInput: String,
        maxBodyChars: Int = DEFAULT_MAX_BODY_CHARS
    ): String {
        val input = userInput.trim()
        if (skill == null || skill.isNone) return input

        val marker = skillMarker(skill.id)
        val pathLine = skill.path?.takeIf { it.isNotBlank() }

        return when (skill.source) {
            SkillSource.BUNDLED -> applyBundled(skill, input, marker, pathLine)
            SkillSource.USER, SkillSource.PROJECT -> applyFs(skill, input, marker, pathLine, maxBodyChars)
        }
    }

    /**
     * Apply by id against a pre-discovered catalog. Returns [userInput] when id is null/none/missing.
     */
    fun applyById(
        skills: List<SkillRef>,
        skillId: String?,
        userInput: String,
        maxBodyChars: Int = DEFAULT_MAX_BODY_CHARS
    ): String = apply(find(skills, skillId), userInput, maxBodyChars)

    private fun applyBundled(
        skill: SkillRef,
        input: String,
        marker: String,
        pathLine: String?
    ): String {
        val template = skill.template ?: "{input}"
        val body = template.replace("{input}", input)
        val rails = skill.bodyOrRails.trim()
        return buildString {
            append(marker)
            append('\n')
            if (pathLine != null) {
                append(pathLine)
                append('\n')
            }
            if (rails.isNotEmpty()) {
                append(rails)
                append("\n\n")
            }
            append(body)
        }
    }

    private fun applyFs(
        skill: SkillRef,
        input: String,
        marker: String,
        pathLine: String?,
        maxBodyChars: Int
    ): String {
        val body = truncateBody(skill.bodyOrRails, maxBodyChars)
        return buildString {
            append(marker)
            append('\n')
            if (pathLine != null) {
                append(pathLine)
                append('\n')
            }
            // Keep name visible so agents/humans see skill identity without grepping keywords
            append("Skill: ")
            append(skill.name)
            if (skill.description.isNotBlank()) {
                append(" - ")
                append(skill.description.lineSequence().first().trim())
            }
            append("\n\n")
            if (body.isNotEmpty()) {
                append(body)
                append("\n\n")
            }
            append(input)
        }
    }

    /**
     * Truncate large SKILL.md bodies while preserving a coherent prefix for the agent goal.
     * Appends a short notice when truncated.
     */
    fun truncateBody(body: String, maxChars: Int): String {
        val text = body.trim()
        if (maxChars <= 0 || text.length <= maxChars) return text
        val cut = text.substring(0, maxChars).trimEnd()
        return "$cut\n\n...(skill body truncated)"
    }
}
