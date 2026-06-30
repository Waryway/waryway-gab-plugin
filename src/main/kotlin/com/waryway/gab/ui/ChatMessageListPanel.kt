package com.waryway.gab.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.datatransfer.StringSelection
import javax.swing.BorderFactory
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities

/**
 * Scrollable message list with role icons — icons are rendered by the plugin, not expected from AI output.
 */
class ChatMessageListPanel : JBPanel<ChatMessageListPanel>() {

    private var activeTurnArea: AutoSizeMessageArea? = null
    private val statusLog = StringBuilder()
    private val streamingBody = StringBuilder()
    private var streamingActive = false

    private val messagesPanel = JPanel().apply {
        layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
        isOpaque = false
    }
    private val scrollPane = JBScrollPane(messagesPanel).apply {
        border = null
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
    }

    init {
        layout = BorderLayout()
        isOpaque = false
        add(scrollPane, BorderLayout.CENTER)
        addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent?) {
                messagesPanel.revalidate()
            }
        })
    }

    fun clear() {
        activeTurnArea = null
        statusLog.clear()
        streamingBody.clear()
        streamingActive = false
        messagesPanel.removeAll()
        messagesPanel.revalidate()
        messagesPanel.repaint()
    }

    /** Start one consolidated bubble for an agent turn (tool activity + final reply). */
    fun beginAgentTurn() {
        activeTurnArea = null
        statusLog.clear()
        streamingBody.clear()
        streamingActive = false
        addMessage(MessageRole.ASSISTANT, "")
        activeTurnArea = lastMessageArea()
    }

    /** Start live token streaming below any status lines. */
    fun beginStreamingBody() {
        streamingActive = true
        streamingBody.clear()
        refreshActiveTurnText()
    }

    /** Append a status line to the active agent turn without creating a new bubble. */
    fun appendToAgentTurn(line: String) {
        if (statusLog.isNotEmpty()) statusLog.append('\n')
        statusLog.append(line)
        refreshActiveTurnText()
    }

    /** Finish the turn with the model's final reply in the same copyable bubble. */
    fun completeAgentTurn(response: String, toolCallCount: Int = 0) {
        val area = activeTurnArea ?: run {
            addMessage(MessageRole.ASSISTANT, response)
            return
        }

        val body = when {
            streamingBody.isNotEmpty() -> streamingBody.toString().trim()
            response.isNotBlank() -> response.trim()
            else -> "(no response)"
        }
        area.text = buildString {
            if (statusLog.isNotEmpty()) {
                append(statusLog.trimEnd())
                append("\n\n")
            }
            append(body)
            if (toolCallCount > 0) {
                append("\n\n— Agent used $toolCallCount tool call(s)")
            }
        }
        activeTurnArea = null
        statusLog.clear()
        streamingBody.clear()
        streamingActive = false
        refreshAfterTextChange(area)
    }

    fun addMessage(role: MessageRole, text: String) {
        messagesPanel.add(buildMessageRow(role, text))
        messagesPanel.revalidate()
        messagesPanel.repaint()
        SwingUtilities.invokeLater { scrollToBottom() }
    }

    /** Append streaming text to the last assistant message instead of fragmenting into many bubbles. */
    fun appendStreamingDelta(delta: String) {
        if (activeTurnArea == null) beginAgentTurn()
        if (!streamingActive) beginStreamingBody()
        streamingBody.append(delta)
        refreshActiveTurnText()
    }

    private fun refreshActiveTurnText() {
        val area = activeTurnArea ?: return
        area.text = buildString {
            if (statusLog.isNotEmpty()) {
                append(statusLog.trimEnd())
                if (streamingBody.isNotEmpty()) append("\n\n")
            }
            append(streamingBody)
        }
        refreshAfterTextChange(area)
    }

    fun loadMessages(entries: List<Pair<MessageRole, String>>) {
        clear()
        entries.forEach { (role, text) -> messagesPanel.add(buildMessageRow(role, text)) }
        messagesPanel.revalidate()
        messagesPanel.repaint()
        SwingUtilities.invokeLater { scrollToBottom() }
    }

    private fun scrollToBottom() {
        val bar = scrollPane.verticalScrollBar
        bar.value = bar.maximum
    }

    private fun lastMessageArea(): AutoSizeMessageArea? {
        for (i in messagesPanel.componentCount - 1 downTo 0) {
            findMessageArea(messagesPanel.getComponent(i))?.let { return it }
        }
        return null
    }

    private fun findMessageArea(component: java.awt.Component): AutoSizeMessageArea? {
        if (component is AutoSizeMessageArea) return component
        if (component is JComponent) {
            for (child in component.components) {
                findMessageArea(child)?.let { return it }
            }
        }
        return null
    }

    private fun refreshAfterTextChange(area: AutoSizeMessageArea) {
        area.revalidate()
        area.repaint()
        messagesPanel.revalidate()
        messagesPanel.repaint()
        SwingUtilities.invokeLater { scrollToBottom() }
    }

    private fun buildMessageRow(role: MessageRole, text: String): JComponent {
        val row = JPanel(BorderLayout(6, 2)).apply {
            border = JBUI.Borders.empty(4, 0)
            isOpaque = false
            alignmentX = java.awt.Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        }

        val iconLabel = JBLabel(role.icon).apply {
            verticalAlignment = javax.swing.SwingConstants.TOP
        }

        val body = AutoSizeMessageArea(
            text,
            role.background,
            role.foreground,
            role.borderColor,
            scrollPane.viewport?.width?.takeIf { it > 0 } ?: 280
        )

        val headerRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JBLabel(role.label).apply {
                font = font.deriveFont(Font.BOLD, 11f)
                foreground = role.foreground
            }, BorderLayout.WEST)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                isOpaque = false
                add(JButton(AllIcons.Actions.Copy).apply {
                    toolTipText = "Copy message"
                    isBorderPainted = false
                    isContentAreaFilled = false
                    preferredSize = Dimension(22, 22)
                    addActionListener {
                        CopyPasteManager.getInstance().setContents(StringSelection(body.text))
                    }
                })
            }, BorderLayout.EAST)
        }

        val textColumn = JPanel(BorderLayout(0, 2)).apply {
            isOpaque = false
            add(headerRow, BorderLayout.NORTH)
            add(body, BorderLayout.CENTER)
        }

        row.add(iconLabel, BorderLayout.WEST)
        row.add(textColumn, BorderLayout.CENTER)
        return row
    }

    /** Non-editable text area that wraps and grows vertically to show all lines. */
    private class AutoSizeMessageArea(
        text: String,
        bg: Color,
        fg: Color,
        borderColor: Color,
        private val fallbackWidth: Int
    ) : JTextArea(text) {
        init {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = Font(Font.SANS_SERIF, Font.PLAIN, 13)
            isOpaque = true
            background = bg
            foreground = fg
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(borderColor, 1, true),
                JBUI.Borders.empty(6, 8)
            )
        }

        override fun getPreferredSize(): Dimension {
            val parentWidth = (parent as? JComponent)?.width?.takeIf { it > 0 } ?: fallbackWidth
            val insets = border?.getBorderInsets(this) ?: java.awt.Insets(0, 0, 0, 0)
            val width = (parentWidth - insets.left - insets.right).coerceAtLeast(80)
            setSize(width, Int.MAX_VALUE)
            return Dimension(width, super.getPreferredSize().height)
        }
    }

    enum class MessageRole(
        val label: String,
        val icon: Icon,
        val background: Color,
        val foreground: Color,
        val borderColor: Color
    ) {
        USER("You", AllIcons.General.User, GabTheme.userMessageBg, GabTheme.userMessageFg, GabTheme.userMessageBorder),
        ASSISTANT("Agent", AllIcons.Toolwindows.ToolWindowMessages, GabTheme.assistantMessageBg, GabTheme.assistantMessageFg, GabTheme.assistantMessageBorder),
        TOOL("Agent", AllIcons.Actions.Execute, GabTheme.systemMessageBg, GabTheme.systemMessageFg, GabTheme.systemMessageBorder),
        SYSTEM("System", AllIcons.General.Information, GabTheme.systemMessageBg, GabTheme.systemMessageFg, GabTheme.systemMessageBorder)
    }
}