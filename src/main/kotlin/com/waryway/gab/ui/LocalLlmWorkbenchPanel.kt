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
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JPanel

/**
 * Local LLM workflow strip: status, presets, corpus collect, rebuild,
 * plus agent-mode / dry-run controls for /api/agent.
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
) : JPanel(BorderLayout(4, 4)) {

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

    init {
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0),
            JBUI.Borders.empty(6, 4)
        )
        isOpaque = true
        background = GabTheme.panelBackground

        val row1 = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            isOpaque = false
            add(JBLabel(AllIcons.Nodes.Plugin).apply { toolTipText = "Offline LLM workbench" })
            add(statusLabel)
            add(pathBadge)
            add(dryRunBadge)
            add(repoRootLabel)
            add(JButton(AllIcons.Actions.Refresh).apply {
                toolTipText = "Refresh LocalLLM status"
                isBorderPainted = false
                addActionListener { refreshStatus() }
            })
        }

        val row2 = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            isOpaque = false
            add(agentModeCheck)
            add(applyChangesCheck)
            add(sendPathStatus)
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

        add(row1, BorderLayout.NORTH)
        add(row2, BorderLayout.CENTER)
        refreshModeBadges()
        refreshStatus()
    }

    /** Whether Send should use `/api/agent` for Local LLM. */
    fun isAgentMode(): Boolean = agentModeCheck.isSelected

    /** dryRun flag to POST — true unless user explicitly enabled Apply. */
    fun isDryRun(): Boolean = !applyChangesCheck.isSelected || !agentModeCheck.isSelected

    fun serviceRoot(): String =
        settings.localLlmBaseUrl.removeSuffix("/v1").removeSuffix("/")
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
                sessionLog?.error("status refresh failed: ${e.message}")
                javax.swing.SwingUtilities.invokeLater {
                    statusLabel.text = "Offline — run scripts\\localllm-run.bat"
                    statusLabel.foreground = JBColor.RED
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
