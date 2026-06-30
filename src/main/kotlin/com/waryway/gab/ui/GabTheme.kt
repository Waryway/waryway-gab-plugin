package com.waryway.gab.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import java.awt.Color

/** Theme-aware colors for Waryway Gab UI components. */
object GabTheme {
    val panelBackground: Color get() = UIUtil.getPanelBackground()
    val textForeground: Color get() = UIUtil.getLabelForeground()
    val inactiveText: Color get() = UIUtil.getInactiveTextColor()
    val borderColor: Color get() = JBColor.border()
    val accent: Color = JBColor(Color(0x4A90D9), Color(0x6AAEE0))

    val chipBackground: Color = JBColor(Color(0xE3F2FD), Color(0x2B3D50))
    val chipBorder: Color = JBColor(Color(0x90CAF9), Color(0x4A6A8A))

    val activeTabBackground: Color = JBColor(Color(0xEEF4FB), Color(0x3C4F63))

    val userMessageBg: Color = JBColor(Color(0xE8F4FD), Color(0x1E3A4F))
    val userMessageFg: Color = JBColor(Color(0x1A1A1A), Color(0xD4D4D4))
    val userMessageBorder: Color = JBColor(Color(0xB0C4DE), Color(0x3A5A6F))

    val assistantMessageBg: Color = JBColor(Color(0xF5F5F5), Color(0x2B2B2B))
    val assistantMessageFg: Color = JBColor(Color(0x1A1A1A), Color(0xD4D4D4))
    val assistantMessageBorder: Color = JBColor(Color(0xCCCCCC), Color(0x4A4A4A))

    val systemMessageBg: Color = JBColor(Color(0xFFF8E7), Color(0x3D3520))
    val systemMessageFg: Color = JBColor(Color(0x5C4A00), Color(0xE6D9A8))
    val systemMessageBorder: Color = JBColor(Color(0xE6D9A8), Color(0x5C4A00))
}