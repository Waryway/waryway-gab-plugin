package com.waryway.gab.ui

import com.waryway.gab.skills.SkillRef
import com.waryway.gab.skills.SkillSource

/**
 * Pure helpers for composer slash `/` skill autocomplete.
 * No Swing — unit-tested without IDE.
 *
 * A slash token is `/` at the start of the document, start of a line, or after whitespace,
 * followed by an optional query of non-whitespace characters (id/name fragment).
 */
object SkillSlashQuery {

    /**
     * Active slash token under (or ending at) the caret.
     *
     * @property startOffset inclusive index of `/`
     * @property endOffset exclusive end of the token (query end)
     * @property query text after `/` (may be empty)
     */
    data class ActiveSlash(
        val startOffset: Int,
        val endOffset: Int,
        val query: String
    )

    /**
     * Find the slash token that the caret is inside or immediately after.
     * Returns null when the caret is not on a valid `/query` token.
     *
     * Examples (caret as `|`):
     * - `|/` → null (caret before slash)
     * - `/|` → query ""
     * - `/ce|o` → query "ceo"
     * - `hi /ex|` → query "ex"
     * - `a/b|` → null (slash not after whitespace)
     */
    fun findActiveSlash(text: String, caretOffset: Int): ActiveSlash? {
        if (text.isEmpty()) return null
        val caret = caretOffset.coerceIn(0, text.length)
        if (caret == 0) return null

        // Character immediately before caret must be part of the token (`/` or query chars)
        val before = text[caret - 1]
        if (isTokenBoundary(before)) return null

        // Walk left to token start
        var tokenStart = caret - 1
        while (tokenStart > 0 && !isTokenBoundary(text[tokenStart - 1])) {
            tokenStart--
        }

        if (text[tokenStart] != '/') return null
        // Slash must be at start or after whitespace/newline
        if (tokenStart > 0 && !isTokenBoundary(text[tokenStart - 1])) return null

        var tokenEnd = tokenStart + 1
        while (tokenEnd < text.length && !isTokenBoundary(text[tokenEnd])) {
            tokenEnd++
        }

        // Caret must lie within the token (inclusive of end = still editing)
        if (caret < tokenStart || caret > tokenEnd) return null

        val query = if (tokenEnd > tokenStart + 1) {
            text.substring(tokenStart + 1, tokenEnd)
        } else {
            ""
        }
        return ActiveSlash(startOffset = tokenStart, endOffset = tokenEnd, query = query)
    }

    private fun isTokenBoundary(c: Char): Boolean =
        c.isWhitespace()

    /**
     * Filter [skills] by [query] against id and name (case-insensitive).
     * Empty query returns all skills (including free-chat `none` when present).
     * Ranking: id prefix > name prefix > id contains > name contains.
     */
    fun filterSkills(skills: List<SkillRef>, query: String): List<SkillRef> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return skills
        data class Ranked(val rank: Int, val skill: SkillRef)
        val ranked = skills.mapNotNull { skill ->
            val id = skill.id.lowercase()
            val name = skill.name.lowercase()
            val rank = when {
                id.startsWith(q) -> 0
                name.startsWith(q) -> 1
                id.contains(q) -> 2
                name.contains(q) -> 3
                else -> return@mapNotNull null
            }
            Ranked(rank, skill)
        }
        return ranked.sortedWith(compareBy({ it.rank }, { it.skill.id.lowercase() })).map { it.skill }
    }

    /**
     * Remove the slash token from [text]. Leaves surrounding prompt text intact.
     * Does not insert a skill marker into the composer — selection is stored separately
     * and applied on Send (see SkillCatalog.apply).
     *
     * @return pair of (newText, newCaretOffset)
     */
    fun removeSlashToken(text: String, slash: ActiveSlash): Pair<String, Int> {
        val before = text.substring(0, slash.startOffset)
        val after = if (slash.endOffset < text.length) text.substring(slash.endOffset) else ""
        // Avoid double-space when token sat between two spaced words: "hi /ceo there" → "hi there"
        val newText = if (before.endsWith(' ') && after.startsWith(' ')) {
            before + after.drop(1)
        } else {
            before + after
        }
        val newCaret = before.length.coerceIn(0, newText.length)
        return newText to newCaret
    }

    /** Short list label: `id — name` with optional source badge. */
    fun listLabel(skill: SkillRef, includeSource: Boolean = true): String {
        val base = if (skill.isNone || skill.name.equals(skill.id, ignoreCase = true)) {
            skill.name.ifBlank { skill.id }
        } else {
            "${skill.id} — ${skill.name}"
        }
        return if (includeSource && !skill.isNone) {
            "$base  (${sourceBadge(skill.source)})"
        } else {
            base
        }
    }

    fun sourceBadge(source: SkillSource): String = when (source) {
        SkillSource.BUNDLED -> "bundled"
        SkillSource.USER -> "user"
        SkillSource.PROJECT -> "project"
    }

    /** Combo / status label for a skill. */
    fun comboLabel(skill: SkillRef): String = when {
        skill.isNone -> skill.name.ifBlank { "(free chat)" }
        skill.source == SkillSource.BUNDLED -> skill.name
        else -> "${skill.name} [${skill.id}]"
    }
}
