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
 *
 * Streaming path is EDT-throttled: [appendStreamingDelta] updates text often, but full
 * revalidate/scroll runs at most every [SCROLL_THROTTLE_MS] during an active stream.
 * Live body is soft-capped at [STREAMING_BODY_SOFT_CAP] with a visible truncation note.
 */
class ChatMessageListPanel : JBPanel<ChatMessageListPanel>() {

    private var activeTurnArea: AutoSizeMessageArea? = null
    private val statusLog = StringBuilder()
    private var statusLineCount = 0
    private var statusSoftCapped = false
    private val streamingBody = StringBuilder()
    private var streamingActive = false
    private var streamingTruncated = false
    private var lastLayoutScrollMs = 0L

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
        statusLineCount = 0
        statusSoftCapped = false
        streamingBody.clear()
        streamingActive = false
        streamingTruncated = false
        lastLayoutScrollMs = 0L
        messagesPanel.removeAll()
        messagesPanel.revalidate()
        messagesPanel.repaint()
    }

    /** Start one consolidated bubble for an agent turn (tool activity + final reply). */
    fun beginAgentTurn() {
        activeTurnArea = null
        statusLog.clear()
        statusLineCount = 0
        statusSoftCapped = false
        streamingBody.clear()
        streamingActive = false
        streamingTruncated = false
        lastLayoutScrollMs = 0L
        addMessage(MessageRole.ASSISTANT, "")
        activeTurnArea = lastMessageArea()
    }

    /** Start (or reset) live token streaming below any status lines. */
    fun beginStreamingBody() {
        streamingActive = true
        streamingBody.clear()
        streamingTruncated = false
        lastLayoutScrollMs = 0L
        refreshActiveTurnText(forceLayout = true)
    }

    /**
     * Append a status line to the active agent turn without creating a new bubble.
     * Soft-caps line count so a runaway tool/event loop cannot dump the chat forever.
     * Full detail stays in the Activity log / fail package.
     */
    fun appendToAgentTurn(line: String) {
        if (statusSoftCapped) return
        if (statusLineCount >= STATUS_LINE_SOFT_CAP) {
            statusSoftCapped = true
            if (statusLog.isNotEmpty()) statusLog.append('\n')
            statusLog.append(STATUS_CAP_NOTE)
            refreshActiveTurnText(forceLayout = true)
            return
        }
        statusLineCount++
        if (statusLog.isNotEmpty()) statusLog.append('\n')
        statusLog.append(line)
        refreshActiveTurnText(forceLayout = true)
    }

    /** Finish the turn with the model's final reply in the same copyable bubble. */
    fun completeAgentTurn(response: String, toolCallCount: Int = 0) {
        val area = activeTurnArea ?: run {
            addMessage(MessageRole.ASSISTANT, response)
            return
        }

        // Prefer the model’s final content (may be longer than UI soft-capped stream preview).
        val body = when {
            response.isNotBlank() -> response.trim()
            streamingBody.isNotEmpty() -> streamingBody.toString().trim()
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
        statusLineCount = 0
        statusSoftCapped = false
        streamingBody.clear()
        streamingActive = false
        streamingTruncated = false
        refreshAfterTextChange(area, forceLayout = true)
    }

    fun addMessage(role: MessageRole, text: String) {
        messagesPanel.add(buildMessageRow(role, text))
        messagesPanel.revalidate()
        messagesPanel.repaint()
        SwingUtilities.invokeLater { scrollToBottom() }
    }

    /** Append streaming text to the last assistant message instead of fragmenting into many bubbles. */
    fun appendStreamingDelta(delta: String) {
        if (delta.isEmpty()) return
        if (activeTurnArea == null) beginAgentTurn()
        if (!streamingActive) beginStreamingBody()
        appendToStreamingBody(delta)
        refreshActiveTurnText(forceLayout = false)
    }

    private fun appendToStreamingBody(delta: String) {
        if (streamingTruncated) return
        val room = STREAMING_BODY_SOFT_CAP - streamingBody.length
        if (room <= 0) {
            streamingTruncated = true
            streamingBody.append(TRUNCATION_NOTE)
            return
        }
        if (delta.length <= room) {
            streamingBody.append(delta)
            return
        }
        streamingBody.append(delta, 0, room)
        streamingTruncated = true
        streamingBody.append(TRUNCATION_NOTE)
    }

    private fun refreshActiveTurnText(forceLayout: Boolean) {
        val area = activeTurnArea ?: return
        area.text = buildString {
            if (statusLog.isNotEmpty()) {
                append(statusLog.trimEnd())
                if (streamingBody.isNotEmpty()) append("\n\n")
            }
            append(streamingBody)
        }
        refreshAfterTextChange(area, forceLayout = forceLayout)
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

    /**
     * @param forceLayout always revalidate + scroll (status lines, complete, stream start).
     *        When false during streaming, layout/scroll is throttled to [SCROLL_THROTTLE_MS].
     */
    private fun refreshAfterTextChange(area: AutoSizeMessageArea, forceLayout: Boolean) {
        // Cheap paint of the text area itself so tokens still appear.
        area.repaint()
        if (!forceLayout && streamingActive) {
            val now = System.currentTimeMillis()
            if (now - lastLayoutScrollMs < SCROLL_THROTTLE_MS) {
                return
            }
            lastLayoutScrollMs = now
        } else {
            lastLayoutScrollMs = System.currentTimeMillis()
        }
        area.revalidate()
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

    companion object {
        /** Soft cap on live streaming body to bound RAM / layout cost. */
        const val STREAMING_BODY_SOFT_CAP: Int = 200_000
        /** Soft cap on status / tool lines in the agent bubble (full log is elsewhere). */
        const val STATUS_LINE_SOFT_CAP: Int = 80
        /** Min interval between full revalidate + scroll during streaming. */
        const val SCROLL_THROTTLE_MS: Long = 80L
        const val TRUNCATION_NOTE: String = "\n\n… (stream truncated for UI)"
        const val STATUS_CAP_NOTE: String =
            "… (status lines capped for UI — see Activity log or Export fail package)"
    }
}
