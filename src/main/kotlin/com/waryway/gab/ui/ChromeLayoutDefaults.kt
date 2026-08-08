package com.waryway.gab.ui

/**
 * Pure collapse / preferred-size defaults for tool-window chrome (wo-03-01..03, wo-01-02).
 * No Swing dependency — unit-testable table of layout policy + vertical residual budget.
 *
 * Intended collapsed taxes (message-first shell at idle):
 * - Activity log: header-only ([ACTIVITY_LOG_COLLAPSED_HEADER_TAX_PX]); scroll preferred 0
 * - Usage meter: thin strip ([USAGE_METER_TAX_PX])
 * - Workbench advanced: off ([WORKBENCH_ADVANCED_DEFAULT_EXPANDED]); primary strip only
 * - Secondary north chrome: hidden until gear ([SECONDARY_CHROME_DEFAULT_VISIBLE])
 */
object ChromeLayoutDefaults {

    // --- Activity log ---

    /** Idle default: collapsed header only (message list keeps residual height). */
    const val ACTIVITY_LOG_DEFAULT_EXPANDED = false

    /** Expanded scroll preferred height (px). */
    const val ACTIVITY_LOG_EXPANDED_PREFERRED_HEIGHT = 130

    /** Expanded scroll preferred width (px). */
    const val ACTIVITY_LOG_EXPANDED_PREFERRED_WIDTH = 200

    /** Expanded scroll minimum height (px). */
    const val ACTIVITY_LOG_EXPANDED_MIN_HEIGHT = 40

    /** Collapsed scroll preferred height (px) — zero so parent is header-only. */
    const val ACTIVITY_LOG_COLLAPSED_PREFERRED_HEIGHT = 0

    /**
     * Documented height tax when activity log is collapsed (header strip only).
     * Used by residual budget math / §02; live preferred is measured, not forced.
     */
    const val ACTIVITY_LOG_COLLAPSED_HEADER_TAX_PX = 28

    // --- Usage meter (always-visible SOUTH of messages) ---

    /** Documented height tax for the usage meter strip. */
    const val USAGE_METER_TAX_PX = 28

    // --- Local LLM workbench ---

    /** Advanced presets/collect/rebuild row — collapsed by default. */
    const val WORKBENCH_ADVANCED_DEFAULT_EXPANDED = false

    /**
     * Documented single-row tax for the always-visible workbench primary strip
     * (status + path/agent badges + Advanced toggle) when advanced is collapsed.
     */
    const val WORKBENCH_PRIMARY_ROW_TAX_PX = 32

    /**
     * Cap preferred wrap rows for the workbench primary FlowLayout so a narrow
     * tool window cannot grow the strip into a multi-row fold stealer.
     */
    const val WORKBENCH_PRIMARY_MAX_WRAP_ROWS = 3

    /**
     * Max preferred height for the workbench primary strip (wrap-aware, capped).
     * Advanced row is separate and only contributes when expanded.
     */
    const val WORKBENCH_PRIMARY_MAX_PREFERRED_HEIGHT_PX =
        WORKBENCH_PRIMARY_ROW_TAX_PX * WORKBENCH_PRIMARY_MAX_WRAP_ROWS

    // --- North toolbar ---

    /** Thinking + skill secondary row — hidden until gear toggled. */
    const val SECONDARY_CHROME_DEFAULT_VISIBLE = false

    /** Compact provider combo preferred width (narrow tool windows). */
    const val PROVIDER_COMBO_PREFERRED_WIDTH = 100

    /** Compact model combo preferred width. */
    const val MODEL_COMBO_PREFERRED_WIDTH = 120

    /** Shared control row height hint. */
    const val TOOLBAR_CONTROL_HEIGHT = 24

    /**
     * Documented idle tax for tabs + primary toolbar (secondary hidden).
     * Rough one-line chrome; wrap may add rows at narrow widths.
     */
    const val NORTH_PRIMARY_CHROME_TAX_PX = 56

    // --- Command / timeline panels ---

    /** Shell/tool output blocks in the chat timeline start collapsed. */
    const val COMMAND_OUTPUT_DEFAULT_EXPANDED = false

    /** Expanded command panel preferred size. */
    const val COMMAND_OUTPUT_EXPANDED_PREFERRED_HEIGHT = 140

    const val COMMAND_OUTPUT_EXPANDED_MAX_HEIGHT = 220

    /**
     * Preferred scroll height for the activity log given [expanded].
     * Collapsed → 0 (header-only tax); expanded → usable log height.
     */
    fun activityLogScrollPreferredHeight(expanded: Boolean): Int =
        if (expanded) ACTIVITY_LOG_EXPANDED_PREFERRED_HEIGHT
        else ACTIVITY_LOG_COLLAPSED_PREFERRED_HEIGHT

    /**
     * Preferred scroll min height for the activity log given [expanded].
     */
    fun activityLogScrollMinHeight(expanded: Boolean): Int =
        if (expanded) ACTIVITY_LOG_EXPANDED_MIN_HEIGHT else 0

    /**
     * Whether secondary north chrome (thinking/skill) should start visible.
     * Always false at construction — gear expands on demand.
     */
    fun secondaryChromeInitiallyVisible(): Boolean = SECONDARY_CHROME_DEFAULT_VISIBLE

    /**
     * Collapsed mid-chrome tax under the message list: activity header + usage meter.
     * Pure helper for residual budget / §02 tests.
     */
    fun collapsedActivityUsageTax(): Int =
        ACTIVITY_LOG_COLLAPSED_HEADER_TAX_PX + USAGE_METER_TAX_PX

    /**
     * Residual height for the message list after north chrome, south composer reservation,
     * and mid chrome tax (activity + usage). Pure — no Swing.
     *
     * Returns max(0, contentHeight - northChromeTax - southComposerMin - activityUsageTax).
     */
    fun residualMessageListHeight(
        contentHeight: Int,
        northChromeTax: Int,
        southComposerMin: Int,
        activityUsageTax: Int = 0,
    ): Int {
        val content = contentHeight.coerceAtLeast(0)
        val north = northChromeTax.coerceAtLeast(0)
        val south = southComposerMin.coerceAtLeast(0)
        val mid = activityUsageTax.coerceAtLeast(0)
        return (content - north - south - mid).coerceAtLeast(0)
    }

    /**
     * Rough north FlowLayout wrap estimate for width sanity notes/tests.
     * Counts how many equal-width slots of [itemWidth] fit in [toolWindowWidth]
     * with [gap] between items; returns ceiling of items / per-row capacity (min 1).
     */
    fun estimateFlowWrapRows(toolWindowWidth: Int, itemCount: Int, itemWidth: Int, gap: Int = 4): Int {
        if (itemCount <= 0) return 0
        val w = toolWindowWidth.coerceAtLeast(1)
        val iw = itemWidth.coerceAtLeast(1)
        val g = gap.coerceAtLeast(0)
        val perRow = ((w + g) / (iw + g)).coerceAtLeast(1)
        return (itemCount + perRow - 1) / perRow
    }

    /**
     * Width band classification for documentation / tests.
     * Narrow: 320–480; Comfortable: 600+; Mid: everything else positive.
     */
    enum class WidthBand { NARROW, MID, COMFORTABLE, INVALID }

    fun widthBand(toolWindowWidth: Int): WidthBand = when {
        toolWindowWidth < 1 -> WidthBand.INVALID
        toolWindowWidth in 320..480 -> WidthBand.NARROW
        toolWindowWidth >= 600 -> WidthBand.COMFORTABLE
        else -> WidthBand.MID
    }
}
