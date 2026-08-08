package com.waryway.gab.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure unit tests for [ComposerLayoutMetrics] grow-on-demand clamps,
 * min shell / usable-width floors (wo-03-03, wo-02-01).
 */
class ComposerLayoutMetricsTest {

    @Test
    fun `constants are grow-on-demand bounds`() {
        assertEquals(1, ComposerLayoutMetrics.MIN_VISIBLE_ROWS)
        assertEquals(6, ComposerLayoutMetrics.MAX_VISIBLE_ROWS)
        assertEquals(40, ComposerLayoutMetrics.DEFAULT_COLUMNS)
        assertTrue(ComposerLayoutMetrics.MIN_VISIBLE_ROWS < ComposerLayoutMetrics.MAX_VISIBLE_ROWS)
    }

    @Test
    fun `MIN_VISIBLE_ROWS is 1 and MAX_VISIBLE_ROWS is 6`() {
        // Clamp / grow-on-demand contract — must not drift without failing CI.
        assertEquals(1, ComposerLayoutMetrics.MIN_VISIBLE_ROWS)
        assertEquals(6, ComposerLayoutMetrics.MAX_VISIBLE_ROWS)
        assertTrue(ComposerLayoutMetrics.MIN_VISIBLE_ROWS < ComposerLayoutMetrics.MAX_VISIBLE_ROWS)
    }

    @Test
    fun `clampedRows coerces into min max`() {
        assertEquals(1, ComposerLayoutMetrics.clampedRows(0))
        assertEquals(1, ComposerLayoutMetrics.clampedRows(-3))
        assertEquals(3, ComposerLayoutMetrics.clampedRows(3))
        assertEquals(6, ComposerLayoutMetrics.clampedRows(6))
        assertEquals(6, ComposerLayoutMetrics.clampedRows(99))
        // Explicit 1–6 after any metric edits.
        for (r in -2..10) {
            val c = ComposerLayoutMetrics.clampedRows(r)
            assertTrue(c in ComposerLayoutMetrics.MIN_VISIBLE_ROWS..ComposerLayoutMetrics.MAX_VISIBLE_ROWS)
        }
    }

    @Test
    fun `estimateRows empty text is min rows`() {
        assertEquals(ComposerLayoutMetrics.MIN_VISIBLE_ROWS, ComposerLayoutMetrics.estimateRows("", 40))
        assertEquals(ComposerLayoutMetrics.MIN_VISIBLE_ROWS, ComposerLayoutMetrics.estimateRows("", 8))
    }

    @Test
    fun `estimateRows single short line is one row`() {
        assertEquals(1, ComposerLayoutMetrics.estimateRows("hello", 40))
    }

    @Test
    fun `estimateRows hard newlines count`() {
        assertEquals(3, ComposerLayoutMetrics.estimateRows("a\nb\nc", 40))
    }

    @Test
    fun `estimateRows soft wrap at columns`() {
        // 40-char line at 20 columns → 2 rows
        val line = "x".repeat(40)
        assertEquals(2, ComposerLayoutMetrics.estimateRows(line, 20))
    }

    @Test
    fun `estimateRows caps at max visible rows`() {
        val many = (1..20).joinToString("\n") { "line$it" }
        assertEquals(ComposerLayoutMetrics.MAX_VISIBLE_ROWS, ComposerLayoutMetrics.estimateRows(many, 40))
    }

    @Test
    fun `estimateRows uses minimum column floor of 8`() {
        // columns coerced to 8: 16 chars → 2 rows
        assertEquals(2, ComposerLayoutMetrics.estimateRows("x".repeat(16), 1))
        // Floor still holds for zero / negative column hints.
        assertEquals(2, ComposerLayoutMetrics.estimateRows("x".repeat(16), 0))
        assertEquals(2, ComposerLayoutMetrics.estimateRows("x".repeat(16), -4))
        // At coerced-8 cols, 8 chars → 1 row; 9 chars → 2 rows.
        assertEquals(1, ComposerLayoutMetrics.estimateRows("x".repeat(8), 1))
        assertEquals(2, ComposerLayoutMetrics.estimateRows("x".repeat(9), 1))
    }

