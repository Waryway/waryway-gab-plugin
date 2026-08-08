package com.waryway.gab.ui

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JComponent

class WarywayGabToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel: JComponent = try {
            WarywayGabToolWindowPanel(project)
        } catch (t: Throwable) {
            LOG.error("Waryway Agent tool window failed to load", t)
            fallbackPanel(t)
        }
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }

    /**
     * Always show something if panel construction throws (offline LLM must never blank the tool window).
     */
    private fun fallbackPanel(error: Throwable): JComponent {
        val detail = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.empty(16)
            add(
                JBLabel(
                    "<html><b>Waryway Agent failed to open</b><br/>" +
                        "The tool window still loaded so you can recover.<br/><br/>" +
                        "<code>${escapeHtml(detail)}</code><br/><br/>" +
                        "Try: restart the IDE, or reinstall the plugin from Disk.<br/>" +
                        "Local LLM offline is OK — the chat UI should still appear.</html>"
                ),
                BorderLayout.NORTH
            )
        }
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    companion object {
        private val LOG = Logger.getInstance(WarywayGabToolWindowFactory::class.java)
    }
}