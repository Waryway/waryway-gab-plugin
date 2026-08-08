package com.waryway.gab.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.waryway.gab.diagnostics.SessionLog
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.datatransfer.StringSelection
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities

/**
 * Collapsible monospace log for HTTP, SSE, tool, and system diagnostics.
 * Supports copy, clear, and export-fail hooks wired by the tool window.
 *
 * Defaults to collapsed (header-only ~24–32px) so the message list keeps vertical space.
 */
class ActivityLogPanel : JPanel(BorderLayout()) {

    var onClearRequested: (() -> Unit)? = null
    var onExportFailRequested: (() -> Unit)? = null
    var onCopyLogPathRequested: (() -> Unit)? = null

    private val logArea = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 11)
        background = JBColor(Color(0xF8F8F8), Color(0x1A1D24))
        foreground = JBColor(Color(0x2D3748), Color(0xC8D0E0))
        border = JBUI.Borders.empty(4, 6)
    }

    private val scrollPane = JBScrollPane(logArea).apply {
        border = BorderFactory.createLineBorder(JBColor.border())
        verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        preferredSize = Dimension(
            ChromeLayoutDefaults.ACTIVITY_LOG_EXPANDED_PREFERRED_WIDTH,
            ChromeLayoutDefaults.ACTIVITY_LOG_EXPANDED_PREFERRED_HEIGHT
        )
    }

    private val pathLabel = JBLabel(" ").apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, 10)
        foreground = GabTheme.inactiveText
        toolTipText = "Durable session log path (copy via button)"
    }

    /** Collapsed by default — idle preferred height is header-only, not the expanded log tax. */
    private var expanded = ChromeLayoutDefaults.ACTIVITY_LOG_DEFAULT_EXPANDED
    private val toggleButton = JButton("Show log", AllIcons.General.ArrowRight).apply {
        isBorderPainted = false
        font = font.deriveFont(Font.PLAIN, 11f)
        addActionListener { setExpanded(!expanded) }
    }

    init {
        border = JBUI.Borders.emptyTop(4)
        isOpaque = false

        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JBLabel("Activity log").apply {
                font = font.deriveFont(Font.BOLD, 11f)
                foreground = GabTheme.inactiveText
            }, BorderLayout.WEST)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0)).apply {
                isOpaque = false
                add(JButton("Export fail", AllIcons.Actions.MenuSaveall).apply {
                    toolTipText =
                        "Write fail package (trigger + logs + paths) for another agent to evaluate"
                    isBorderPainted = false
                    font = font.deriveFont(Font.PLAIN, 11f)
                    addActionListener { onExportFailRequested?.invoke() }
                })
                add(JButton(AllIcons.Actions.Copy).apply {
                    toolTipText = "Copy log text"
                    isBorderPainted = false
                    preferredSize = Dimension(24, 22)
                    addActionListener {
                        CopyPasteManager.getInstance().setContents(StringSelection(logArea.text))
                    }
                })
                add(JButton(AllIcons.Actions.Show).apply {
                    toolTipText = "Copy session log file path to clipboard"
                    isBorderPainted = false
                    preferredSize = Dimension(24, 22)
                    addActionListener { onCopyLogPathRequested?.invoke() }
                })
                add(JButton("Clear", AllIcons.Actions.GC).apply {
                    toolTipText = "Clear log"
                    isBorderPainted = false
                    font = font.deriveFont(Font.PLAIN, 11f)
                    addActionListener { onClearRequested?.invoke() ?: clear() }
                })
                add(toggleButton)
            }, BorderLayout.EAST)
        }

        val south = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(2)
            add(pathLabel, BorderLayout.CENTER)
        }

        add(header, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
        add(south, BorderLayout.SOUTH)
        // Apply collapsed preferred sizes so layout never taxes messages on first paint.
        setExpanded(ChromeLayoutDefaults.ACTIVITY_LOG_DEFAULT_EXPANDED)
    }

    fun isExpanded(): Boolean = expanded

    fun setLogPathDisplay(path: String?) {
        SwingUtilities.invokeLater {
            pathLabel.text = if (path.isNullOrBlank()) " " else "log: $path"
            pathLabel.toolTipText = path
        }
    }

    fun appendLine(line: String) {
        if (line == SessionLog.CLEAR_SENTINEL) {
            clear()
            return
        }
        SwingUtilities.invokeLater {
            if (logArea.text.isNotEmpty()) logArea.append("\n")
            logArea.append(line)
            scrollToBottom()
        }
    }

    fun clear() {
        SwingUtilities.invokeLater {
            logArea.text = ""
        }
    }

    fun setExpanded(value: Boolean) {
        expanded = value
        scrollPane.isVisible = value
        pathLabel.isVisible = value
        // Expanded: usable log height. Collapsed: zero preferred so parent is header-only (~24–32px).
        val prefH = ChromeLayoutDefaults.activityLogScrollPreferredHeight(value)
        val minH = ChromeLayoutDefaults.activityLogScrollMinHeight(value)
        val prefW = if (value) ChromeLayoutDefaults.ACTIVITY_LOG_EXPANDED_PREFERRED_WIDTH else 0
        scrollPane.preferredSize = Dimension(prefW, prefH)
        scrollPane.minimumSize = Dimension(0, minH)
        toggleButton.text = if (value) "Hide log" else "Show log"
        toggleButton.icon = if (value) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight
        revalidate()
        repaint()
    }

    /**
     * Collapsed: keep preferred height header-cheap even if a child briefly reports scroll tax.
     * Expanded: default BorderLayout preferred.
     */
    override fun getPreferredSize(): Dimension {
        val base = super.getPreferredSize()
        if (expanded) return base
        val cap = ChromeLayoutDefaults.ACTIVITY_LOG_COLLAPSED_HEADER_TAX_PX + 12
        return if (base.height > cap) Dimension(base.width, cap) else base
    }

    private fun scrollToBottom() {
        logArea.caretPosition = logArea.document.length
        val bar = scrollPane.verticalScrollBar
        bar.value = bar.maximum
    }
}
