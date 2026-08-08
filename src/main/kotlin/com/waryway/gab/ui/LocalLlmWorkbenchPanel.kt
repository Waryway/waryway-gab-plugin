package com.waryway.gab.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.waryway.gab.client.LocalLLMService
import com.waryway.gab.diagnostics.SessionLog
import com.waryway.gab.settings.WarywayGabSettings
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JPanel

/**
 * Local LLM workflow strip: one-line status + agent/apply badges by default;
 * presets, collect, rebuild, and secondary controls behind a collapsible Advanced row.
 */
class LocalLlmWorkbenchPanel(
    private val settings: WarywayGabSettings,
    private val sessionLog: SessionLog? = null,
    private val onPresetChanged: (String) -> Unit,
    private val onStatusMessage: (String) -> Unit,
    private val onCompactContext: () -> Unit = {},
    private val lastExchange: () -> Pair<String, String>?,
    /** Fired when agent mode or dry-run/apply changes so parent can refresh Send badge. */
    private val onModeChanged: () -> Unit = {}
) : JPanel(BorderLayout(4, 2)) {

    var contextSize: Int = settings.localLlmContextTokens
        private set

    private val service = LocalLLMService(settings, sessionLog)
    private val statusLabel = JBLabel("Local LLM: checking…").apply {
        font = font.deriveFont(Font.PLAIN, 11f)
        foreground = JBColor.GRAY
    }
    private val dryRunBadge = JBLabel("DRY-RUN").apply {
        font = font.deriveFont(Font.BOLD, 10f)
        foreground = JBColor(0x34D399, 0x34D399)
        toolTipText = "Agent runs post dryRun=true — tools plan/preview only (no writes)"
        isVisible = settings.localLlmAgentMode
    }
    private val pathBadge = JBLabel(
        LocalLlmSendUx.sendPathLabel(settings.localLlmAgentMode, settings.localLlmAgentDryRun)
    ).apply {
        font = font.deriveFont(Font.BOLD, 10f)
        foreground = JBColor(0x69C9FF, 0x69C9FF)
        toolTipText = LocalLlmSendUx.sendPathToolTip(settings.localLlmAgentMode, settings.localLlmAgentDryRun)
    }
    private val sendPathStatus = JBLabel(
        LocalLlmSendUx.sendPathStatusLine(settings.localLlmAgentMode, settings.localLlmAgentDryRun)
    ).apply {
        font = font.deriveFont(Font.PLAIN, 10f)
        foreground = JBColor.GRAY
        toolTipText = LocalLlmSendUx.sendPathToolTip(settings.localLlmAgentMode, settings.localLlmAgentDryRun)
    }
    private val repoRootLabel = JBLabel("").apply {
        font = font.deriveFont(Font.PLAIN, 10f)
        foreground = JBColor.GRAY
        toolTipText = "Server agent workspace root (from last run or empty until a run starts)"
    }
    private val agentModeCheck = JBCheckBox(
        LocalLlmSendUx.AGENT_MODE_CHECK_LABEL,
        settings.localLlmAgentMode
    ).apply {
        toolTipText =
            "On: next Send → /api/agent (plan+tools). Off: next Send → /v1/chat/completions (chat only). " +
                "Not the same as MCP tools. Default is ON."
        font = font.deriveFont(Font.PLAIN, 11f)
    }
    /**
     * Explicit apply opt-in. When unchecked (default), dryRun stays true.
     * Checked → dryRun false only after user gesture.
     */
    private val applyChangesCheck = JBCheckBox("Apply changes", !settings.localLlmAgentDryRun).apply {
        toolTipText =
            "Off (default): dry-run — planned but not written. " +
                "On: dryRun=false, server may write under repoRoot. Requires intentional check."
        font = font.deriveFont(Font.PLAIN, 11f)
        isEnabled = settings.localLlmAgentMode
    }
    private val presetCombo = JComboBox(arrayOf("gab-chat", "concise", "goland", "stack", "corpus", "code-edit"))
    private var corpusCount = 0

    /** Advanced row (presets / collect / rebuild / path detail) — collapsed by default. */
    private var advancedExpanded = ChromeLayoutDefaults.WORKBENCH_ADVANCED_DEFAULT_EXPANDED
    private var advancedRow: JPanel = JPanel()
    private val advancedToggle = JButton("▸ Advanced").apply {
        isBorderPainted = false
        isContentAreaFilled = false
        isFocusPainted = false
        font = font.deriveFont(Font.PLAIN, 11f)
        toolTipText = "Show presets, collect, rebuild, and path detail"
        addActionListener { setAdvancedExpanded(!advancedExpanded) }
    }

    init {
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0),
            JBUI.Borders.empty(4, 4)
        )
        isOpaque = true
        background = GabTheme.panelBackground

        // Always-visible primary strip. Wrap-aware preferred height (FlowLayout undercounts
        // multi-row wrap) with a hard cap so the strip cannot bury the SOUTH composer.
        val primaryRow = wrapAwarePrimaryRow().apply {
            isOpaque = false
            add(JBLabel(AllIcons.Nodes.Plugin).apply { toolTipText = "Offline LLM workbench" })
            add(statusLabel)
            add(pathBadge)
            add(dryRunBadge)
            add(agentModeCheck)
            add(applyChangesCheck)
            add(JButton(AllIcons.Actions.Refresh).apply {
                toolTipText = "Refresh LocalLLM status"
                isBorderPainted = false
                addActionListener { refreshStatus() }
            })
            add(advancedToggle)
        }

        agentModeCheck.addActionListener {
            settings.localLlmAgentMode = agentModeCheck.isSelected
            applyChangesCheck.isEnabled = agentModeCheck.isSelected
            refreshModeBadges()
            sessionLog?.system(
                if (agentModeCheck.isSelected) {
                    "local agent mode ON → /api/agent (${LocalLlmSendUx.sendPathLabel(true, isDryRun())})"
                } else {
                    "local agent mode OFF → /v1/chat/completions (Chat)"
                }
            )
            onModeChanged()
        }
        applyChangesCheck.addActionListener {
            // Invert: checkbox = apply → settings dryRun is opposite
            val apply = applyChangesCheck.isSelected
            if (apply) {
                val confirm = javax.swing.JOptionPane.showConfirmDialog(
                    this@LocalLlmWorkbenchPanel,
                    "Apply mode allows the LocalLLM agent to write files under its repo root.\n" +
                        "Continue with dryRun=false for agent runs?",
                    "Enable apply changes",
                    javax.swing.JOptionPane.YES_NO_OPTION,
                    javax.swing.JOptionPane.WARNING_MESSAGE
                )
                if (confirm != javax.swing.JOptionPane.YES_OPTION) {
                    applyChangesCheck.isSelected = false
                    settings.localLlmAgentDryRun = true
                    refreshModeBadges()
                    onModeChanged()
                    return@addActionListener
                }
            }
            settings.localLlmAgentDryRun = !apply
            refreshModeBadges()
            sessionLog?.system(
                if (settings.localLlmAgentDryRun) "agent dry-run ON (safe) → Next Send: Agent · dry-run"
                else "agent APPLY enabled (dryRun=false) → Next Send: Agent · APPLY"
            )
            onModeChanged()
        }

        val advancedRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            isOpaque = false
            add(sendPathStatus)
            add(repoRootLabel)
            add(JBLabel("Preset:"))
            add(presetCombo)
            presetCombo.selectedItem = settings.localLlmPreset
            presetCombo.addActionListener {
                val p = presetCombo.selectedItem?.toString() ?: return@addActionListener
                settings.localLlmPreset = p
                onPresetChanged(p)
            }
            add(JButton("Compact", AllIcons.Actions.Collapseall).apply {
                toolTipText = "Fold older turns into a rolling summary (smaller prompt for local model)"
                addActionListener { onCompactContext() }
            })
            add(JButton("Collect", AllIcons.Actions.Lightning).apply {
                toolTipText = "Add last Q&A to training corpus"
                addActionListener { collectLast() }
            })
            add(JButton("Rebuild", AllIcons.Actions.Execute).apply {
                toolTipText = "Rebuild model from corpus (POST /api/rebuild)"
                addActionListener { rebuild() }
            })
            add(JButton("Open UI", AllIcons.Toolwindows.ToolWindowPalette).apply {
                toolTipText = "Open LocalLLM web UI"
                addActionListener { BrowserUtil.browse(serviceRoot()) }
            })
        }
        this.advancedRow = advancedRow

        add(primaryRow, BorderLayout.NORTH)
        add(advancedRow, BorderLayout.CENTER)
        setAdvancedExpanded(ChromeLayoutDefaults.WORKBENCH_ADVANCED_DEFAULT_EXPANDED)
        refreshModeBadges()
        refreshStatus()
    }

    fun isAdvancedExpanded(): Boolean = advancedExpanded

    fun setAdvancedExpanded(value: Boolean) {
        advancedExpanded = value
        advancedRow.isVisible = value
        advancedToggle.text = if (value) "▾ Advanced" else "▸ Advanced"
        advancedToggle.toolTipText =
            if (value) "Hide presets, collect, rebuild, and path detail"
            else "Show presets, collect, rebuild, and path detail"
        // Collapsed advanced: keep panel max height cheap (primary strip only, wrap-capped).
        // Expanded: allow advanced row preferred height through.
        maximumSize = if (value) {
            Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        } else {
            val insets = insets
            val maxH = ChromeLayoutDefaults.WORKBENCH_PRIMARY_MAX_PREFERRED_HEIGHT_PX +
                insets.top + insets.bottom + 4
            Dimension(Int.MAX_VALUE, maxH)
        }
        revalidate()
        repaint()
    }

    /**
     * FlowLayout primary strip with wrap-aware preferred height, capped at
     * [ChromeLayoutDefaults.WORKBENCH_PRIMARY_MAX_WRAP_ROWS] so narrow widths
     * allocate correct multi-row space without dominating the fold.
     */
    private fun wrapAwarePrimaryRow(): JPanel =
        object : JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)) {
            override fun getPreferredSize(): Dimension {
                val insets = insets
                val maxInner = (parent?.width?.takeIf { it > 0 }
                    ?: width.takeIf { it > 0 }
                    ?: Int.MAX_VALUE)
                    .let { it - insets.left - insets.right }
                    .coerceAtLeast(1)
                val hgap = 6
                val vgap = 2
                var rowW = 0
                var rowH = 0
                var totalH = 0
                var completedRows = 0
                var maxRowW = 0
                for (i in 0 until componentCount) {
                    val c = getComponent(i)
                    if (!c.isVisible) continue
                    val d = c.preferredSize
                    val next = if (rowW == 0) d.width else rowW + hgap + d.width
                    if (rowW > 0 && next > maxInner) {
                        totalH += rowH + if (completedRows > 0) vgap else 0
                        maxRowW = maxOf(maxRowW, rowW)
                        completedRows++
                        rowW = d.width
                        rowH = d.height
                    } else {
                        rowW = next
                        rowH = maxOf(rowH, d.height)
                    }
                }
                if (rowH > 0) {
                    totalH += rowH + if (completedRows > 0) vgap else 0
                    maxRowW = maxOf(maxRowW, rowW)
                    completedRows++
                }
                if (totalH <= 0) {
                    totalH = ChromeLayoutDefaults.WORKBENCH_PRIMARY_ROW_TAX_PX
                }
                val maxRows = ChromeLayoutDefaults.WORKBENCH_PRIMARY_MAX_WRAP_ROWS
                if (completedRows > maxRows && rowH > 0) {
                    // Cap: first [maxRows] of measured average row height.
                    val avgRow = (totalH - (completedRows - 1) * vgap) / completedRows
                    totalH = avgRow * maxRows + vgap * (maxRows - 1)
                }
                val cap = ChromeLayoutDefaults.WORKBENCH_PRIMARY_MAX_PREFERRED_HEIGHT_PX
                val prefW = maxRowW + insets.left + insets.right
                val prefH = (totalH + insets.top + insets.bottom).coerceAtMost(cap)
                return Dimension(prefW.coerceAtLeast(1), prefH.coerceAtLeast(ChromeLayoutDefaults.WORKBENCH_PRIMARY_ROW_TAX_PX))
            }

            override fun getMinimumSize(): Dimension =
                Dimension(0, ChromeLayoutDefaults.WORKBENCH_PRIMARY_ROW_TAX_PX)
        }

    /** Whether Send should use `/api/agent` for Local LLM. */
    fun isAgentMode(): Boolean = agentModeCheck.isSelected

    /** dryRun flag to POST — true unless user explicitly enabled Apply. */
    fun isDryRun(): Boolean = !applyChangesCheck.isSelected || !agentModeCheck.isSelected

    fun serviceRoot(): String =
        com.waryway.gab.client.AgentClient.normalizeLocalRootUrl(settings.localLlmBaseUrl)
            .ifBlank { "http://127.0.0.1:7400" }

    /** Update dry-run / apply badge and optional repoRoot from a run snapshot. */
    fun updateRunMeta(dryRun: Boolean, repoRoot: String?) {
        javax.swing.SwingUtilities.invokeLater {
            if (dryRun) {
                dryRunBadge.text = "DRY-RUN"
                dryRunBadge.foreground = JBColor(0x34D399, 0x34D399)
                dryRunBadge.toolTipText = "Planned but not written — dryRun=true"
            } else {
                dryRunBadge.text = "APPLY"
                dryRunBadge.foreground = JBColor(0xF59E0B, 0xF59E0B)
                dryRunBadge.toolTipText = "Mutating tools allowed — dryRun=false"
            }
            dryRunBadge.isVisible = agentModeCheck.isSelected
            if (!repoRoot.isNullOrBlank()) {
                val short = if (repoRoot.length > 48) "…" + repoRoot.takeLast(47) else repoRoot
                repoRootLabel.text = "· $short"
                repoRootLabel.toolTipText = "Agent repoRoot: $repoRoot"
            }
        }
    }

    private fun refreshModeBadges() {
        val agent = agentModeCheck.isSelected
        // Sync Apply checkbox from settings before computing dry-run label.
        applyChangesCheck.isSelected = !settings.localLlmAgentDryRun
        applyChangesCheck.isEnabled = agent
        val dry = isDryRun()
        dryRunBadge.isVisible = agent
        if (dry) {
            dryRunBadge.text = "DRY-RUN"
            dryRunBadge.foreground = JBColor(0x34D399, 0x34D399)
            dryRunBadge.toolTipText = "Agent runs post dryRun=true — preview only"
        } else {
            dryRunBadge.text = "APPLY"
            dryRunBadge.foreground = JBColor(0xF59E0B, 0xF59E0B)
            dryRunBadge.toolTipText = "Agent runs post dryRun=false — writes allowed"
        }
        pathBadge.text = LocalLlmSendUx.sendPathLabel(agent, dry)
        pathBadge.toolTipText = LocalLlmSendUx.sendPathToolTip(agent, dry)
        pathBadge.foreground = if (agent) {
            if (dry) JBColor(0x69C9FF, 0x69C9FF) else JBColor(0xF59E0B, 0xF59E0B)
        } else {
            JBColor.GRAY
        }
        sendPathStatus.text = LocalLlmSendUx.sendPathStatusLine(agent, dry)
        sendPathStatus.toolTipText = LocalLlmSendUx.sendPathToolTip(agent, dry)
    }

    fun refreshStatus() {
        Thread {
            try {
                val st = service.fetchStatus()
                contextSize = st.contextSize
                settings.localLlmContextTokens = st.contextSize
                corpusCount = st.corpusCount.coerceAtLeast(service.fetchCorpusCount())
                val text = buildString {
                    append(if (st.ready) "Ready" else "Not ready")
                    append(" · model ")
                    append(st.defaultModel)
                    append(" · corpus ")
                    append(corpusCount)
                    append(" · ctx ")
                    append(st.contextSize)
                }
                javax.swing.SwingUtilities.invokeLater {
                    statusLabel.text = text
                    statusLabel.foreground = if (st.ready) JBColor(0x34D399, 0x34D399) else JBColor.RED
                    if (st.presets.isNotEmpty()) {
                        val current = settings.localLlmPreset
                        presetCombo.removeAllItems()
                        st.presets.forEach { presetCombo.addItem(it.id) }
                        presetCombo.selectedItem = st.presets.find { it.id == current }?.id ?: st.presets.first().id
                    }
                }
            } catch (e: Exception) {
                val detail = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                sessionLog?.error("status refresh failed: $detail")
                javax.swing.SwingUtilities.invokeLater {
                    // Offline is a normal state — keep the workbench visible; do not blank the tool window.
                    statusLabel.text = "Offline — start LocalLLM (scripts\\localllm-run.bat)"
                    statusLabel.foreground = JBColor.RED
                    statusLabel.toolTipText = "Could not reach ${serviceRoot()}: $detail"
                }
            }
        }.start()
    }

    private fun collectLast() {
        val pair = lastExchange() ?: run {
            onStatusMessage("Nothing to collect — chat first.")
            return
        }
        val (question, answer) = pair
        Thread {
            try {
                val ok = service.collectExample(
                    instruction = question.take(500),
                    output = answer.take(2000),
                    tags = listOf("gab-plugin", "goland")
                )
                onStatusMessage(if (ok) "Collected example to corpus ($corpusCount → ${corpusCount + 1})." else "Collect failed.")
                refreshStatus()
            } catch (e: Exception) {
                onStatusMessage("Collect failed: ${e.message}")
            }
        }.start()
    }

    private fun rebuild() {
        Thread {
            try {
                val id = service.startRebuild(force = false)
                onStatusMessage("Rebuild started: $id")
            } catch (e: Exception) {
                onStatusMessage("Rebuild failed: ${e.message}")
            }
        }.start()
    }
}
