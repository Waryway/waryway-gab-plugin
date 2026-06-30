package com.waryway.gab.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.waryway.gab.chat.TokenEstimator
import com.waryway.gab.model.CreditsInfo
import com.waryway.gab.model.ModelProvider
import com.waryway.gab.model.Usage
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JPanel
import javax.swing.JProgressBar

/**
 * Always-visible token, credit, and context budget meters.
 */
class UsageMeterPanel : JPanel(BorderLayout(8, 0)) {

    private val creditsLabel = JBLabel()
    private val sessionLabel = JBLabel()
    private val contextLabel = JBLabel()
    private val contextBar = JProgressBar(0, 100).apply {
        preferredSize = java.awt.Dimension(120, 14)
        isStringPainted = true
        foreground = GabTheme.accent
    }

    private var activeProvider: ModelProvider = ModelProvider.GROK

    init {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, GabTheme.borderColor),
            JBUI.Borders.empty(6, 4)
        )
        isOpaque = true
        background = GabTheme.panelBackground

        val left = JPanel().apply {
            layout = java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 12, 0)
            isOpaque = false
            add(labeledRow(AllIcons.General.Information, creditsLabel, "Account usage"))
            add(labeledRow(AllIcons.Actions.Profile, sessionLabel, "Session"))
        }

        val right = JPanel().apply {
            layout = java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 0)
            isOpaque = false
            add(JBLabel(AllIcons.Actions.OpenNewTab).apply { toolTipText = "Context window usage" })
            add(contextLabel)
            add(contextBar)
        }

        add(left, BorderLayout.WEST)
        add(right, BorderLayout.EAST)
        reset()
    }

    private fun labeledRow(icon: javax.swing.Icon, valueLabel: JBLabel, tooltip: String): JPanel =
        JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            toolTipText = tooltip
            add(JBLabel(icon))
            valueLabel.font = valueLabel.font.deriveFont(Font.PLAIN, 11f)
            add(valueLabel)
        }

    fun reset() {
        updateCredits(null, activeProvider)
        updateSession(Usage.ZERO, 0)
        updateContext(0, null)
    }

    fun setProvider(provider: ModelProvider) {
        activeProvider = provider
    }

    fun updateCredits(credits: CreditsInfo?, provider: ModelProvider = activeProvider) {
        activeProvider = provider
        creditsLabel.text = when {
            provider.supportsCredits && credits != null -> "Credits: ${credits.displayBalance()}"
            provider.supportsCredits -> "Credits: —"
            else -> "${provider.displayName}: token usage"
        }
    }

    fun updateSession(sessionUsage: Usage, estimatedContext: Int) {
        sessionLabel.text = "Tokens: ${TokenEstimator.formatTokenCount(sessionUsage.totalTokens)} " +
            "(${TokenEstimator.formatTokenCount(estimatedContext)} est. context)"
        if (sessionUsage.creditsUsed > 0) {
            sessionLabel.text += " | ${"%.1f".format(sessionUsage.creditsUsed)} cr"
        }
    }

    fun updateContext(estimatedTokens: Int, contextLimit: Int?) {
        val pct = TokenEstimator.contextPercent(estimatedTokens, contextLimit)
        if (contextLimit != null && contextLimit > 0) {
            contextLabel.text = "${TokenEstimator.formatTokenCount(estimatedTokens)} / " +
                TokenEstimator.formatTokenCount(contextLimit)
            contextBar.value = pct
            contextBar.string = "$pct%"
            contextBar.foreground = when {
                pct >= 85 -> JBColor(0xD32F2F, 0xEF5350)
                pct >= 60 -> JBColor(0xF57C00, 0xFFB74D)
                else -> GabTheme.accent
            }
        } else {
            contextLabel.text = "${TokenEstimator.formatTokenCount(estimatedTokens)} / —"
            contextBar.value = 0
            contextBar.string = "—"
        }
    }

    fun updateLastTurn(usage: Usage) {
        val turn = "Last turn: +${usage.promptTokens}p / +${usage.completionTokens}c"
        val creditPart = if (usage.creditsUsed > 0) " (${"%.1f".format(usage.creditsUsed)} cr)" else ""
        contextLabel.toolTipText = turn + creditPart
    }
}