package com.waryway.gab.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.datatransfer.StringSelection
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.event.HyperlinkEvent

/**
 * Scrollable message list with role icons — icons are rendered by the plugin, not expected from AI output.
 *
 * Streaming path is EDT-throttled: [appendStreamingDelta] updates text often, but full
 * revalidate/scroll runs at most every [SCROLL_THROTTLE_MS] during an active stream.
 * Live body is soft-capped at [STREAMING_BODY_SOFT_CAP] with a visible truncation note.
 *
 * Agent turns use a **timeline** (status lines + collapsible command output) above the
 * streaming/final reply body so bash/tool stdout is visible in the plugin view without
 * dumping full logs into the transcript by default.
 */
class ChatMessageListPanel : JBPanel<ChatMessageListPanel>() {

    /** Per-turn UI state (status + command panels stay with the bubble after complete). */
    private class ActiveTurn(
        val body: AutoSizeMessageArea,
        val timeline: JPanel,
        val statusLog: StringBuilder = StringBuilder(),
        val commandOutputs: MutableList<CollapsibleCommandOutputPanel> = mutableListOf(),
        var statusLineCount: Int = 0,
        var statusSoftCapped: Boolean = false,
        val streamingBody: StringBuilder = StringBuilder(),
        var streamingActive: Boolean = false,
        var streamingTruncated: Boolean = false,
        /** When set by [completeAgentTurn], clipboard uses this instead of reassembling panels. */
        var overrideCopyText: String? = null,
    )

    private var activeTurn: ActiveTurn? = null
    private var lastLayoutScrollMs = 0L

