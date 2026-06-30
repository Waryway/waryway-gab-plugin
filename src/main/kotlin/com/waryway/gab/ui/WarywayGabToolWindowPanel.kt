package com.waryway.gab.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.dnd.DnDSupport
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.waryway.gab.chat.AgentSession
import com.waryway.gab.chat.ContextCompactor
import com.waryway.gab.chat.ConversationManager
import com.waryway.gab.chat.TokenEstimator
import com.waryway.gab.diagnostics.SessionLog
import com.waryway.gab.model.Conversation
import com.waryway.gab.client.GabClient
import com.waryway.gab.model.ChatMessage
import com.waryway.gab.model.ContextAttachment
import com.waryway.gab.model.CreditsInfo
import com.waryway.gab.model.ModelCatalog
import com.waryway.gab.model.ModelProvider
import com.waryway.gab.settings.WarywayGabSettings
import com.waryway.gab.skills.InputNormalizer
import com.waryway.gab.skills.SkillRegistry
import kotlinx.coroutines.runBlocking
import java.awt.*
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.*
import javax.swing.text.DefaultEditorKit

/**
 * Main content for the Waryway Agent right-side tool window.
 */
class WarywayGabToolWindowPanel(private val project: Project) : JBPanel<WarywayGabToolWindowPanel>(BorderLayout()) {

    private val settings = WarywayGabSettings.getInstance()
    private val conversationManager = ConversationManager()

    private var loadedModels: List<GabClient.ModelInfo> = emptyList()
    private var creditsInfo: CreditsInfo? = null

    private val providerCombo = JComboBox(ModelProvider.selectable.map { it.displayName }.toTypedArray())
    private val modelCombo = JComboBox(
        ModelCatalog.fallbackModelIds(ModelProvider.LOCAL_LLM).toTypedArray()
    )
    private val thinkingCombo = JComboBox(arrayOf("auto", "none", "standard", "deep"))
    private val skillCombo = JComboBox(SkillRegistry.all.map { it.name }.toTypedArray())
    private val skillHintLabel = JBLabel().apply {
        font = font.deriveFont(java.awt.Font.ITALIC, 11f)
        foreground = JBColor.GRAY
        border = JBUI.Borders.emptyLeft(4)
    }
    private val stopButton = JButton("Stop", AllIcons.Actions.Suspend).apply { isEnabled = false }
    private var localLlmWorkbench: LocalLlmWorkbenchPanel? = null
    private var lastUserQuestion: String = ""
    private var lastAssistantAnswer: String = ""

    private val activityLogPanel = ActivityLogPanel()
    private val messageList = ChatMessageListPanel()
    private val sessionLog: SessionLog = SessionLog(onLine = { line: String ->
        activityLogPanel.appendLine(line)
        if (line.contains("] ERR  ")) {
            SwingUtilities.invokeLater {
                messageList.appendToAgentTurn("⚠ $line")
            }
        }
    })
    private val usageMeter = UsageMeterPanel()
    private lateinit var conversationTabBar: ConversationTabBar
    private lateinit var attachmentChipPanel: AttachmentChipPanel

