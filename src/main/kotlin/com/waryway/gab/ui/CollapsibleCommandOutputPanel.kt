package com.waryway.gab.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.datatransfer.StringSelection
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.ScrollPaneConstants

/**
 * Compact shell/tool output block for the agent chat timeline.
 * Collapsed by default; expand to read full (soft-capped) stdout/stderr.
 */
class CollapsibleCommandOutputPanel(
    command: String,
    output: String,
    initiallyExpanded: Boolean = false,
) : JPanel(BorderLayout(0, 2)) {

    private val fullBody = CommandOutputUi.bodyForUi(output)
    private val headerLabelText = CommandOutputUi.headerSummary(command, output)

    private var expanded = initiallyExpanded

    private val outputArea = JTextArea(fullBody).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 11)
        background = JBColor(Color(0xF4F6F8), Color(0x1A1D24))
        foreground = JBColor(Color(0x2D3748), Color(0xC8D0E0))
        border = JBUI.Borders.empty(4, 6)
        caretPosition = 0
    }

    private val scrollPane = JBScrollPane(outputArea).apply {
        border = BorderFactory.createLineBorder(JBColor.border())
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        // Cap height so long builds don't dominate the chat.
        maximumSize = Dimension(Int.MAX_VALUE, 220)
        preferredSize = Dimension(200, 140)
    }

    private val toggleButton = JButton().apply {
        isBorderPainted = false
        isContentAreaFilled = false
        isFocusPainted = false
        font = font.deriveFont(Font.PLAIN, 11f)
        toolTipText = "Show or hide command output"
        addActionListener { setExpanded(!expanded) }
    }

    init {
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(JBColor(Color(0xD0D7DE), Color(0x3A4250)), 1, true),
            JBUI.Borders.empty(2, 4, 2, 4)
        )
        maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)

        val header = JPanel(BorderLayout(4, 0)).apply {
            isOpaque = false
            add(toggleButton, BorderLayout.WEST)
            add(
                javax.swing.JLabel(headerLabelText).apply {
                    font = Font(Font.MONOSPACED, Font.PLAIN, 11)
                    foreground = GabTheme.inactiveText
                    toolTipText = command.trim().ifBlank { headerLabelText }
                },
                BorderLayout.CENTER
            )
            add(
                JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                    isOpaque = false
                    add(JButton(AllIcons.Actions.Copy).apply {
                        toolTipText = "Copy command output"
                        isBorderPainted = false
                        isContentAreaFilled = false
                        preferredSize = Dimension(22, 20)
                        addActionListener {
                            CopyPasteManager.getInstance().setContents(StringSelection(fullBody))
                        }
                    })
                },
                BorderLayout.EAST
            )
        }

        add(header, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
        // Spacer keeps BoxLayout children from stretching oddly when collapsed.
        add(Box.createVerticalStrut(0), BorderLayout.SOUTH)
        setExpanded(initiallyExpanded)
    }

    fun setExpanded(value: Boolean) {
        expanded = value
        scrollPane.isVisible = value
        toggleButton.icon = if (value) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight
        toggleButton.text = if (value) "Hide" else "Show"
        revalidate()
        repaint()
    }

    fun isExpanded(): Boolean = expanded

    /** Plain text included when copying the whole agent turn. */
    fun plainTextForCopy(): String = buildString {
        append(headerLabelText)
        if (expanded) {
            append('\n')
            append(fullBody)
        } else {
            append("  [output collapsed — expand to view]")
        }
    }
}