    @Test
    fun `estimateRows and clampedRows stay within 1 to 6`() {
        val samples = listOf(
            "" to 40,
            "short" to 40,
            "a\nb\nc\nd\ne\nf\ng" to 40,
            "x".repeat(500) to 8,
            "x".repeat(500) to 1,
        )
        for ((text, cols) in samples) {
            val rows = ComposerLayoutMetrics.estimateRows(text, cols)
            assertTrue(
                rows in ComposerLayoutMetrics.MIN_VISIBLE_ROWS..ComposerLayoutMetrics.MAX_VISIBLE_ROWS,
                "estimateRows($text, $cols) = $rows out of 1..6"
            )
        }
    }

    @Test
    fun `preferredViewportHeight scales with rows and insets`() {
        assertEquals(20, ComposerLayoutMetrics.preferredViewportHeight(lineHeight = 10, rows = 2, verticalInsets = 0))
        assertEquals(28, ComposerLayoutMetrics.preferredViewportHeight(lineHeight = 10, rows = 2, verticalInsets = 8))
        // rows clamped to max 6
        assertEquals(60, ComposerLayoutMetrics.preferredViewportHeight(lineHeight = 10, rows = 99, verticalInsets = 0))
        // lineHeight floor
        assertEquals(6, ComposerLayoutMetrics.preferredViewportHeight(lineHeight = 0, rows = 6, verticalInsets = 0))
        // negative insets ignored
        assertEquals(10, ComposerLayoutMetrics.preferredViewportHeight(lineHeight = 10, rows = 1, verticalInsets = -4))
    }

    @Test
    fun `minComposerShellHeightPx reserves one row plus action strip`() {
        val min = ComposerLayoutMetrics.minComposerShellHeightPx(lineHeight = 16, verticalInsets = 4)
        val viewport = ComposerLayoutMetrics.preferredViewportHeight(16, 1, 4)
        assertEquals(
            viewport + ComposerLayoutMetrics.ACTION_STRIP_MIN_HEIGHT_PX + 8,
            min
        )
        assertTrue(min >= ComposerLayoutMetrics.ACTION_STRIP_MIN_HEIGHT_PX + viewport)
        assertTrue(ComposerLayoutMetrics.MIN_USABLE_WIDTH_PX >= 80)
        assertTrue(ComposerLayoutMetrics.ACTION_STRIP_MIN_HEIGHT_PX >= 24)
    }

    @Test
    fun `min shell and usable width floors lock composer against off-screen regression`() {
        // Formula: 1-row viewport + ACTION_STRIP_MIN_HEIGHT_PX + chips + gap.
        val lineHeight = 16
        val verticalInsets = 4
        val gap = 8
        val chips = 0
        val expected =
            ComposerLayoutMetrics.preferredViewportHeight(lineHeight, ComposerLayoutMetrics.MIN_VISIBLE_ROWS, verticalInsets) +
                ComposerLayoutMetrics.ACTION_STRIP_MIN_HEIGHT_PX +
                chips +
                gap
        assertEquals(
            expected,
            ComposerLayoutMetrics.minComposerShellHeightPx(lineHeight, verticalInsets, chips, gap)
        )
        // Default args match typical 11–12pt metrics used by residual budget tests.
        val defaults = ComposerLayoutMetrics.minComposerShellHeightPx()
        assertEquals(
            ComposerLayoutMetrics.minComposerShellHeightPx(16, 4, 0, 8),
            defaults
        )
        assertTrue(defaults > ComposerLayoutMetrics.ACTION_STRIP_MIN_HEIGHT_PX)
        // Usable width floor: prefer ≥ 120 (textarea min band at 320–480 tool windows).
        assertTrue(
            ComposerLayoutMetrics.MIN_USABLE_WIDTH_PX >= 120,
            "MIN_USABLE_WIDTH_PX must stay ≥ 120, was ${ComposerLayoutMetrics.MIN_USABLE_WIDTH_PX}"
        )
        assertTrue(ComposerLayoutMetrics.ACTION_STRIP_MIN_HEIGHT_PX >= 24)
        // Chips raise shell min; negative chips ignored.
        val withChips = ComposerLayoutMetrics.minComposerShellHeightPx(chipsHeight = 24)
        assertEquals(defaults + 24, withChips)
        assertEquals(
            defaults,
            ComposerLayoutMetrics.minComposerShellHeightPx(chipsHeight = -10)
        )
    }
}
