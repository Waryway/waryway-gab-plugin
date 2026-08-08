package com.waryway.gab.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.waryway.gab.skills.SkillRef
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.ListSelectionModel
import javax.swing.Popup
import javax.swing.PopupFactory
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Composer-side skill UX: slash `/` autocomplete popup + optional picker button.
 *
 * Selection updates skill id via [onSkillSelected] and removes the `/query` token from
 * the composer — **never** sends the message.
 */
class ComposerSkillPicker(
    private val textArea: JTextArea,
    private val getCatalog: () -> List<SkillRef>,
    private val onSkillSelected: (SkillRef) -> Unit
) {
    private val listModel = DefaultListModel<SkillRef>()
    private val list = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        visibleRowCount = 8
        cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean
            ): java.awt.Component {
                val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                if (value is SkillRef) {
                    text = SkillSlashQuery.listLabel(value, includeSource = true)
                    toolTipText = value.hint.ifBlank { value.description }.ifBlank { value.id }
                }
                return c
            }
        }
        border = JBUI.Borders.empty(2)
    }

    private val popupPanel = JPanel(BorderLayout()).apply {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(JBColor.border()),
            JBUI.Borders.empty(2)
        )
        background = JBColor.background()
        val header = JBLabel("Skills  ·  Enter/Tab select · Esc close").apply {
            font = font.deriveFont(11f)
            foreground = JBColor.GRAY
            border = JBUI.Borders.empty(2, 6, 4, 6)
        }
        add(header, BorderLayout.NORTH)
        add(JBScrollPane(list).apply {
            border = BorderFactory.createEmptyBorder()
            preferredSize = Dimension(320, 180)
        }, BorderLayout.CENTER)
    }

    private var popup: Popup? = null
    private var activeSlash: SkillSlashQuery.ActiveSlash? = null
    private var suppressDocEvents = false
    private var wired = false

    /** Whether the slash/picker popup is currently showing. */
    val isPopupVisible: Boolean get() = popup != null

    /**
     * Attach document + mouse listeners once. Key routing is handled by the tool window
     * ([tryHandleKey]) so Enter can select without sending.
     */
    fun wire() {
        if (wired) return
        wired = true
        textArea.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = scheduleRefresh()
            override fun removeUpdate(e: DocumentEvent?) = scheduleRefresh()
            override fun changedUpdate(e: DocumentEvent?) = scheduleRefresh()
        })
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent?) {
                if (e != null && e.clickCount >= 1 && list.selectedIndex >= 0) {
                    acceptSelection()
                }
            }
        })
        list.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent?) {
                if (e == null) return
                when (e.keyCode) {
                    KeyEvent.VK_ENTER, KeyEvent.VK_TAB -> {
                        e.consume()
                        acceptSelection()
                    }
                    KeyEvent.VK_ESCAPE -> {
                        e.consume()
                        closePopup()
                    }
                }
            }
        })
    }

    /** Small toolbar button that opens the full skill list (same catalog as slash). */
    fun createPickerButton(): JButton = JButton(AllIcons.Nodes.Toolbox).apply {
        toolTipText = "Pick a skill (same catalog as typing / in the composer)"
        isBorderPainted = false
        preferredSize = Dimension(28, 24)
        addActionListener { openFullPicker() }
    }

    /**
     * Handle keys while the composer is focused.
     * @return true if the key was consumed (caller must not send / insert default action).
     */
    fun tryHandleKey(e: KeyEvent): Boolean {
        if (!isPopupVisible) {
            // Ctrl+/ or Alt+/ can open full picker without a slash token
            if (e.keyCode == KeyEvent.VK_SLASH && e.isControlDown) {
                openFullPicker()
                e.consume()
                return true
            }
            return false
        }
        when (e.keyCode) {
            KeyEvent.VK_ESCAPE -> {
                closePopup()
                e.consume()
                return true
            }
            KeyEvent.VK_ENTER, KeyEvent.VK_TAB -> {
                acceptSelection()
                e.consume()
                return true
            }
            KeyEvent.VK_UP -> {
                moveSelection(-1)
                e.consume()
                return true
            }
            KeyEvent.VK_DOWN -> {
                moveSelection(1)
                e.consume()
                return true
            }
            else -> return false
        }
    }

    /** Enter/Tab accept when popup open — used from InputMap send-message action. */
    fun tryAcceptSelection(): Boolean {
        if (!isPopupVisible) return false
        acceptSelection()
        return true
    }

    fun closePopup() {
        popup?.hide()
        popup = null
        activeSlash = null
        listModel.clear()
    }

    /** Open unfiltered list (picker button). Selection still does not send. */
    fun openFullPicker() {
        val skills = getCatalog()
        if (skills.isEmpty()) return
        activeSlash = null
        showSkills(skills, anchorToCaret = false)
    }

    fun refreshFromComposer() {
        if (suppressDocEvents) return
        val text = textArea.text ?: ""
        val caret = textArea.caretPosition
        val slash = SkillSlashQuery.findActiveSlash(text, caret)
        if (slash == null) {
            // Auto-close only when leaving a slash token (not when full picker was opened via button)
            if (activeSlash != null) closePopup()
            return
        }
        activeSlash = slash
        val filtered = SkillSlashQuery.filterSkills(getCatalog(), slash.query)
        if (filtered.isEmpty()) {
            popup?.hide()
            popup = null
            listModel.clear()
            return
        }
        showSkills(filtered, anchorToCaret = true)
    }

    private fun scheduleRefresh() {
        if (suppressDocEvents) return
        SwingUtilities.invokeLater { refreshFromComposer() }
    }

    private fun showSkills(skills: List<SkillRef>, anchorToCaret: Boolean) {
        listModel.clear()
        skills.forEach { listModel.addElement(it) }
        if (listModel.size() > 0) {
            list.selectedIndex = 0
            list.ensureIndexIsVisible(0)
        }
        val size = Dimension(
            320.coerceAtLeast(textArea.width.coerceAtMost(480).coerceAtLeast(240)),
            (28 + (skills.size.coerceAtMost(8) * 22) + 12).coerceIn(80, 220)
        )
        popupPanel.preferredSize = size
        popupPanel.revalidate()

        val location = if (anchorToCaret) caretScreenLocation() else buttonishLocation()
        // Re-show to update position/size
        popup?.hide()
        popup = PopupFactory.getSharedInstance().getPopup(
            textArea,
            popupPanel,
            location.x,
            location.y
        )
        popup?.show()
    }

    private fun caretScreenLocation(): Point {
        return try {
            val caret = textArea.caretPosition
            @Suppress("DEPRECATION")
            val rect: Rectangle? = textArea.modelToView(caret)
            if (rect == null) return buttonishLocation()
            val p = Point(rect.x, rect.y + rect.height + 2)
            SwingUtilities.convertPointToScreen(p, textArea)
            p
        } catch (_: Exception) {
            buttonishLocation()
        }
    }

    private fun buttonishLocation(): Point {
        val p = Point(0, textArea.height)
        SwingUtilities.convertPointToScreen(p, textArea)
        return p
    }

    private fun moveSelection(delta: Int) {
        if (listModel.isEmpty) return
        val next = (list.selectedIndex + delta).coerceIn(0, listModel.size() - 1)
        list.selectedIndex = next
        list.ensureIndexIsVisible(next)
    }

    private fun acceptSelection() {
        val skill = list.selectedValue ?: listModel.getElementAt(0) ?: return
        applySkill(skill)
    }

    private fun applySkill(skill: SkillRef) {
        val slash = activeSlash
        suppressDocEvents = true
        try {
            if (slash != null) {
                val (newText, newCaret) = SkillSlashQuery.removeSlashToken(textArea.text ?: "", slash)
                textArea.text = newText
                textArea.caretPosition = newCaret.coerceIn(0, newText.length)
            }
            onSkillSelected(skill)
        } finally {
            suppressDocEvents = false
            closePopup()
        }
        textArea.requestFocusInWindow()
    }
}
