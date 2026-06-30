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
 */
class ActivityLogPanel : JPanel(BorderLayout()) {

    var onClearRequested: (() -> Unit)? = null

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
        preferredSize = Dimension(200, 130)
    }

    private var expanded = true
    private val toggleButton = JButton("Hide log", AllIcons.General.ArrowDown).apply {
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
                add(JButton(AllIcons.Actions.Copy).apply {
                    toolTipText = "Copy log"
                    isBorderPainted = false
                    preferredSize = Dimension(24, 22)
                    addActionListener {
                        CopyPasteManager.getInstance().setContents(StringSelection(logArea.text))
                    }
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

        add(header, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
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
        toggleButton.text = if (value) "Hide log" else "Show log"
        toggleButton.icon = if (value) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight
        revalidate()
        repaint()
    }

    private fun scrollToBottom() {
        logArea.caretPosition = logArea.document.length
        val bar = scrollPane.verticalScrollBar
        bar.value = bar.maximum
    }
}