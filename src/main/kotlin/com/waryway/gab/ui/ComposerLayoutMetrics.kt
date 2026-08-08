package com.waryway.gab.ui

/**
 * Pure sizing helpers for the main-chat composer (grow-on-demand textarea).
 * Shared with layout tests (wo-03-03); no Swing dependency.
 */
object ComposerLayoutMetrics {
    /** Minimum visible rows when empty or short. */
    const val MIN_VISIBLE_ROWS = 1

    /** Grow with content up to this many rows, then scroll. */
    const val MAX_VISIBLE_ROWS = 6

    /** Fallback columns when width is not yet laid out. */
    const val DEFAULT_COLUMNS = 40

    /**
     * Minimum horizontal space the prompt textarea should keep at narrow tool-window widths
     * (~320–480px). Used as min width on the composer scroll shell.
     */
    const val MIN_USABLE_WIDTH_PX = 120

    /**
     * Floor height for the Stop/Send/badge action strip under the textarea (one control row).
     */
    const val ACTION_STRIP_MIN_HEIGHT_PX = 28

    fun clampedRows(lineCount: Int): Int =
        lineCount.coerceIn(MIN_VISIBLE_ROWS, MAX_VISIBLE_ROWS)

    /**
     * Estimate visible rows from hard newlines and soft wrap at [columns].
     * Empty text → [MIN_VISIBLE_ROWS].
     */
    fun estimateRows(text: String, columns: Int): Int {
        val cols = columns.coerceAtLeast(8)
        if (text.isEmpty()) return MIN_VISIBLE_ROWS
        var total = 0
        for (line in text.split('\n')) {
            val len = line.length.coerceAtLeast(1)
            total += (len + cols - 1) / cols
        }
        return total.coerceIn(MIN_VISIBLE_ROWS, MAX_VISIBLE_ROWS)
    }

    /**
     * Preferred viewport height for [rows] of text at [lineHeight] with vertical insets.
     */
    fun preferredViewportHeight(lineHeight: Int, rows: Int, verticalInsets: Int): Int {
        val lh = lineHeight.coerceAtLeast(1)
        val r = clampedRows(rows)
        return r * lh + verticalInsets.coerceAtLeast(0)
    }

    /**
     * Minimum SOUTH shell reservation for residual budget math: 1-row viewport + action strip
     * (+ optional attachment chips). Pure estimate — live layout still measures components.
     *
     * Default line metrics match a typical 11–12pt monospaced/control font (~16px line).
     */
    fun minComposerShellHeightPx(
        lineHeight: Int = 16,
        verticalInsets: Int = 4,
        chipsHeight: Int = 0,
        gap: Int = 8,
    ): Int =
        preferredViewportHeight(lineHeight, MIN_VISIBLE_ROWS, verticalInsets) +
            ACTION_STRIP_MIN_HEIGHT_PX +
            chipsHeight.coerceAtLeast(0) +
            gap.coerceAtLeast(0)
}
