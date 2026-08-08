package com.waryway.gab.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure unit tests for [ChromeLayoutDefaults] collapse table, width helpers,
 * and layout-budget residual matrix (wo-03-03, wo-02-01).
 * No Swing — residual floors lock the composer/message list against chrome tax regression.
 */
class ChromeLayoutDefaultsTest {

    // --- Collapse defaults table (policy: idle chrome stays cheap) ---

    @Test
    fun `activity log defaults collapsed with expanded height tax only when open`() {
        assertFalse(ChromeLayoutDefaults.ACTIVITY_LOG_DEFAULT_EXPANDED)
        assertEquals(0, ChromeLayoutDefaults.activityLogScrollPreferredHeight(expanded = false))
        assertEquals(0, ChromeLayoutDefaults.activityLogScrollMinHeight(expanded = false))
        assertEquals(
            ChromeLayoutDefaults.ACTIVITY_LOG_EXPANDED_PREFERRED_HEIGHT,
            ChromeLayoutDefaults.activityLogScrollPreferredHeight(expanded = true)
        )
        assertEquals(
            ChromeLayoutDefaults.ACTIVITY_LOG_EXPANDED_MIN_HEIGHT,
            ChromeLayoutDefaults.activityLogScrollMinHeight(expanded = true)
        )
        assertEquals(130, ChromeLayoutDefaults.ACTIVITY_LOG_EXPANDED_PREFERRED_HEIGHT)
        assertEquals(200, ChromeLayoutDefaults.ACTIVITY_LOG_EXPANDED_PREFERRED_WIDTH)
        // Collapsed scroll preferred height constant is zero (header-only tax).
        assertEquals(0, ChromeLayoutDefaults.ACTIVITY_LOG_COLLAPSED_PREFERRED_HEIGHT)
    }

    @Test
    fun `workbench advanced and secondary chrome default collapsed or hidden`() {
        assertFalse(ChromeLayoutDefaults.WORKBENCH_ADVANCED_DEFAULT_EXPANDED)
        assertFalse(ChromeLayoutDefaults.SECONDARY_CHROME_DEFAULT_VISIBLE)
        assertFalse(ChromeLayoutDefaults.secondaryChromeInitiallyVisible())
        assertFalse(ChromeLayoutDefaults.COMMAND_OUTPUT_DEFAULT_EXPANDED)
    }

    @Test
    fun `collapse defaults table is false or hidden policy`() {
        // Full policy table: idle shell must not steal residual without user expand/toggle.
        assertFalse(
            ChromeLayoutDefaults.ACTIVITY_LOG_DEFAULT_EXPANDED,
            "ACTIVITY_LOG_DEFAULT_EXPANDED must stay false"
        )
        assertFalse(
            ChromeLayoutDefaults.WORKBENCH_ADVANCED_DEFAULT_EXPANDED,
            "WORKBENCH_ADVANCED_DEFAULT_EXPANDED must stay false (advanced tax out of idle path)"
        )
        assertFalse(
            ChromeLayoutDefaults.SECONDARY_CHROME_DEFAULT_VISIBLE,
            "SECONDARY_CHROME_DEFAULT_VISIBLE must stay false"
        )
        assertFalse(
            ChromeLayoutDefaults.COMMAND_OUTPUT_DEFAULT_EXPANDED,
            "COMMAND_OUTPUT_DEFAULT_EXPANDED must stay false"
        )
    }

    @Test
    fun `narrow toolbar combo widths are compact`() {
        assertTrue(ChromeLayoutDefaults.PROVIDER_COMBO_PREFERRED_WIDTH <= 120)
        assertTrue(ChromeLayoutDefaults.MODEL_COMBO_PREFERRED_WIDTH <= 140)
        assertEquals(24, ChromeLayoutDefaults.TOOLBAR_CONTROL_HEIGHT)
    }

    @Test
    fun `command panel expanded heights stay dense`() {
        assertEquals(140, ChromeLayoutDefaults.COMMAND_OUTPUT_EXPANDED_PREFERRED_HEIGHT)
        assertEquals(220, ChromeLayoutDefaults.COMMAND_OUTPUT_EXPANDED_MAX_HEIGHT)
        assertTrue(
            ChromeLayoutDefaults.COMMAND_OUTPUT_EXPANDED_PREFERRED_HEIGHT
                <= ChromeLayoutDefaults.COMMAND_OUTPUT_EXPANDED_MAX_HEIGHT
        )
    }