    private val inputArea = JBTextArea(3, 40).apply {
        lineWrap = true
        wrapStyleWord = true
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(JBColor.border()),
            JBUI.Borders.empty(4)
        )
        toolTipText = "Enter to send · Shift+Enter or Ctrl+Enter for new line · Drop files to attach"
    }
    private val sendButton = JButton("Send", AllIcons.Actions.Execute)

    private val agentCancelled = AtomicBoolean(false)

    private val coachingPanel = createCoachingPanel()

    init {
        border = JBUI.Borders.empty(8)
        activityLogPanel.onClearRequested = { sessionLog.clear() }
        rebuildUI()
    }

    private fun rebuildUI() {
        removeAll()
        if (settings.hasAnyApiKey()) {
            add(createMainChatPanel(), BorderLayout.CENTER)
            refreshAccountInfo()
        } else {
            add(coachingPanel, BorderLayout.CENTER)
        }
        revalidate()
        repaint()
    }

    private fun createMainChatPanel(): JComponent {
        conversationTabBar = ConversationTabBar(
            onNewConversation = { onNewConversation() },
            onSwitchConversation = { onSwitchConversation(it) }
        )

        attachmentChipPanel = AttachmentChipPanel { attachment ->
            conversationManager.removeAttachment(attachment)
            refreshAttachmentChips()
            refreshUsageMeters()
        }

        val topBar = JPanel(BorderLayout(0, 4)).apply {
            add(conversationTabBar, BorderLayout.NORTH)

            val controls = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
                add(JBLabel(AllIcons.Actions.Forward).apply { toolTipText = "Provider (Gab AI or Grok)" })
                add(providerCombo)
                add(JBLabel(AllIcons.Actions.Download).apply { toolTipText = "Model" })
                add(modelCombo)
                val refreshModels = JButton(AllIcons.Actions.Refresh).apply {
                    toolTipText = "Refresh models from active provider"
                    isBorderPainted = false
                    preferredSize = Dimension(28, 24)
                    addActionListener { refreshModelsFromApi() }
                }
                add(refreshModels)
                add(JBLabel(AllIcons.Actions.Lightning).apply { toolTipText = "Thinking level" })
                add(thinkingCombo)
                add(JBLabel(AllIcons.Nodes.Toolbox).apply { toolTipText = "Guided skill — keeps prompts on rails" })
                add(skillCombo)
                add(skillHintLabel)
                add(Box.createHorizontalGlue())
                stopButton.addActionListener { onStop() }
                add(stopButton)
            }
            add(controls, BorderLayout.SOUTH)
        }

        val inputScroll = JBScrollPane(inputArea)
        val inputPanel = JPanel(BorderLayout(4, 4)).apply {
            add(attachmentChipPanel, BorderLayout.NORTH)
            add(inputScroll, BorderLayout.CENTER)
            val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                val frankHint = JBLabel(AllIcons.General.InspectionsOK).apply {
                    toolTipText = "Frank: articles and filler phrases are stripped from your message before sending"
                    border = JBUI.Borders.emptyRight(4)
                }
                add(frankHint)
                sendButton.addActionListener { onSend() }
                add(sendButton)
            }
            add(buttons, BorderLayout.EAST)
        }

        setupInputKeyBindings()

        val chatColumn = JPanel(BorderLayout()).apply {
            add(messageList, BorderLayout.CENTER)
            add(activityLogPanel, BorderLayout.SOUTH)
        }
        val center = JPanel(BorderLayout()).apply {
            add(chatColumn, BorderLayout.CENTER)
            add(usageMeter, BorderLayout.SOUTH)
        }

        val provider = settings.activeProvider
        providerCombo.selectedItem = provider.displayName
        usageMeter.setProvider(provider)
        modelCombo.selectedItem = ModelCatalog.resolveSelection(
            ModelCatalog.fallbackAsModelInfo(provider),
            settings.getLastUsedModel(provider),
            settings.getDefaultModel(provider),
            provider
        )
        providerCombo.addActionListener { onProviderChanged() }
        modelCombo.addActionListener {
            val p = settings.activeProvider
            settings.setLastUsedModel(modelCombo.selectedItem?.toString() ?: settings.getLastUsedModel(p), p)
            refreshUsageMeters()
        }
        thinkingCombo.selectedItem = settings.thinkingLevel
        thinkingCombo.addActionListener {
            settings.thinkingLevel = thinkingCombo.selectedItem?.toString() ?: "auto"
        }
        skillCombo.selectedItem = skillById(settings.selectedSkillId)?.name ?: SkillRegistry.all.first().name
        skillCombo.addActionListener { onSkillChanged() }
        onSkillChanged()

        refreshConversationUi()
        refreshAttachmentChips()
        refreshUsageMeters()
        loadMessagesForActiveConversation()

        val workbench = if (settings.activeProvider == ModelProvider.LOCAL_LLM) {
            LocalLlmWorkbenchPanel(
                settings = settings,
                sessionLog = sessionLog,
                onPresetChanged = { preset ->
                    sessionLog.system("preset → $preset")
                },
                onStatusMessage = { msg ->
                    sessionLog.system(msg)
                    messageList.addMessage(ChatMessageListPanel.MessageRole.SYSTEM, msg)
                },
                onCompactContext = { compactConversationNow() },
                lastExchange = {
                    val q = lastUserQuestion.trim()
                    val a = lastAssistantAnswer.trim()
                    if (q.isNotEmpty() && a.isNotEmpty()) q to a else null
                }
            ).also { localLlmWorkbench = it }
        } else {
            localLlmWorkbench = null
            null
        }

        val mainPanel = JPanel(BorderLayout(0, 8)).apply {
            val north = JPanel(BorderLayout()).apply {
                add(topBar, BorderLayout.NORTH)
                if (workbench != null) add(workbench, BorderLayout.SOUTH)
            }
            add(north, BorderLayout.NORTH)
            add(center, BorderLayout.CENTER)
            add(inputPanel, BorderLayout.SOUTH)
        }
        setupDragAndDrop(mainPanel, inputPanel, inputScroll, inputArea, attachmentChipPanel)
        return mainPanel
    }

    private fun onProviderChanged() {
        val selected = providerCombo.selectedItem?.toString() ?: return
        val provider = ModelProvider.selectable.find { it.displayName == selected } ?: return
        sessionLog.system("provider → ${provider.displayName}")
        settings.activeProvider = provider
        creditsInfo = null
        loadedModels = emptyList()
        rebuildUI()
        if (!settings.hasApiKey(provider)) {
            val hint = if (provider == ModelProvider.LOCAL_LLM) {
                "Local LLM server not configured. Set base URL in Plugin Settings and run scripts\\localllm-run.bat."
            } else {
                "No ${provider.displayName} API key configured. Add one in Plugin Settings."
            }
            messageList.addMessage(ChatMessageListPanel.MessageRole.SYSTEM, hint)
        }
    }

    /** Enter sends; Shift+Enter or Ctrl+Enter inserts a newline. */
    private fun setupInputKeyBindings() {
        val inputMap = inputArea.getInputMap(JComponent.WHEN_FOCUSED)
        val actionMap = inputArea.actionMap

        val enter = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)
        val shiftEnter = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK)
        val ctrlEnter = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK)

        inputMap.put(enter, "send-message")
        inputMap.put(shiftEnter, DefaultEditorKit.insertBreakAction)
        inputMap.put(ctrlEnter, DefaultEditorKit.insertBreakAction)

        actionMap.put("send-message", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                if (inputArea.text.isNotBlank()) onSend()
            }
        })
    }

    /** IntelliJ DnD manager handles in-IDE drags; AWT DropTarget only sees OS-level file drops. */
    private fun setupDragAndDrop(vararg targets: JComponent) {
        targets.forEach { component ->
            DnDSupport.createBuilder(component)
                .disableAsSource()
                .enableAsNativeTarget()
                .setDisposableParent(project)
                .setTargetChecker { event ->
                    val accepted = FileDropUtil.canAccept(event)
                    event.setDropPossible(accepted, if (accepted) "Attach file as context" else "")
                    !accepted
                }
                .setDropHandler { event ->
                    FileDropUtil.extractFiles(event).forEach { addFileAttachment(it) }
                }
                .install()
        }
    }

    private fun addFileAttachment(file: VirtualFile) {
        if (file.isDirectory) return
        val relativePath = toProjectRelativePath(file)
        val attachment = ContextAttachment(
            type = ContextAttachment.Type.FILE,
            path = relativePath,
            displayName = file.name,
            content = readFilePreview(file)
        )
        conversationManager.addAttachment(attachment)
        refreshAttachmentChips()
        refreshUsageMeters()
    }

    private fun toProjectRelativePath(file: VirtualFile): String {
        val base = project.basePath ?: return file.path
        val baseDir = project.baseDir ?: return file.path.removePrefix(base).removePrefix("/").removePrefix("\\")
        return VfsUtil.getRelativePath(file, baseDir)
            ?: file.path.removePrefix(base).removePrefix("/").removePrefix("\\")
    }

    private fun readFilePreview(file: VirtualFile, maxChars: Int = 8000): String? {
        return try {
            val text = String(file.contentsToByteArray(), file.charset)
            if (text.length <= maxChars) text else text.take(maxChars) + "\n… (truncated)"
        } catch (_: Exception) {
            null
        }
    }

    private fun onNewConversation() {
        conversationManager.createNew()
        inputArea.text = ""
        refreshConversationUi()
        refreshAttachmentChips()
        refreshUsageMeters()
        loadMessagesForActiveConversation()
    }

    private fun onSwitchConversation(id: String) {
        conversationManager.switchTo(id)
        inputArea.text = ""
        refreshConversationUi()
        refreshAttachmentChips()
        refreshUsageMeters()
        loadMessagesForActiveConversation()
    }

    private fun refreshConversationUi() {
        val active = conversationManager.getActive()
        conversationTabBar.update(conversationManager.getAll(), active.id)
    }

    private fun refreshAttachmentChips() {
        attachmentChipPanel.setAttachments(conversationManager.getAttachments().toList())
    }

    private fun refreshUsageMeters() {
        val provider = settings.activeProvider
        val conv = conversationManager.getActive()
        val model = (modelCombo.selectedItem ?: settings.getLastUsedModel(provider)).toString()
        val contextLimit = loadedModels.find { it.id == model }?.contextWindow
        val estimated = estimatedContextTokens(conv)
        usageMeter.updateCredits(creditsInfo, provider)
        usageMeter.updateSession(conv.usage, estimated)
        usageMeter.updateContext(estimated, contextLimit)
    }

    private fun loadMessagesForActiveConversation() {
        val conv = conversationManager.getActive()
        val entries = conv.messages.map { msg ->
            when (msg.role) {
                ChatMessage.Role.user -> ChatMessageListPanel.MessageRole.USER to msg.content
                ChatMessage.Role.assistant -> ChatMessageListPanel.MessageRole.ASSISTANT to msg.content
                else -> ChatMessageListPanel.MessageRole.SYSTEM to msg.content
            }
        }
        messageList.loadMessages(entries)
    }

    private fun applyModelsToCombo(
        models: List<GabClient.ModelInfo>,
        provider: ModelProvider = settings.activeProvider
    ) {
        val sorted = ModelCatalog.sortForDisplay(models, provider)
        loadedModels = sorted
        val selection = ModelCatalog.resolveSelection(
            sorted,
            settings.getLastUsedModel(provider),
            settings.getDefaultModel(provider),
            provider
        )
        modelCombo.removeAllItems()
        sorted.forEach { modelCombo.addItem(it.id) }
        modelCombo.selectedItem = selection
        settings.setLastUsedModel(selection, provider)
    }

    private fun refreshAccountInfo(provider: ModelProvider = settings.activeProvider) {
        if (!settings.hasApiKey(provider)) return
        Thread {
            try {
                val client = settings.createClient(provider, sessionLog)
                val credits = runBlocking { client.getCredits() }
                val models = runBlocking { client.listModels() }
                SwingUtilities.invokeLater {
                    if (provider != settings.activeProvider) return@invokeLater
                    creditsInfo = credits
                    usageMeter.setProvider(provider)
                    if (models.isNotEmpty()) applyModelsToCombo(models, provider)
                    refreshUsageMeters()
                }
            } catch (_: Exception) {
                // Credits endpoint may be unavailable; meters show placeholders
            }
        }.start()
    }

    private fun createCoachingPanel(): JComponent {
        val outer = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(16)
        }

        outer.add(JBLabel(AllIcons.Toolwindows.ToolWindowMessages).apply {
            alignmentX = Component.LEFT_ALIGNMENT
        })
        outer.add(Box.createVerticalStrut(8))
        outer.add(JBLabel("<html><b>Welcome to Waryway Agent</b></html>").apply {
            alignmentX = Component.LEFT_ALIGNMENT
            font = font.deriveFont(Font.BOLD, 16f)
        })
        outer.add(Box.createVerticalStrut(8))
        outer.add(JBLabel(
            "<html>Connect <b>Local LLM</b> (offline), <b>Grok (xAI)</b>, and/or <b>Gab AI</b>.<br/>" +
                "Local LLM uses your stack <code>apps/localllm</code> server — no cloud credits.</html>"
        ).apply {
            alignmentX = Component.LEFT_ALIGNMENT
        })
        outer.add(Box.createVerticalStrut(16))

        val providerSelector = JComboBox(ModelProvider.selectable.map { it.displayName }.toTypedArray()).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(400, 28)
            selectedItem = settings.activeProvider.displayName
        }
        outer.add(JBLabel("Provider to configure:").apply { alignmentX = Component.LEFT_ALIGNMENT })
        outer.add(providerSelector)
        outer.add(Box.createVerticalStrut(8))

        val stepsPane = JEditorPane().apply {
            contentType = "text/html"
            isEditable = false
            alignmentX = Component.LEFT_ALIGNMENT
            updateCoachingSteps(settings.activeProvider)
        }
        val pasteLabel = JBLabel().apply {
            alignmentX = Component.LEFT_ALIGNMENT
            text = "Paste your ${settings.activeProvider.displayName} API key here:"
        }
        providerSelector.addActionListener {
            val provider = ModelProvider.selectable[providerSelector.selectedIndex]
            stepsPane.updateCoachingSteps(provider)
            pasteLabel.text = "Paste your ${provider.displayName} API key here:"
        }
        outer.add(stepsPane)
        outer.add(Box.createVerticalStrut(16))
        outer.add(pasteLabel)

        val keyField = JBPasswordField().apply {
            columns = 36
            maximumSize = Dimension(400, 28)
        }
        keyField.alignmentX = Component.LEFT_ALIGNMENT
        outer.add(keyField)
        outer.add(Box.createVerticalStrut(8))

        val buttonRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            val pasteBtn = JButton("Use Key & Continue", AllIcons.RunConfigurations.TestState.Run)
            pasteBtn.addActionListener {
                val key = String(keyField.password)
                val provider = ModelProvider.selectable[providerSelector.selectedIndex]
                if (provider == ModelProvider.LOCAL_LLM || key.isNotBlank()) {
                    settings.activeProvider = provider
                    if (provider != ModelProvider.LOCAL_LLM) {
                        settings.setApiKey(key, provider)
                    }
                    rebuildUI()
                } else {
                    JOptionPane.showMessageDialog(this, "Please paste a key first.")
                }
            }
            add(pasteBtn)

            val localBtn = JButton("Use Local LLM", AllIcons.RunConfigurations.TestState.Run)
            localBtn.addActionListener {
                settings.activeProvider = ModelProvider.LOCAL_LLM
                rebuildUI()
            }
            add(localBtn)

            val openSettings = JButton("Open Plugin Settings", AllIcons.General.Settings)
            openSettings.addActionListener {
                com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                    .showSettingsDialog(project, "Waryway Agent")
            }
            add(openSettings)

            val testBtn = JButton("Test Key", AllIcons.RunConfigurations.TestState.Run)
            testBtn.addActionListener {
                val key = String(keyField.password)
                val provider = ModelProvider.selectable[providerSelector.selectedIndex]
                if (key.isBlank()) {
                    JOptionPane.showMessageDialog(this, "Paste a key first.")
                    return@addActionListener
                }
                testKey(key, provider) { success, msg ->
                    SwingUtilities.invokeLater {
                        JOptionPane.showMessageDialog(this, if (success) "Success: $msg" else "Failed: $msg")
                        if (success) {
                            settings.activeProvider = provider
                            settings.setApiKey(key, provider)
                            rebuildUI()
                        }
                    }
                }
            }
            add(testBtn)
        }
        outer.add(buttonRow)
        outer.add(Box.createVerticalGlue())

        return JBScrollPane(outer).apply { border = null }
    }

    private fun JEditorPane.updateCoachingSteps(provider: ModelProvider) {
        val steps = when (provider) {
            ModelProvider.LOCAL_LLM -> listOf(
                "1. In the stack repo, run <b>scripts\\localllm-run.bat</b> (or <code>bazel run //apps/localllm:localllm-dev</code>)",
                "2. Wait for <a href='http://127.0.0.1:7400/healthz'>http://127.0.0.1:7400/healthz</a> to return ready",
                "3. Click <b>Use Local LLM</b> below — no cloud API key required",
                "4. IDE tools (read_file, search, edit, build) work via the plugin; model runs offline"
            )
            ModelProvider.GROK -> listOf(
                "1. Go to <a href='https://console.x.ai'>https://console.x.ai</a> and sign in",
                "2. Open <b>API Keys</b> and create a new key",
                "3. Copy the key (shown only once)",
                "4. Paste it below — uses your xAI Grok quota directly"
            )
            ModelProvider.GAB_AI -> listOf(
                "1. Go to <a href='https://gab.ai'>https://gab.ai</a> and sign in",
                "2. Upgrade to a <b>Plus</b> plan (required for API access)",
                "3. Open <b>Settings</b> → <b>API Settings</b>",
                "4. Generate a new API key and paste it below"
            )
        }
        text = "<html><body style='font-family:sans-serif;font-size:12pt'>" + steps.joinToString("<br/>") + "</body></html>"
        addHyperlinkListener { e ->
            if (e.eventType == javax.swing.event.HyperlinkEvent.EventType.ACTIVATED) {
                try {
                    com.intellij.ide.BrowserUtil.browse(e.url?.toString() ?: provider.keyHelpUrl)
                } catch (_: Exception) {}
            }
        }
    }

    private fun testKey(key: String, provider: ModelProvider, callback: (Boolean, String) -> Unit) {
        Thread {
            try {
                if (provider == ModelProvider.LOCAL_LLM) {
                    settings.activeProvider = ModelProvider.LOCAL_LLM
                } else {
                    settings.setApiKey(key, provider)
                }
                val client = settings.createClient(provider, sessionLog)
                val models = runBlocking { client.listModels() }
                val filtered = ModelCatalog.filterForProvider(models, provider)
                callback(true, "${provider.displayName}: ${filtered.size} models available.")
            } catch (e: Exception) {
                callback(false, e.message ?: "Unknown error")
            }
        }.start()
    }

    private fun skillById(id: String): SkillRegistry.GuidedSkill? =
        SkillRegistry.all.find { it.id == id }

    private fun selectedSkill(): SkillRegistry.GuidedSkill? {
        val name = skillCombo.selectedItem?.toString() ?: return null
        return SkillRegistry.all.find { it.name == name }
    }

    private fun onSkillChanged() {
        val skill = selectedSkill() ?: return
        settings.selectedSkillId = skill.id
        skillHintLabel.text = skill.hint
        inputArea.toolTipText = skill.hint
        if (settings.activeProvider == ModelProvider.LOCAL_LLM) {
            skill.localLlmPreset?.let { settings.localLlmPreset = it }
        }
    }

    private fun onSend() {
        val rawText = inputArea.text.trimEnd()
        if (rawText.isBlank()) return

        val normalizedText = InputNormalizer.normalize(rawText)
        if (normalizedText.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Message is empty after Frank compression. Add more substance.")
            return
        }

        val skill = selectedSkill()
        val guidedText = SkillRegistry.apply(skill, normalizedText)
        val payload = buildMessagePayload(guidedText)
        lastUserQuestion = normalizedText
        lastAssistantAnswer = ""
        val displayText = buildString {
            if (skill != null && skill.id != "none") {
                append("[${skill.name}] ")
            }
            append(normalizedText)
            if (normalizedText != rawText) {
                append("\n\n(frank: ${rawText.length - normalizedText.length} chars removed)")
            }
        }

        val conv = conversationManager.getActive()
        val userMsg = ChatMessage(ChatMessage.Role.user, payload)
        conv.addMessage(userMsg)

        messageList.addMessage(ChatMessageListPanel.MessageRole.USER, displayText)
        inputArea.text = ""
        conversationManager.clearAttachments()
        refreshAttachmentChips()
        sendButton.isEnabled = false
        stopButton.isEnabled = true

        val provider = settings.activeProvider
        if (!settings.hasApiKey(provider)) {
            val hint = if (provider == ModelProvider.LOCAL_LLM) {
                "Local LLM server not reachable — start scripts\\localllm-run.bat first."
            } else {
                "No ${provider.displayName} API key configured."
            }
            messageList.addMessage(ChatMessageListPanel.MessageRole.SYSTEM, hint)
            sendButton.isEnabled = true
            stopButton.isEnabled = false
            return
        }

        val model = (modelCombo.selectedItem ?: ModelCatalog.defaultModelId(provider)).toString()
        agentCancelled.set(false)
        messageList.beginAgentTurn()
        sessionLog.system("send: model=$model skill=${skill?.id ?: "none"}")

        Thread {
            try {
                val client = settings.createClient(provider, sessionLog)
                val preset = when {
                    provider == ModelProvider.LOCAL_LLM -> skill?.localLlmPreset ?: settings.localLlmPreset
                    else -> null
                }
                val session = AgentSession(
                    project = project,
                    client = client,
                    sessionLog = sessionLog,
                    onStatus = { status ->
                        SwingUtilities.invokeLater {
                            messageList.appendToAgentTurn(status)
                        }
                    },
                    onStreamStart = {
                        SwingUtilities.invokeLater {
                            messageList.beginStreamingBody()
                        }
                    },
                    onStreamDelta = { delta ->
                        SwingUtilities.invokeLater {
                            messageList.appendStreamingDelta(delta)
                        }
                    },
                    cancelled = agentCancelled,
                    presetOverride = preset
                )
                val apiMessages = prepareApiMessages(provider, conv)
                val apiSizeBefore = apiMessages.size
                val result = runBlocking {
                    session.run(model, apiMessages)
                }
                for (i in apiSizeBefore until apiMessages.size) {
                    conv.addMessage(apiMessages[i])
                }
                SwingUtilities.invokeLater {
                    lastAssistantAnswer = result.finalContent
                    conv.usage = conv.usage.plus(result.totalUsage)
                    messageList.completeAgentTurn(result.finalContent, result.toolCallCount)
                    localLlmWorkbench?.refreshStatus()
                    usageMeter.updateLastTurn(result.totalUsage)
                    refreshUsageMeters()
                    refreshConversationUi()
                    creditsInfo?.let { info ->
                        creditsInfo = info.copy(
                            balance = (info.balance - result.totalUsage.creditsUsed).coerceAtLeast(0.0)
                        )
                    }
                }
            } catch (e: Exception) {
                val detail = (e as? GabClient.GabApiException)?.body?.take(400)
                sessionLog.error("request failed: ${e.message}${detail?.let { " — $it" }.orEmpty()}")
                SwingUtilities.invokeLater {
                    messageList.completeAgentTurn("Error: ${e.message}")
                }
            } finally {
                SwingUtilities.invokeLater {
                    sendButton.isEnabled = true
                    stopButton.isEnabled = false
                }
            }
        }.start()
    }

    private fun buildMessagePayload(userText: String): String {
        val attachments = conversationManager.getAttachments()
        if (attachments.isEmpty()) return userText

        val contextBlock = attachments.joinToString("\n\n") { att ->
            buildString {
                append("[Attached: ${att.path ?: att.displayName}]")
                att.content?.let { append("\n```\n").append(it).append("\n```") }
            }
        }
        return "$userText\n\n--- Workspace context ---\n$contextBlock"
    }

    private fun buildCompactionConfig(): ContextCompactor.Config {
        val contextLimit = localLlmWorkbench?.contextSize ?: settings.localLlmContextTokens
        return ContextCompactor.Config(
            contextTokenLimit = contextLimit,
            keepRecentTurns = settings.localLlmKeepRecentTurns,
            enabled = settings.localLlmContextCompaction
        )
    }

    private fun estimatedContextTokens(conv: Conversation): Int {
        val attachments = TokenEstimator.estimateAttachments(conv.attachments)
        if (settings.activeProvider != ModelProvider.LOCAL_LLM || !settings.localLlmContextCompaction) {
            return TokenEstimator.estimateMessages(conv.messages) + attachments
        }
        val compacted = ContextCompactor.compact(
            messages = conv.messages,
            existingSummary = conv.compactSummary,
            compactedMessageCount = conv.compactedMessageCount,
            config = buildCompactionConfig()
        )
        return TokenEstimator.estimateMessages(compacted.apiMessages) + attachments
    }

    private fun prepareApiMessages(provider: ModelProvider, conv: Conversation): MutableList<ChatMessage> {
        if (provider != ModelProvider.LOCAL_LLM) {
            return conv.messages.toMutableList()
        }
        val compacted = ContextCompactor.compact(
            messages = conv.messages,
            existingSummary = conv.compactSummary,
            compactedMessageCount = conv.compactedMessageCount,
            config = buildCompactionConfig()
        )
        conv.compactSummary = compacted.compactSummary
        conv.compactedMessageCount = compacted.compactedMessageCount
        if (compacted.didCompact) {
            sessionLog.system("context compact: ${compacted.stats}")
        }
        return compacted.apiMessages.toMutableList()
    }

    fun compactConversationNow() {
        val conv = conversationManager.getActive()
        if (conv.messages.count { it.role == ChatMessage.Role.user } < 2) {
            messageList.addMessage(
                ChatMessageListPanel.MessageRole.SYSTEM,
                "Need at least two user turns before compacting context."
            )
            return
        }
        val result = ContextCompactor.compact(
            messages = conv.messages,
            existingSummary = conv.compactSummary,
            compactedMessageCount = conv.compactedMessageCount,
            config = buildCompactionConfig(),
            force = true
        )
        conv.compactSummary = result.compactSummary
        conv.compactedMessageCount = result.compactedMessageCount
        sessionLog.system("manual compact: ${result.stats}")
        messageList.addMessage(
            ChatMessageListPanel.MessageRole.SYSTEM,
            "Context compacted for the next Local LLM request. ${result.stats}"
        )
        refreshUsageMeters()
    }

    private fun onStop() {
        agentCancelled.set(true)
        sessionLog.system("stop requested")
        stopButton.isEnabled = false
        sendButton.isEnabled = true
        messageList.appendToAgentTurn("Stopping agent…")
    }

    private fun refreshModelsFromApi() {
        val provider = settings.activeProvider
        if (!settings.hasApiKey(provider)) {
            messageList.addMessage(
                ChatMessageListPanel.MessageRole.SYSTEM,
                "Cannot refresh models: ${provider.displayName} is not configured."
            )
            return
        }
        sendButton.isEnabled = false
        sessionLog.system("refreshing models from ${provider.displayName}")
        Thread {
            try {
                val client = settings.createClient(provider, sessionLog)
                val models = runBlocking { client.listModels() }
                val credits = runBlocking { client.getCredits() }
                SwingUtilities.invokeLater {
                    creditsInfo = credits
                    if (models.isNotEmpty()) applyModelsToCombo(models, provider)
                    refreshUsageMeters()
                    val filtered = ModelCatalog.filterForProvider(models, provider)
                    val creditsText = credits?.displayBalance() ?: "token usage only"
                    messageList.addMessage(
                        ChatMessageListPanel.MessageRole.SYSTEM,
                        "${provider.displayName}: loaded ${filtered.size} models. " +
                            "Default: ${ModelCatalog.defaultModelId(provider)}. Balance: $creditsText"
                    )
                }
            } catch (e: Exception) {
                sessionLog.error("model refresh failed: ${e.message}")
                SwingUtilities.invokeLater {
                    messageList.addMessage(ChatMessageListPanel.MessageRole.SYSTEM, "Failed to load models: ${e.message}")
                }
            } finally {
                SwingUtilities.invokeLater { sendButton.isEnabled = true }
            }
        }.start()
    }

    fun appendStreamingDelta(delta: String) {
        SwingUtilities.invokeLater {
            messageList.appendStreamingDelta(delta)
        }
    }

    fun finishStreaming() {
        SwingUtilities.invokeLater {
            stopButton.isEnabled = false
            sendButton.isEnabled = true
        }
    }
}
