package com.waryway.gab.skills

/**
 * Origin of a discovered skill entry.
 *
 * Merge precedence when the same [SkillRef.id] appears in more than one source:
 * **project > user > bundled** (see [SkillCatalog.merge]).
 */
enum class SkillSource {
    /** Hard-coded [SkillRegistry.GuidedSkill] entries (explain, tests, implement, …, none). */
    BUNDLED,

    /** `%USERPROFILE%\.grok\skills` / `~/.grok/skills` filesystem skills. */
    USER,

    /** `{projectBasePath}/.grok/skills` filesystem skills (optional; missing dir = empty). */
    PROJECT
}
