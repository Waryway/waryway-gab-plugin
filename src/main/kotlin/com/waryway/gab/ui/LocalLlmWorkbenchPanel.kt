package com.waryway.gab.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ui.JBColor
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
 * Local LLM workflow strip: status, presets, corpus collect, rebuild.
 */
class LocalLlmWorkbenchPanel(
    private val settings: WarywayGabSettings,
    private val sessionLog: SessionLog? = null,
    private val onPresetChanged: (String) -> Unit,
    private val onStatusMessage: (String) -> Unit,
    private val onCompactContext: () -> Unit = {},
    private val lastExchange: () -> Pair<String, String>?
) : JPanel(BorderLayout(4, 4)) {

    var contextSize: Int = settings.localLlmContextTokens
        private set

    private val service = LocalLLMService(settings, sessionLog)
    private val statusLabel = JBLabel("Local LLM: checking…").apply {
        font = font.deriveFont(Font.PLAIN, 11f)
        foreground = JBColor.GRAY
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
            add(JButton(AllIcons.Actions.Refresh).apply {
                toolTipText = "Refresh LocalLLM status"
                isBorderPainted = false
                addActionListener { refreshStatus() }
            })
        }

        val row2 = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            isOpaque = false
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
        refreshStatus()
    }

    fun serviceRoot(): String =
        settings.localLlmBaseUrl.removeSuffix("/v1").removeSuffix("/")
            .ifBlank { "http://127.0.0.1:7400" }

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