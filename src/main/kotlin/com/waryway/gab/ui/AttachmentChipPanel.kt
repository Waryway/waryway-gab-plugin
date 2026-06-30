package com.waryway.gab.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.waryway.gab.model.ContextAttachment
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JPanel

/**
 * Removable summary bubbles for attached project files (shows filename, keeps full path internally).
 */
class AttachmentChipPanel(
    private val onRemove: (ContextAttachment) -> Unit
) : JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)) {

    init {
        isOpaque = false
        border = JBUI.Borders.empty(2, 0)
    }

    fun setAttachments(attachments: List<ContextAttachment>) {
        removeAll()
        if (attachments.isEmpty()) {
            val hint = JBLabel("Drop files here to attach project context").apply {
                foreground = GabTheme.inactiveText
                font = font.deriveFont(Font.ITALIC, 11f)
            }
            add(hint)
        } else {
            attachments.forEach { add(buildChip(it)) }
        }
        revalidate()
        repaint()
    }

    private fun buildChip(attachment: ContextAttachment): JPanel {
        val chip = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            isOpaque = true
            background = GabTheme.chipBackground
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(GabTheme.chipBorder, 1, true),
                JBUI.Borders.empty(2, 6)
            )
            toolTipText = attachment.path ?: attachment.displayName
        }

        val icon = when (attachment.type) {
            ContextAttachment.Type.FILE -> AllIcons.FileTypes.Any_type
            ContextAttachment.Type.SELECTION -> AllIcons.Actions.EditSource
            ContextAttachment.Type.SYMBOL -> AllIcons.Nodes.Class
            ContextAttachment.Type.DIRECTORY_SUMMARY -> AllIcons.Nodes.Folder
            ContextAttachment.Type.ERROR -> AllIcons.General.Error
        }

        chip.add(JBLabel(icon))
        chip.add(JBLabel(attachment.displayName).apply {
            font = font.deriveFont(Font.PLAIN, 11f)
            foreground = GabTheme.textForeground
        })

        val removeBtn = JButton(AllIcons.General.Close).apply {
            isBorderPainted = false
            isContentAreaFilled = false
            preferredSize = java.awt.Dimension(18, 18)
            toolTipText = "Remove attachment"
            addActionListener { onRemove(attachment) }
        }
        chip.add(removeBtn)
        return chip
    }
}