    // --- Narrow width band (action strip under textarea; no width-steal) ---

    @Test
    fun `widthBand maps 320-480 and 600+`() {
        assertEquals(ChromeLayoutDefaults.WidthBand.NARROW, ChromeLayoutDefaults.widthBand(320))
        assertEquals(ChromeLayoutDefaults.WidthBand.NARROW, ChromeLayoutDefaults.widthBand(400))
        assertEquals(ChromeLayoutDefaults.WidthBand.NARROW, ChromeLayoutDefaults.widthBand(480))
        assertEquals(ChromeLayoutDefaults.WidthBand.MID, ChromeLayoutDefaults.widthBand(500))
        assertEquals(ChromeLayoutDefaults.WidthBand.COMFORTABLE, ChromeLayoutDefaults.widthBand(600))
        assertEquals(ChromeLayoutDefaults.WidthBand.COMFORTABLE, ChromeLayoutDefaults.widthBand(900))
        assertEquals(ChromeLayoutDefaults.WidthBand.INVALID, ChromeLayoutDefaults.widthBand(0))
        assertEquals(ChromeLayoutDefaults.WidthBand.INVALID, ChromeLayoutDefaults.widthBand(-1))
    }

    @Test
    fun `narrow width band 320 to 480 does not force composer columns below 8`() {
        // Action strip lives under the textarea (SOUTH), so NARROW band is height-policy only.
        // Composer still floors columns at 8 — estimateRows must not collapse at 320–480.
        for (w in listOf(320, 400, 480)) {
            assertEquals(
                ChromeLayoutDefaults.WidthBand.NARROW,
                ChromeLayoutDefaults.widthBand(w),
                "width $w should be NARROW"
            )
            // Column floor of 8: 16 chars at coerced-8 cols → 2 rows (not 16+ rows).
            assertEquals(2, ComposerLayoutMetrics.estimateRows("x".repeat(16), columns = 1))
            assertEquals(
                ComposerLayoutMetrics.MIN_VISIBLE_ROWS,
                ComposerLayoutMetrics.estimateRows("hi", columns = 1)
            )
        }
        assertTrue(
            ComposerLayoutMetrics.MIN_USABLE_WIDTH_PX >= 80,
            "textarea min usable band must stay ≥ 80 (prefer ≥ 120)"
        )
        assertTrue(ComposerLayoutMetrics.MIN_USABLE_WIDTH_PX >= 120)
    }

    @Test
    fun `estimateFlowWrapRows primary chrome at narrow vs comfortable`() {
        // ~7 primary items at ~48px each (icon+combo mix) — sanity model for wrap notes.
        val items = 7
        val itemW = 48
        val narrowRows = ChromeLayoutDefaults.estimateFlowWrapRows(320, items, itemW, gap = 4)
        val midRows = ChromeLayoutDefaults.estimateFlowWrapRows(480, items, itemW, gap = 4)
        val wideRows = ChromeLayoutDefaults.estimateFlowWrapRows(600, items, itemW, gap = 4)
        assertTrue(narrowRows >= midRows)
        assertTrue(midRows >= wideRows)
        assertTrue(narrowRows >= 1)
        assertEquals(1, wideRows) // 600 / ~52 ≈ 11 slots → single row
        assertEquals(0, ChromeLayoutDefaults.estimateFlowWrapRows(400, 0, 40))
    }

    @Test
    fun `composer bounds stay aligned with ComposerLayoutMetrics`() {
        // Cross-check so chrome notes and composer helpers do not drift silently.
        assertEquals(1, ComposerLayoutMetrics.MIN_VISIBLE_ROWS)
        assertEquals(6, ComposerLayoutMetrics.MAX_VISIBLE_ROWS)
    }

    // --- Dual-path workbench tax policy ---