    private val messagesPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
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
        activeTurn = null
        lastLayoutScrollMs = 0L
        messagesPanel.removeAll()
        messagesPanel.revalidate()
        messagesPanel.repaint()
    }

    /** Start one consolidated bubble for an agent turn (tool activity + final reply). */
    fun beginAgentTurn() {
        activeTurn = null
        lastLayoutScrollMs = 0L
        val built = buildAgentTurnRow()
        messagesPanel.add(built.row)
        activeTurn = built.turn
        messagesPanel.revalidate()
        messagesPanel.repaint()
        SwingUtilities.invokeLater { scrollToBottom() }
    }

    /** Start (or reset) live token streaming below any status lines. */
    fun beginStreamingBody() {
        val turn = activeTurn ?: return
        turn.streamingActive = true
        turn.streamingBody.clear()
        turn.streamingTruncated = false
        lastLayoutScrollMs = 0L
        refreshActiveTurnText(forceLayout = true)
    }

    /**
     * Append a status line to the active agent turn without creating a new bubble.
     * Soft-caps line count so a runaway tool/event loop cannot dump the chat forever.
     * Full detail stays in the Activity log / fail package.
     */
    fun appendToAgentTurn(line: String) {
        val turn = activeTurn ?: return
        if (turn.statusSoftCapped) return
        if (turn.statusLineCount >= STATUS_LINE_SOFT_CAP) {
            turn.statusSoftCapped = true
            if (turn.statusLog.isNotEmpty()) turn.statusLog.append('\n')
            turn.statusLog.append(STATUS_CAP_NOTE)
            appendTimelineStatus(turn, STATUS_CAP_NOTE)
            refreshActiveTurnText(forceLayout = true)
            return
        }
        turn.statusLineCount++
        if (turn.statusLog.isNotEmpty()) turn.statusLog.append('\n')
        turn.statusLog.append(line)
        appendTimelineStatus(turn, line)
        refreshActiveTurnText(forceLayout = true)
    }

    /**
     * Full shell/tool output for the chat timeline: collapsed by default, expandable.
     * Call after the `▸ cmd:` status line (or instead of a truncated `→` summary).
     */
    fun appendCommandOutput(command: String, output: String) {
        if (activeTurn == null) {
            beginAgentTurn()
        }
        val turn = activeTurn ?: return
        val panel = CollapsibleCommandOutputPanel(
            command = command,
            output = output,
            initiallyExpanded = false,
        )
        turn.commandOutputs.add(panel)
        turn.timeline.add(panel)
        turn.timeline.revalidate()
        messagesPanel.revalidate()
        messagesPanel.repaint()
        SwingUtilities.invokeLater { scrollToBottom() }
    }

    /**
     * Finish the turn with the model's final reply in the same copyable bubble.
     *
     * @param response Main answer body (or full combined text when [thinking] is null).
     * @param toolCallCount Optional footer count.
     * @param thinking Optional goal/plan/thinking text — shown as a collapsible panel
     *   **above** the answer so Answer stays clean and scannable.
     * @param fullCopyText Optional full plain text for clipboard (answer + thinking).
     *   Defaults to thinking + response when thinking is present.
     */
    fun completeAgentTurn(
        response: String,
        toolCallCount: Int = 0,
        thinking: String? = null,
        fullCopyText: String? = null,
    ) {
        val turn = activeTurn
        val area = turn?.body ?: run {
            // No active turn: fold thinking into a single message for non-agent paths.
            val combined = if (!thinking.isNullOrBlank()) {
                buildString {
                    append(response.trim())
                    append("\n\n### Thinking\n\n")
                    append(thinking.trim())
                    if (toolCallCount > 0) {
                        append("\n\n— Agent used $toolCallCount tool call(s)")
                    }
                }
            } else {
                buildString {
                    append(response.trim().ifBlank { "(no response)" })
                    if (toolCallCount > 0) {
                        append("\n\n— Agent used $toolCallCount tool call(s)")
                    }
                }
            }
            addMessage(MessageRole.ASSISTANT, combined)
            return
        }

        // Thinking / plan: collapsible above the answer (collapsed by default).
        val thinkingTrim = thinking?.trim().orEmpty()
        if (thinkingTrim.isNotEmpty()) {
            val panel = CollapsibleCommandOutputPanel(
                command = "Thinking / plan",
                output = thinkingTrim,
                initiallyExpanded = false,
            )
            turn.commandOutputs.add(panel)
            // Insert after status timeline entries but before answer body: timeline is
            // already above body in the layout; append is fine (answer body is separate).
            turn.timeline.add(panel)
            turn.timeline.revalidate()
        }

        // Prefer the model’s final content (may be longer than UI soft-capped stream preview).
        val body = when {
            response.isNotBlank() -> response.trim()
            turn.streamingBody.isNotEmpty() -> turn.streamingBody.toString().trim()
            else -> "(no response)"
        }
        area.text = buildString {
            append(body)
            if (toolCallCount > 0) {
                append("\n\n— Agent used $toolCallCount tool call(s)")
            }
        }

        // Prefer structured full text for copy when provided.
        if (fullCopyText != null) {
            turn.overrideCopyText = fullCopyText.trim().ifBlank { null }
        }

        // Timeline + collapsible panels remain on the bubble; drop active pointer only.
        activeTurn = null
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
        if (activeTurn == null) beginAgentTurn()
        val turn = activeTurn ?: return
        if (!turn.streamingActive) beginStreamingBody()
        appendToStreamingBody(turn, delta)
        refreshActiveTurnText(forceLayout = false)
    }

    private fun appendToStreamingBody(turn: ActiveTurn, delta: String) {
        if (turn.streamingTruncated) return
        val room = STREAMING_BODY_SOFT_CAP - turn.streamingBody.length
        if (room <= 0) {
            turn.streamingTruncated = true
            turn.streamingBody.append(TRUNCATION_NOTE)
            return
        }
        if (delta.length <= room) {
            turn.streamingBody.append(delta)
            return
        }
        turn.streamingBody.append(delta, 0, room)
        turn.streamingTruncated = true
        turn.streamingBody.append(TRUNCATION_NOTE)
    }

    private fun refreshActiveTurnText(forceLayout: Boolean) {
        val turn = activeTurn ?: return
        // Status lives in the timeline; body is stream/final only.
        turn.body.text = turn.streamingBody.toString()
        refreshAfterTextChange(turn.body, forceLayout = forceLayout)
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

    private fun appendTimelineStatus(turn: ActiveTurn, line: String) {
        val label = JBLabel(line).apply {
            font = Font(Font.MONOSPACED, Font.PLAIN, 11)
            foreground = GabTheme.inactiveText
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(1, 2)
        }
        // Wrap so BoxLayout respects max width.
        val wrap = JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 28)
            add(label, BorderLayout.WEST)
        }
        turn.timeline.add(wrap)
        turn.timeline.revalidate()
    }

    private data class AgentTurnBuilt(
        val row: JComponent,
        val turn: ActiveTurn,
    )

    private fun buildAgentTurnRow(): AgentTurnBuilt {
        val role = MessageRole.ASSISTANT
        val row = JPanel(BorderLayout(6, 2)).apply {
            border = JBUI.Borders.empty(4, 0)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        }

        val iconLabel = JBLabel(role.icon).apply {
            verticalAlignment = javax.swing.SwingConstants.TOP
        }

        val timeline = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }

        val body = AutoSizeMessageArea(
            "",
            role.background,
            role.foreground,
            role.borderColor,
            scrollPane.viewport?.width?.takeIf { it > 0 } ?: 280,
            embedded = true,
        )

        val turn = ActiveTurn(body = body, timeline = timeline)

        val copyText: () -> String = {
            val override = turn.overrideCopyText?.takeIf { it.isNotBlank() }
            if (override != null) {
                override
            } else {
                buildString {
                    if (turn.statusLog.isNotEmpty()) {
                        append(turn.statusLog.trimEnd())
                        append('\n')
                    }
                    turn.commandOutputs.forEach { panel ->
                        append(panel.plainTextForCopy())
                        append('\n')
                    }
                    val bodyText = body.text
                    if (bodyText.isNotBlank()) {
                        if (isNotEmpty()) append('\n')
                        append(bodyText)
                    }
                }.trim()
            }
        }

        val headerRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, 28)
            alignmentX = Component.LEFT_ALIGNMENT
            add(JBLabel(role.label).apply {
                font = font.deriveFont(Font.BOLD, 11f)
                foreground = role.foreground
            }, BorderLayout.WEST)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                isOpaque = false
                add(JButton(AllIcons.Actions.Copy).apply {
                    toolTipText = "Copy message (status + command output + reply)"
                    isBorderPainted = false
                    isContentAreaFilled = false
                    preferredSize = Dimension(22, 22)
                    addActionListener {
                        CopyPasteManager.getInstance().setContents(StringSelection(copyText()))
                    }
                })
            }, BorderLayout.EAST)
        }

        val contentColumn = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(headerRow)
            add(timeline)
            add(body)
        }

        // Outer bubble border around the whole agent turn.
        val bubble = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = role.background
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(role.borderColor, 1, true),
                JBUI.Borders.empty(4, 6)
            )
            add(contentColumn, BorderLayout.CENTER)
        }

        row.add(iconLabel, BorderLayout.WEST)
        row.add(bubble, BorderLayout.CENTER)
        return AgentTurnBuilt(row, turn)
    }

    private fun buildMessageRow(role: MessageRole, text: String): JComponent {
        val row = JPanel(BorderLayout(6, 2)).apply {
            border = JBUI.Borders.empty(4, 0)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
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

    /**
     * @param forceLayout always revalidate + scroll (status lines, complete, stream start).
     *        When false during streaming, layout/scroll is throttled to [SCROLL_THROTTLE_MS].
     */
    private fun refreshAfterTextChange(area: AutoSizeMessageArea, forceLayout: Boolean) {
        // Cheap paint of the text area itself so tokens still appear.
        area.repaint()
        val streaming = activeTurn?.streamingActive == true
        if (!forceLayout && streaming) {
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

    /**
     * Non-editable message body that wraps, grows vertically, and opens http(s) links
     * in the system browser (so login / docs URLs are not dead text).
     */
    private class AutoSizeMessageArea(
        text: String,
        bg: Color,
        fg: Color,
        borderColor: Color,
        private val fallbackWidth: Int,
        embedded: Boolean = false,
    ) : JEditorPane() {
        init {
            contentType = "text/html"
            isEditable = false
            isOpaque = !embedded
            background = bg
            foreground = fg
            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
            font = Font(Font.SANS_SERIF, Font.PLAIN, 13)
            border = if (embedded) {
                JBUI.Borders.empty(4, 0, 2, 0)
            } else {
                BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(borderColor, 1, true),
                    JBUI.Borders.empty(6, 8)
                )
            }
            alignmentX = Component.LEFT_ALIGNMENT
            // Use plain-text path so links are linkified (see setText).
            this.text = text
            addHyperlinkListener { e ->
                if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                    val url = e.url?.toString() ?: e.description
                    if (!url.isNullOrBlank()) {
                        try {
                            BrowserUtil.browse(url)
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        }

        /** Plain text for clipboard / tests; HTML is only for display. */
        private var plainText: String = text

        override fun getText(): String = plainText

        override fun setText(t: String?) {
            if (t == null) {
                plainText = ""
                super.setText("")
                return
            }
            // Callers assign plain text via `area.text = ...`; convert to HTML with linkified URLs.
            if (!t.trimStart().startsWith("<html", ignoreCase = true)) {
                plainText = t
                super.setText(toHtml(t, foreground))
            } else {
                super.setText(t)
            }
        }

        override fun getPreferredSize(): Dimension {
            val parentWidth = (parent as? JComponent)?.width?.takeIf { it > 0 } ?: fallbackWidth
            val insets = border?.getBorderInsets(this) ?: java.awt.Insets(0, 0, 0, 0)
            val width = (parentWidth - insets.left - insets.right).coerceAtLeast(80)
            setSize(width, Int.MAX_VALUE)
            return Dimension(width, super.getPreferredSize().height)
        }

        companion object {
            private val URL_RE = Regex("""(https?://[^\s<>"')\]]+)""")
            private val BOLD_RE = Regex("""\*\*([^*]+)\*\*""")
            private val HEADING_RE = Regex("""^(#{1,3})\s+(.+)$""")

            fun toHtml(plain: String, fg: Color): String {
                val hex = String.format("#%06X", fg.rgb and 0xFFFFFF)
                val muted = mutedHex(fg)
                val lines = plain.split('\n')
                val htmlLines = lines.map { line ->
                    val heading = HEADING_RE.matchEntire(line.trimEnd())
                    if (heading != null) {
                        val level = heading.groupValues[1].length
                        val title = escapeHtml(heading.groupValues[2])
                        val withBold = applyBold(title)
                        val size = when (level) {
                            1 -> "15pt"
                            2 -> "14pt"
                            else -> "13pt"
                        }
                        // Thinking/meta headings slightly muted; Answer stays strong.
                        val color = if (title.startsWith("Thinking", ignoreCase = true) ||
                            title.startsWith("Agent result", ignoreCase = true) ||
                            title.startsWith("Details", ignoreCase = true)
                        ) muted else hex
                        """<div style="font-weight:bold;font-size:$size;color:$color;margin:8px 0 4px 0;">$withBold</div>"""
                    } else {
                        val escaped = escapeHtml(line)
                        val withBold = applyBold(escaped)
                        val withLinks = URL_RE.replace(withBold) { m ->
                            val url = m.groupValues[1]
                            """<a href="$url">$url</a>"""
                        }
                        if (withLinks.isEmpty()) "<br/>" else "$withLinks<br/>"
                    }
                }
                return """<html><body style="font-family:sans-serif;font-size:13pt;color:$hex;margin:0;">${htmlLines.joinToString("")}</body></html>"""
            }

            private fun escapeHtml(s: String): String =
                s.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")

            private fun applyBold(escaped: String): String =
                BOLD_RE.replace(escaped) { m -> "<b>${m.groupValues[1]}</b>" }

            private fun mutedHex(fg: Color): String {
                // Blend toward gray so secondary headers (Thinking) read quieter.
                val r = (fg.red * 0.65 + 0x88 * 0.35).toInt().coerceIn(0, 255)
                val g = (fg.green * 0.65 + 0x88 * 0.35).toInt().coerceIn(0, 255)
                val b = (fg.blue * 0.65 + 0x88 * 0.35).toInt().coerceIn(0, 255)
                return String.format("#%02X%02X%02X", r, g, b)
            }
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
