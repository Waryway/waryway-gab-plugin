package com.waryway.gab.skills

import java.nio.file.Path

/**
 * Pure send-path skill injection shared by chat ([AgentSession]) and LocalLLM agent goals.
 *
 * ## Pattern (Frank-safe)
 *
 * 1. [InputNormalizer.normalize] the **raw composer user text only**
 * 2. [SkillCatalog.apply] skill rails/body + `[skill:<id>]` onto the normalized user part
 * 3. Caller wraps result with [com.waryway.gab.ui.AttachmentPayload.buildMessagePayload]
 * 4. LocalLLM agent path passes that payload into
 *    [com.waryway.gab.chat.LocalLlmAgentSession.buildGoalWithAttachments] (no second strip)
 *
 * Frank never runs on skill body/rails — only on the operator's free text.
 * Pure helpers — no Swing / IDE APIs required for unit tests.
 */
object SkillSendInjection {

    /**
     * Result of prepare/normalize + catalog apply for one Send.
     *
     * @property normalizedUser user text after Frank; empty means "nothing left to send"
     * @property outboundText full skill-injected text for the model/agent goal base
     * @property displaySkillName short name for the chat bubble (`[name] user…`); null when free-chat
     * @property skillId resolved id (`none` when free-chat / missing)
     * @property skill matched [SkillRef], or null for free-chat
     * @property localLlmPreset optional workbench preset from bundled llm-* skills
     */
    data class Prepared(
        val normalizedUser: String,
        val outboundText: String,
        val displaySkillName: String?,
        val skillId: String,
        val skill: SkillRef?,
        val localLlmPreset: String?
    ) {
        /** True when Frank left no substance (caller should refuse Send). */
        val isEmpty: Boolean get() = normalizedUser.isEmpty()

        /** True when a non-`none` skill was applied. */
        val hasSkill: Boolean get() = skill != null && !skill.isNone
    }

    /**
     * Normalize [rawComposerText] with Frank, then inject the skill from [skills] by [skillId].
     *
     * Prefer this overload when the UI already holds a discovered catalog (avoids re-scan).
     */
    fun prepare(
        rawComposerText: String,
        skillId: String?,
        skills: List<SkillRef>
    ): Prepared {
        val normalizedUser = InputNormalizer.normalize(rawComposerText)
        val skill = SkillCatalog.find(skills, skillId)
        val resolvedId = when {
            skill != null && !skill.isNone -> skill.id
            else -> "none"
        }
        // Empty after Frank → empty outbound; caller shows the usual "empty after Frank" dialog.
        val outbound = if (normalizedUser.isEmpty()) {
            ""
        } else {
            SkillCatalog.apply(skill, normalizedUser)
        }
        return Prepared(
            normalizedUser = normalizedUser,
            outboundText = outbound,
            displaySkillName = skill?.takeUnless { it.isNone }?.name,
            skillId = resolvedId,
            skill = skill,
            localLlmPreset = skill?.localLlmPreset
        )
    }

    /**
     * Discover catalog then [prepare]. Used by the tool-window send path when no in-memory
     * catalog is available yet (composer slash catalog lands in wo-02-02).
     */
    fun prepareWithDiscovery(
        rawComposerText: String,
        skillId: String?,
        projectBasePath: String? = null,
        userSkillsRoot: Path? = SkillDiscovery.defaultUserSkillsRoot(),
        projectSkillsRoot: Path? = SkillDiscovery.projectSkillsRoot(projectBasePath)
    ): Prepared {
        val skills = SkillCatalog.discover(
            projectBasePath = projectBasePath,
            userSkillsRoot = userSkillsRoot,
            projectSkillsRoot = projectSkillsRoot
        )
        return prepare(rawComposerText, skillId, skills)
    }

    /**
     * Build short display bubble text: optional `[Skill name] ` prefix + normalized user.
     * Optionally notes Frank compression. Does **not** embed skill body (payload is authoritative).
     */
    fun displayText(
        prepared: Prepared,
        rawComposerText: String,
        includeFrankNote: Boolean = true
    ): String {
        val raw = rawComposerText.trimEnd()
        return buildString {
            prepared.displaySkillName?.let {
                append('[')
                append(it)
                append("] ")
            }
            append(prepared.normalizedUser)
            if (includeFrankNote && prepared.normalizedUser != raw && raw.isNotEmpty()) {
                val removed = (raw.length - prepared.normalizedUser.length).coerceAtLeast(0)
                if (removed > 0) {
                    append("\n\n(frank: ")
                    append(removed)
                    append(" chars removed)")
                }
            }
        }
    }
}