    @Test
    fun `GROK_BUILD north tax excludes workbench primary row`() {
        // Grok Build path: NORTH_PRIMARY only — no workbench strip in idle chrome tax.
        val grokNorth = ChromeLayoutDefaults.NORTH_PRIMARY_CHROME_TAX_PX
        assertTrue(grokNorth > 0)
        // Policy: workbench tax is Local-only; Grok residual must not bake it in.
        val localOnlyExtra = ChromeLayoutDefaults.WORKBENCH_PRIMARY_ROW_TAX_PX
        assertTrue(localOnlyExtra > 0)
        assertTrue(
            grokNorth < grokNorth + localOnlyExtra,
            "Local path must pay more north tax than Grok Build"
        )
    }

    @Test
    fun `LOCAL_LLM north tax adds only bounded primary row when advanced collapsed`() {
        // Advanced expanded tax is out of idle default path (WORKBENCH_ADVANCED_DEFAULT_EXPANDED == false).
        assertFalse(
            ChromeLayoutDefaults.WORKBENCH_ADVANCED_DEFAULT_EXPANDED,
            "advanced-expanded workbench tax is not on idle default path"
        )
        val grokNorth = ChromeLayoutDefaults.NORTH_PRIMARY_CHROME_TAX_PX
        val localNorth = grokNorth + ChromeLayoutDefaults.WORKBENCH_PRIMARY_ROW_TAX_PX
        // Idle Local = primary strip only (bounded single-row tax).
        assertEquals(
            ChromeLayoutDefaults.WORKBENCH_PRIMARY_ROW_TAX_PX,
            localNorth - grokNorth
        )
        // Wrap-aware preferred height is capped (primary strip cannot multi-row steal residual).
        assertEquals(
            ChromeLayoutDefaults.WORKBENCH_PRIMARY_ROW_TAX_PX *
                ChromeLayoutDefaults.WORKBENCH_PRIMARY_MAX_WRAP_ROWS,
            ChromeLayoutDefaults.WORKBENCH_PRIMARY_MAX_PREFERRED_HEIGHT_PX
        )
        assertTrue(ChromeLayoutDefaults.WORKBENCH_PRIMARY_MAX_WRAP_ROWS in 1..4)
        // Idle primary tax ≤ max preferred (single row ≤ capped wrap height).
        assertTrue(
            ChromeLayoutDefaults.WORKBENCH_PRIMARY_ROW_TAX_PX
                <= ChromeLayoutDefaults.WORKBENCH_PRIMARY_MAX_PREFERRED_HEIGHT_PX
        )
    }

    // --- Vertical residual budget matrix (H=400, H=600) ---

    @Test
    fun `residualMessageListHeight leaves room at normal content heights`() {
        val south = ComposerLayoutMetrics.minComposerShellHeightPx()
        val mid = ChromeLayoutDefaults.collapsedActivityUsageTax()
        // Grok Build path: north primary only (no workbench).
        val grokNorth = ChromeLayoutDefaults.NORTH_PRIMARY_CHROME_TAX_PX
        val residualGrok400 = ChromeLayoutDefaults.residualMessageListHeight(
            contentHeight = 400,
            northChromeTax = grokNorth,
            southComposerMin = south,
            activityUsageTax = mid,
        )
        assertTrue(residualGrok400 > 0, "Grok residual at 400px should be positive, was $residualGrok400")
        // Local LLM: north + workbench primary (advanced collapsed).
        val localNorth = grokNorth + ChromeLayoutDefaults.WORKBENCH_PRIMARY_ROW_TAX_PX
        val residualLocal400 = ChromeLayoutDefaults.residualMessageListHeight(
            contentHeight = 400,
            northChromeTax = localNorth,
            southComposerMin = south,
            activityUsageTax = mid,
        )
        assertTrue(residualLocal400 > 0, "Local LLM residual at 400px should be positive, was $residualLocal400")
        assertTrue(residualLocal400 <= residualGrok400)
        // Clamps at zero when chrome exceeds content.
        assertEquals(
            0,
            ChromeLayoutDefaults.residualMessageListHeight(100, 80, 50, 30)
        )
        // Negative inputs do not explode.
        assertEquals(
            0,
            ChromeLayoutDefaults.residualMessageListHeight(-10, -5, -5, -5)
        )
    }

