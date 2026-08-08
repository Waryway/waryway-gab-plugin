package com.waryway.gab.skills

/**
 * Unified skill reference used by discovery, catalog merge, and apply/injection.
 *
 * @property id stable identifier (directory name for FS skills; [SkillRegistry.GuidedSkill.id] for bundled)
 * @property name display name (frontmatter `name` or guided skill name)
 * @property source where this entry was loaded from
 * @property path absolute path to SKILL.md when known (null for bundled)
 * @property description short description (frontmatter or guided hint)
 * @property bodyOrRails markdown body for FS skills; rails text for bundled guided skills
 * @property template optional `{input}` template (bundled only)
 * @property category free-form category (e.g. code, llm, general)
 * @property hint UI hint text
 * @property localLlmPreset optional Local LLM workbench preset id (bundled llm-* skills)
 */
data class SkillRef(
    val id: String,
    val name: String,
    val source: SkillSource,
    val path: String? = null,
    val description: String = "",
    val bodyOrRails: String = "",
    val template: String? = null,
    val category: String = "general",
    val hint: String = "",
    val localLlmPreset: String? = null
) {
    /** True for free-chat sentinel; apply/injection leaves user text unchanged. */
    val isNone: Boolean get() = id == "none" || id.isBlank()
}
