package com.waryway.gab.skills

/**
 * Deterministic input compression applied to every user message before it is sent to Gab AI.
 *
 * "Frank" removes low-signal words (articles, hedging phrases) via regex so behaviour is
 * predictable and does not consume model tokens for an LLM-based rewrite step.
 */
object InputNormalizer {

    /** Standalone articles: a, an, the */
    private val ARTICLE_PATTERN = Regex("""\b(?:a|an|the)\b""", RegexOption.IGNORE_CASE)

    /**
     * Hedging / filler phrases commonly used when users already know the answer.
     * Applied repeatedly until no pattern matches (handles stacked fillers).
     */
    private val FILLER_PATTERNS = listOf(
        Regex("""(?i)\b(?:i\s+(?:think|thought|believe|guess|mean|figured(?:\s+it)?\s+out|realized|realised|noticed|found(?:\s+(?:that|out))?|worked\s+out|sorted\s+out))\b[,.]?\s*"""),
        Regex("""(?i)\b(?:basically|actually|honestly|literally|obviously|clearly|simply|just|really|very)\b[,.]?\s*"""),
        Regex("""(?i)\b(?:to\s+be\s+(?:honest|clear|fair|frank))\b[,.]?\s*"""),
        Regex("""(?i)\b(?:in\s+my\s+(?:opinion|view)|imo|tbh|fyi)\b[,.]?\s*"""),
        Regex("""(?i)\b(?:you\s+know|kind\s+of|sort\s+of|more\s+or\s+less)\b[,.]?\s*"""),
        Regex("""(?i)\b(?:so\s+yeah|long\s+story\s+short|bottom\s+line)\b[,.]?\s*"""),
    )

    private val MULTI_SPACE = Regex("""[ \t]{2,}""")
    private val SPACE_BEFORE_PUNCT = Regex("""\s+([,.!?;:])""")
    private val BLANK_LINES = Regex("""\n{3,}""")

    /**
     * Returns a trimmed, compressed version of [text]. Empty result means the message had
     * no substantive content after normalization.
     */
    fun normalize(text: String): String {
        var result = text.trim()
        if (result.isEmpty()) return result

        var changed: Boolean
        do {
            changed = false
            for (pattern in FILLER_PATTERNS) {
                val next = pattern.replace(result, "")
                if (next != result) {
                    result = next
                    changed = true
                }
            }
        } while (changed)

        result = ARTICLE_PATTERN.replace(result, "")
        result = MULTI_SPACE.replace(result, " ")
        result = SPACE_BEFORE_PUNCT.replace(result, "$1")
        result = BLANK_LINES.replace(result, "\n\n")

        // Tidy line-leading spaces left after article removal
        result = result.lines().joinToString("\n") { it.trim().replace(Regex("""\s{2,}"""), " ") }

        return result.trim()
    }
}