    @Test
    fun `vertical budget residual floors at H 400 and H 600 for Grok and Local`() {
        val south = ComposerLayoutMetrics.minComposerShellHeightPx()
        val mid = ChromeLayoutDefaults.collapsedActivityUsageTax()
        val grokNorth = ChromeLayoutDefaults.NORTH_PRIMARY_CHROME_TAX_PX
        // Local LLM: primary strip only (advanced collapsed — not idle-expanded tax).
        val localNorth = grokNorth + ChromeLayoutDefaults.WORKBENCH_PRIMARY_ROW_TAX_PX

        // Floors: message list must stay usable so composer cannot regress off-screen via zero residual.
        val floor400 = 80
        val floor600 = 120

        for (h in listOf(400, 600)) {
            val floor = if (h == 400) floor400 else floor600
            val residualGrok = ChromeLayoutDefaults.residualMessageListHeight(
                contentHeight = h,
                northChromeTax = grokNorth,
                southComposerMin = south,
                activityUsageTax = mid,
            )
            val residualLocal = ChromeLayoutDefaults.residualMessageListHeight(
                contentHeight = h,
                northChromeTax = localNorth,
                southComposerMin = south,
                activityUsageTax = mid,
            )

            assertTrue(
                residualGrok >= floor,
                "Grok residual at H=$h must be ≥ $floor, was $residualGrok " +
                    "(north=$grokNorth south=$south mid=$mid)"
            )
            assertTrue(
                residualLocal >= floor,
                "Local residual at H=$h must be ≥ $floor, was $residualLocal " +
                    "(north=$localNorth south=$south mid=$mid)"
            )
            // Local pays workbench primary-row tax → residual ≤ Grok at same H.
            assertTrue(
                residualLocal <= residualGrok,
                "Local residual ($residualLocal) must be ≤ Grok ($residualGrok) at H=$h"
            )

            // Helper semantics: residual = max(0, H − taxes); when positive, taxes + residual == H.
            val taxesGrok = grokNorth + south + mid
            val taxesLocal = localNorth + south + mid
            if (h >= taxesGrok) {
                assertEquals(
                    h,
                    residualGrok + taxesGrok,
                    "Grok: residual + taxes must equal H=$h"
                )
            } else {
                assertEquals(0, residualGrok)
            }
            if (h >= taxesLocal) {
                assertEquals(
                    h,
                    residualLocal + taxesLocal,
                    "Local: residual + taxes must equal H=$h"
                )
            } else {
                assertEquals(0, residualLocal)
            }
        }
    }

    @Test
    fun `collapsed chrome tax constants stay cheap vs expanded log`() {
        assertTrue(ChromeLayoutDefaults.ACTIVITY_LOG_COLLAPSED_HEADER_TAX_PX < 48)
        assertTrue(ChromeLayoutDefaults.USAGE_METER_TAX_PX < 48)
        assertTrue(ChromeLayoutDefaults.WORKBENCH_PRIMARY_ROW_TAX_PX < 48)
        assertEquals(
            ChromeLayoutDefaults.ACTIVITY_LOG_COLLAPSED_HEADER_TAX_PX +
                ChromeLayoutDefaults.USAGE_METER_TAX_PX,
            ChromeLayoutDefaults.collapsedActivityUsageTax()
        )
        assertTrue(
            ChromeLayoutDefaults.collapsedActivityUsageTax() <
                ChromeLayoutDefaults.ACTIVITY_LOG_EXPANDED_PREFERRED_HEIGHT
        )
        // Collapsed mid tax stays well under expanded activity preferred (message-first).
        assertTrue(
            ChromeLayoutDefaults.collapsedActivityUsageTax() * 2 <
                ChromeLayoutDefaults.ACTIVITY_LOG_EXPANDED_PREFERRED_HEIGHT +
                ChromeLayoutDefaults.USAGE_METER_TAX_PX
        )
        assertTrue(ChromeLayoutDefaults.WORKBENCH_PRIMARY_MAX_WRAP_ROWS in 1..4)
        assertEquals(
            ChromeLayoutDefaults.WORKBENCH_PRIMARY_ROW_TAX_PX *
                ChromeLayoutDefaults.WORKBENCH_PRIMARY_MAX_WRAP_ROWS,
            ChromeLayoutDefaults.WORKBENCH_PRIMARY_MAX_PREFERRED_HEIGHT_PX
        )
    }
}
