package com.waryway.gab.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.waryway.gab.model.Conversation
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.JToggleButton

/**
 * Browser-style tab bar: two most recent conversations as tabs + history dropdown + new chat button.
 */
class ConversationTabBar(
    private val onNewConversation: () -> Unit,
    private val onSwitchConversation: (String) -> Unit
) : JPanel(BorderLayout(4, 0)) {

    private val tabPanel = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0))
    private val historyCombo = JComboBox<String>()
    private val newButton = JButton(AllIcons.General.Add).apply {
        toolTipText = "New conversation"
        isBorderPainted = false
        preferredSize = java.awt.Dimension(28, 24)
        addActionListener { onNewConversation() }
    }

    private var conversations: List<Conversation> = emptyList()
    private var activeId: String? = null
    private val tabButtons = mutableListOf<JToggleButton>()
    /**
     * While true, history combo selection changes are programmatic (title refresh after send)
     * and must not call [onSwitchConversation] — that reloads the message list and can
     * duplicate user bubbles / wipe the live agent turn.
     */
    private var suppressHistorySwitch = false

    init {
        border = JBUI.Borders.emptyBottom(4)
        tabPanel.isOpaque = false
        add(tabPanel, BorderLayout.CENTER)

        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            isOpaque = false
            add(JBLabel(AllIcons.Actions.ShowAsTree).apply { toolTipText = "Conversation history" })
            historyCombo.preferredSize = java.awt.Dimension(140, 24)
            historyCombo.addActionListener {
                if (suppressHistorySwitch) return@addActionListener
                val idx = historyCombo.selectedIndex
                if (idx >= 0 && idx < conversations.size) {
                    val id = conversations[idx].id
                    // Ignore no-op reselection of the already-active chat.
                    if (id != activeId) {
                        onSwitchConversation(id)
                    }
                }
            }
            add(historyCombo)
            add(newButton)
        }
        add(right, BorderLayout.EAST)
    }

    fun update(conversations: List<Conversation>, activeId: String) {
        this.conversations = conversations
        this.activeId = activeId

        tabPanel.removeAll()
        tabButtons.clear()

        val tabs = conversations.take(2)
        tabs.forEach { conv ->
            val isActive = conv.id == activeId
            val btn = JToggleButton(truncate(conv.title, 22)).apply {
                icon = AllIcons.Toolwindows.ToolWindowMessages
                isSelected = isActive
                font = font.deriveFont(if (isActive) Font.BOLD else Font.PLAIN, 11f)
                foreground = UIUtil.getLabelForeground()
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, if (isActive) 2 else 0, 0, GabTheme.accent),
                    JBUI.Borders.empty(4, 8)
                )
                isContentAreaFilled = isActive
                isOpaque = isActive
                background = if (isActive) GabTheme.activeTabBackground else GabTheme.panelBackground
                toolTipText = conv.title
                addActionListener {
                    if (isSelected && conv.id != activeId) {
                        onSwitchConversation(conv.id)
                    }
                }
            }
            tabButtons.add(btn)
            tabPanel.add(btn)
        }

        suppressHistorySwitch = true
        try {
            historyCombo.removeAllItems()
            conversations.forEach { historyCombo.addItem(truncate(it.title, 28)) }
            val activeIndex = conversations.indexOfFirst { it.id == activeId }
            if (activeIndex >= 0) {
                historyCombo.selectedIndex = activeIndex
            }
        } finally {
            suppressHistorySwitch = false
        }

        revalidate()
        repaint()
    }

    private fun truncate(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max - 1) + "…"
}