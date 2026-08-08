package com.waryway.gab.ui

/**
 * Pure helpers for presenting shell / terminal tool results in the chat UI.
 * Keep formatting logic here so unit tests do not need Swing.
 */
object CommandOutputUi {

    const val DEFAULT_UI_OUTPUT_CAP: Int = 24_000

    /**
     * Compact one-line summary for a collapsed command block header.
     * Prefers `exit=N` from the first line of [output] when present.
     */
    fun headerSummary(command: String, output: String): String {
        val cmd = command.trim().ifBlank { "(command)" }
        val shortCmd = if (cmd.length > 72) cmd.take(69) + "…" else cmd
        val exit = extractExitCode(output)
        val lines = nonBlankLineCount(output)
        val exitPart = exit?.let { "exit=$it" } ?: "done"
        val linePart = when {
            lines <= 0 -> "no output"
            lines == 1 -> "1 line"
            else -> "$lines lines"
        }
        return "▸ cmd: $shortCmd  ·  $exitPart · $linePart"
    }

    /** Soft-cap body for the expandable panel; full text still goes to the model / activity log. */
    fun bodyForUi(output: String, maxChars: Int = DEFAULT_UI_OUTPUT_CAP): String {
        val trimmed = output.trimEnd()
        if (trimmed.isEmpty()) return "(no output)"
        if (trimmed.length <= maxChars) return trimmed
        val omitted = trimmed.length - maxChars
        return trimmed.take(maxChars) + "\n… ($omitted more chars truncated in UI)"
    }

    fun extractExitCode(output: String): Int? {
        val first = output.lineSequence().firstOrNull()?.trim().orEmpty()
        val m = Regex("""^exit\s*=\s*(-?\d+)""", RegexOption.IGNORE_CASE).find(first)
        return m?.groupValues?.get(1)?.toIntOrNull()
    }

    fun nonBlankLineCount(output: String): Int =
        output.lineSequence().count { it.isNotBlank() }
}
