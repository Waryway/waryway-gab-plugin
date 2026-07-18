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
import com.waryway.gab.chat.ConversationHistorySync
import com.waryway.gab.chat.ConversationManager
import com.waryway.gab.chat.LocalLlmAgentSession
import com.waryway.gab.chat.TokenEstimator
import com.waryway.gab.diagnostics.FailPackageExporter
import com.waryway.gab.diagnostics.FailPackageMeta
import com.waryway.gab.diagnostics.SessionLog
import com.waryway.gab.model.Conversation
import com.waryway.gab.client.AgentClient
import com.waryway.gab.client.GabClient
import com.waryway.gab.client.GrokBuildAuth
import com.waryway.gab.client.GrokBuildAuthRecovery
import com.waryway.gab.client.LocalLLMService
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
import java.awt.datatransfer.StringSelection
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.swing.*
import javax.swing.text.DefaultEditorKit
import com.intellij.openapi.ide.CopyPasteManager

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
    private val stopButton = JButton("Stop", AllIcons.Actions.Suspend).apply {
        isEnabled = false
        toolTipText = "Stop the current agent / stream (also aborts blocked SSE reads)"
        foreground = JBColor(Color(0xB00020), Color(0xFF6B6B))
    }
    private var localLlmWorkbench: LocalLlmWorkbenchPanel? = null
    private var lastUserQuestion: String = ""
    private var lastAssistantAnswer: String = ""
    private var lastSendPathLabel: String = ""
    private var lastSendModel: String = ""
    private var lastSendSkillId: String = ""

    private val activityLogPanel = ActivityLogPanel()
    private val messageList = ChatMessageListPanel()
    /** ERR lines mirrored into the agent bubble this turn (capped to avoid dump loops). */
    private val errMirrorCount = AtomicInteger(0)
    private val sessionLog: SessionLog = SessionLog(onLine = { line: String ->
        activityLogPanel.appendLine(line)
        // Mirror at most a few errors into the chat bubble; full log is in Activity / fail package.
        if (line.contains("] ERR  ") && errMirrorCount.incrementAndGet() <= MAX_ERR_MIRROR_PER_TURN) {
            SwingUtilities.invokeLater {
                messageList.appendToAgentTurn("⚠ $line")
            }
        } else if (line.contains("] ERR  ") && errMirrorCount.get() == MAX_ERR_MIRROR_PER_TURN + 1) {
            SwingUtilities.invokeLater {
                messageList.appendToAgentTurn(
                    "⚠ (further errors only in Activity log — use Export fail)"
                )
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
    /**
     * Send-adjacent chip:
     * - Local LLM: Agent · dry-run / Agent · APPLY / Chat (never for other providers)
     * - Grok Build: light session status; click re-reads auth.json
     */
    private val sendPathBadge = JBLabel("").apply {
        font = font.deriveFont(java.awt.Font.BOLD, 11f)
        foreground = JBColor(0x69C9FF, 0x69C9FF)
        border = JBUI.Borders.emptyRight(6)
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                if (settings.activeProvider != ModelProvider.GROK_BUILD) return
                val summary = settings.refreshGrokBuildSession()
                refreshSendPathBadge()
                sessionLog.system("grok build session refresh: $summary")
                messageList.addMessage(ChatMessageListPanel.MessageRole.SYSTEM, summary)
            }
        })
    }

    private val agentCancelled = AtomicBoolean(false)
    /** Prevents double Enter / double-click from starting two concurrent turns. */
    private val sendInFlight = AtomicBoolean(false)
    /** Active Local LLM `/api/agent` session (null for chat / cloud AgentSession path). */
    private val activeLocalAgent = AtomicReference<LocalLlmAgentSession?>(null)
    /** Active OpenAI-compat client (for [GabClient.abortActiveStream] on Stop). */
    private val activeGabClient = AtomicReference<GabClient?>(null)
    /** Background send/agent worker thread (interrupted on Stop as a last resort). */
    private val activeWorkerThread = AtomicReference<Thread?>(null)

    /**
     * Batches high-frequency stream deltas before they hit [messageList].
     * Drained by a single repeating EDT [javax.swing.Timer] (~40ms) or on size force.
     */
    private val streamUiCoalescer = StreamUiCoalescer()
    /** EDT-only; created on stream start, stopped on complete / stop / finally. */
    private var streamFlushTimer: Timer? = null

    private val coachingPanel = createCoachingPanel()

    init {
        border = JBUI.Borders.empty(8)
        // Durable session log on disk so users can hand a path to another agent.
        val logFile = sessionLog.attachLogFile(FailPackageExporter.newSessionLogFile(project.basePath))
        activityLogPanel.setLogPathDisplay(logFile.toString())
        sessionLog.system("session log file: $logFile")
        activityLogPanel.onClearRequested = { sessionLog.clear() }
        activityLogPanel.onExportFailRequested = { exportFailPackage("manual_export") }
        activityLogPanel.onCopyLogPathRequested = {
            val path = sessionLog.logFilePath()?.toString()
                ?: FailPackageExporter.logsRoot(project.basePath).toString()
            CopyPasteManager.getInstance().setContents(StringSelection(path))
            sessionLog.system("copied log path: $path")
            messageList.addMessage(
                ChatMessageListPanel.MessageRole.SYSTEM,
                "Log path copied:\n$path"
            )
        }
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
                add(JBLabel(AllIcons.Actions.Forward).apply {
                    toolTipText = "Provider (Local LLM, Grok, or Gab AI)"
                })
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
                add(sendPathBadge)
                // Duplicate Stop next to Send so it is always reachable during a dump loop.
                val stopNearSend = JButton("Stop", AllIcons.Actions.Suspend).apply {
                    toolTipText = "Stop the current agent / stream"
                    foreground = JBColor(Color(0xB00020), Color(0xFF6B6B))
                    addActionListener { onStop() }
                }
                // Keep enabled state in sync with the header stop button.
                stopButton.addChangeListener {
                    stopNearSend.isEnabled = stopButton.isEnabled
                }
                stopNearSend.isEnabled = stopButton.isEnabled
                add(stopNearSend)
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
                },
                onModeChanged = { refreshSendPathBadge() }
            ).also { localLlmWorkbench = it }
        } else {
            localLlmWorkbench = null
            null
        }
        refreshSendPathBadge()

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
            val hint = when (provider) {
                ModelProvider.LOCAL_LLM -> LocalLlmSendUx.blankBaseUrlMessage()
                ModelProvider.GROK_BUILD -> grokBuildPreSendCoaching()
                else -> "No ${provider.displayName} API key configured. Add one in Plugin Settings."
            }
            messageList.addMessage(ChatMessageListPanel.MessageRole.SYSTEM, hint)
        } else if (provider == ModelProvider.GROK_BUILD) {
            messageList.addMessage(
                ChatMessageListPanel.MessageRole.SYSTEM,
                settings.grokBuildSessionSummary()
            )
        }
    }

    /**
     * Update Send-adjacent badge:
     * - Local LLM only: Agent · dry-run / APPLY / Chat (from [LocalLlmSendUx])
     * - Grok Build: light session status (click re-reads auth.json via [WarywayGabSettings.refreshGrokBuildSession])
     * - Other providers: hidden (no agent-mode claims)
     */
    private fun refreshSendPathBadge() {
        when (settings.activeProvider) {
            ModelProvider.LOCAL_LLM -> {
                val agent = isLocalLlmAgentMode()
                val dry = resolveAgentDryRun()
                sendPathBadge.isVisible = true
                sendPathBadge.cursor = Cursor.getDefaultCursor()
                sendPathBadge.text = LocalLlmSendUx.sendPathLabel(agent, dry)
                sendPathBadge.toolTipText = LocalLlmSendUx.sendPathToolTip(agent, dry)
                sendPathBadge.foreground = when {
                    !agent -> JBColor.GRAY
                    dry -> JBColor(0x69C9FF, 0x69C9FF)
                    else -> JBColor(0xF59E0B, 0xF59E0B)
                }
            }
            ModelProvider.GROK_BUILD -> {
                val session = GrokBuildAuth.readSession()
                val state = GrokBuildAuthRecovery.classifySession(session)
                sendPathBadge.isVisible = true
                sendPathBadge.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                when (state) {
                    GrokBuildAuthRecovery.SessionState.USABLE -> {
                        val who = session?.email?.takeIf { it.isNotBlank() } ?: "signed in"
                        sendPathBadge.text = "Grok · $who"
                        sendPathBadge.toolTipText =
                            "${settings.grokBuildSessionSummary()} — click to re-read session after `grok login`"
                        sendPathBadge.foreground = JBColor(0x34D399, 0x34D399)
                    }
                    GrokBuildAuthRecovery.SessionState.EXPIRED -> {
                        sendPathBadge.text = "Session expired"
                        sendPathBadge.toolTipText =
                            GrokBuildSendUx.coachingExpiredSession(email = session?.email) +
                                " — click to re-check after login"
                        sendPathBadge.foreground = JBColor(0xF59E0B, 0xF59E0B)
                    }
                    GrokBuildAuthRecovery.SessionState.MISSING -> {
                        sendPathBadge.text = "No session"
                        sendPathBadge.toolTipText =
                            GrokBuildSendUx.coachingMissingSession() + " — click to re-check after login"
                        sendPathBadge.foreground = JBColor(0xB00020, 0xFF6B6B)
                    }
                }
            }
            else -> {
                sendPathBadge.text = ""
                sendPathBadge.toolTipText = null
                sendPathBadge.isVisible = false
                sendPathBadge.cursor = Cursor.getDefaultCursor()
            }
        }
    }

    /**
     * Pre-send / provider-change coaching for Grok Build when no usable key/session.
     * Uses pure [GrokBuildSendUx] / [GrokBuildAuthRecovery] missing vs expired copy.
     */
    private fun grokBuildPreSendCoaching(): String {
        val session = GrokBuildAuth.readSession()
        return when (GrokBuildAuthRecovery.classifySession(session)) {
            GrokBuildAuthRecovery.SessionState.EXPIRED ->
                GrokBuildSendUx.coachingExpiredSession(email = session?.email)
            GrokBuildAuthRecovery.SessionState.MISSING,
            GrokBuildAuthRecovery.SessionState.USABLE ->
                // USABLE here only if PasswordSafe override is blank and hasApiKey was false —
                // still coach login (summary path uses same catalog).
                GrokBuildSendUx.coachingMissingSession()
        }
    }

    /**
     * Blocking health preflight for Local LLM. Returns offline message if down; null if OK.
     * Call from a background thread (not EDT).
     */
    private fun localLlmOfflineHintOrNull(): String? {
        val root = LocalLlmSendUx.normalizeRootUrl(settings.localLlmBaseUrl)
        return if (LocalLLMService(settings, sessionLog).healthOk()) {
            null
        } else {
            LocalLlmSendUx.offlineMessage(root)
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
        val path = toProjectRelativePath(file)
        val attachment = ContextAttachment(
            type = ContextAttachment.Type.FILE,
            path = path,
            displayName = file.name,
            content = readFilePreview(file)
        )
        conversationManager.addAttachment(attachment)
        refreshAttachmentChips()
        refreshUsageMeters()
    }

    /**
     * Project-relative when under [Project.getBasePath] / [Project.getBaseDir]; absolute otherwise.
     * Never empty — see [AttachmentPayload.resolveAttachmentPath] (separators → `/`).
     */
    private fun toProjectRelativePath(file: VirtualFile): String {
        val relativeFromVfs = project.baseDir?.let { baseDir ->
            VfsUtil.getRelativePath(file, baseDir)
        }
        return AttachmentPayload.resolveAttachmentPath(
            absolutePath = file.path,
            projectBasePath = project.basePath,
            relativeFromVfs = relativeFromVfs,
            fallbackName = file.name
        )
    }

    /**
     * Text preview or null (binary / unreadable / empty). Does not throw.
     * Payload path injects [AttachmentPayload.CONTENT_UNAVAILABLE] when null.
     */
    private fun readFilePreview(
        file: VirtualFile,
        maxChars: Int = AttachmentPayload.DEFAULT_PREVIEW_MAX_CHARS
    ): String? {
        return try {
            val bytes = file.contentsToByteArray()
            AttachmentPayload.previewTextFromBytes(bytes, file.charset, maxChars)
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
        val entries = ConversationHistorySync.toDisplayEntries(conv.messages).map { entry ->
            val role = when (entry.role) {
                ConversationHistorySync.DisplayRole.USER -> ChatMessageListPanel.MessageRole.USER
                ConversationHistorySync.DisplayRole.ASSISTANT -> ChatMessageListPanel.MessageRole.ASSISTANT
            }
            role to entry.text
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
            text = coachingPasteLabel(settings.activeProvider)
        }
        providerSelector.addActionListener {
            val provider = ModelProvider.selectable[providerSelector.selectedIndex]
            stepsPane.updateCoachingSteps(provider)
            pasteLabel.text = coachingPasteLabel(provider)
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
                when {
                    provider == ModelProvider.LOCAL_LLM -> {
                        settings.activeProvider = provider
                        rebuildUI()
                    }
                    provider == ModelProvider.GROK_BUILD && settings.hasApiKey(ModelProvider.GROK_BUILD) -> {
                        settings.activeProvider = provider
                        rebuildUI()
                    }
                    key.isNotBlank() -> {
                        settings.activeProvider = provider
                        if (provider != ModelProvider.GROK_BUILD) {
                            settings.setApiKey(key, provider)
                        } else if (key.isNotBlank()) {
                            // Optional manual session token override for Grok Build.
                            settings.setApiKey(key, provider)
                        }
                        rebuildUI()
                    }
                    else -> {
                        val msg = if (provider == ModelProvider.GROK_BUILD) {
                            // Missing vs expired from recovery helper; override paste still allowed.
                            settings.grokBuildSessionSummary() +
                                "\n\nOr paste a session token override below."
                        } else {
                            "Please paste a key first."
                        }
                        JOptionPane.showMessageDialog(this, msg)
                    }
                }
            }
            add(pasteBtn)

            val grokBuildBtn = JButton("Use Grok Build", AllIcons.RunConfigurations.TestState.Run)
            grokBuildBtn.addActionListener {
                if (!settings.hasApiKey(ModelProvider.GROK_BUILD)) {
                    // Summary already has missing vs expired + grok login + auth path.
                    JOptionPane.showMessageDialog(
                        this,
                        settings.grokBuildSessionSummary()
                    )
                    return@addActionListener
                }
                settings.activeProvider = ModelProvider.GROK_BUILD
                rebuildUI()
            }
            add(grokBuildBtn)

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
                if (key.isBlank() && provider != ModelProvider.GROK_BUILD && provider != ModelProvider.LOCAL_LLM) {
                    JOptionPane.showMessageDialog(this, "Paste a key first.")
                    return@addActionListener
                }
                if (provider == ModelProvider.GROK_BUILD && !settings.hasApiKey(ModelProvider.GROK_BUILD) && key.isBlank()) {
                    JOptionPane.showMessageDialog(
                        this,
                        settings.grokBuildSessionSummary()
                    )
                    return@addActionListener
                }
                testKey(key, provider) { success, msg ->
                    SwingUtilities.invokeLater {
                        JOptionPane.showMessageDialog(this, if (success) "Success: $msg" else "Failed: $msg")
                        if (success) {
                            settings.activeProvider = provider
                            if (key.isNotBlank() && provider != ModelProvider.LOCAL_LLM) {
                                settings.setApiKey(key, provider)
                            }
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
            ModelProvider.GROK_BUILD -> listOf(
                "1. Install/use Grok Build CLI and run <code>grok login</code> (browser OAuth)",
                "2. Session is stored at <code>~/.grok/auth.json</code> — same login as GoLand AI Chat Grok Build",
                "3. Click <b>Use Grok Build</b> below — no console.x.ai API key or team credits required",
                "4. Status: ${settings.grokBuildSessionSummary().replace("<", "&lt;")}"
            )
            ModelProvider.GROK -> listOf(
                "1. Go to <a href='https://console.x.ai'>https://console.x.ai</a> and sign in (xAI Grok API)",
                "2. Open <b>API Keys</b> and create a new key (requires team credits/licenses)",
                "3. Copy the key (shown only once)",
                "4. Paste it below — this is <b>prepaid API</b> quota, not Grok Build CLI quota"
            )
            ModelProvider.GAB_AI -> listOf(
                "1. Go to <a href='https://gab.ai'>https://gab.ai</a> and sign in",
                "2. Upgrade to a <b>Plus</b> plan (required for API access)",
                "3. Open <b>Settings</b> → <b>API Settings</b>",
                "4. Generate a new API key and paste it below — separate from Grok credentials"
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

    private fun coachingPasteLabel(provider: ModelProvider): String = when (provider) {
        ModelProvider.GROK_BUILD ->
            "Grok Build uses `grok login` (optional: paste session token override):"
        ModelProvider.LOCAL_LLM ->
            "Local LLM needs no key — click Use Local LLM:"
        else ->
            "Paste your ${provider.displayName} API key here:"
    }

    /**
     * Stores the key under [provider] (Grok keys never go under Gab AI) and probes via
     * [GabClient.listModels] + [ModelCatalog.filterForProvider].
     * Grok Build prefers ~/.grok/auth.json and does not require a pasted key.
     */
    private fun testKey(key: String, provider: ModelProvider, callback: (Boolean, String) -> Unit) {
        Thread {
            try {
                when (provider) {
                    ModelProvider.LOCAL_LLM -> settings.activeProvider = ModelProvider.LOCAL_LLM
                    ModelProvider.GROK_BUILD -> {
                        if (key.isNotBlank()) settings.setApiKey(key, provider)
                        settings.activeProvider = ModelProvider.GROK_BUILD
                    }
                    else -> settings.setApiKey(key, provider)
                }
                val client = settings.createClient(provider, sessionLog)
                val models = runBlocking { client.listModels() }
                val filtered = ModelCatalog.filterForProvider(models, provider)
                val extra = if (provider == ModelProvider.GROK_BUILD) {
                    " ${settings.grokBuildSessionSummary()}"
                } else {
                    ""
                }
                callback(true, "${provider.displayName}: ${filtered.size} models available.$extra")
            } catch (e: Exception) {
                callback(false, "${provider.displayName}: ${e.message ?: "Unknown error"}")
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
        // Atomic guard: Enter can fire twice before sendButton disables; do not start two turns.
        if (!sendInFlight.compareAndSet(false, true)) return

        val rawText = inputArea.text.trimEnd()
        if (rawText.isBlank()) {
            sendInFlight.set(false)
            return
        }

        val normalizedText = InputNormalizer.normalize(rawText)
        if (normalizedText.isEmpty()) {
            sendInFlight.set(false)
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
        // Capture attach paths for Local LLM agent goal BEFORE chips/attachments are cleared.
        // Must run on this thread while getAttachments() still holds the Send-time set.
        lastAttachmentPathsForAgent =
            LocalLlmAgentSession.attachmentPathsForAgent(conversationManager.getAttachments())
        conversationManager.clearAttachments()
        refreshAttachmentChips()
        sendButton.isEnabled = false
        stopButton.isEnabled = true
        // Title may change on first user message — refresh tabs without reloading the chat list.
        refreshConversationUi()

        val provider = settings.activeProvider
        if (!settings.hasApiKey(provider)) {
            // LOCAL_LLM: hasApiKey is URL-blank only — not a live health check.
            // GROK_BUILD: pure helper distinguishes missing vs expired (same catalog as settings).
            val hint = when (provider) {
                ModelProvider.LOCAL_LLM -> LocalLlmSendUx.blankBaseUrlMessage()
                ModelProvider.GROK_BUILD -> grokBuildPreSendCoaching()
                else -> "No ${provider.displayName} API key configured."
            }
            messageList.addMessage(ChatMessageListPanel.MessageRole.SYSTEM, hint)
            if (provider == ModelProvider.GROK_BUILD) {
                refreshSendPathBadge()
            }
            sendButton.isEnabled = true
            stopButton.isEnabled = false
            sendInFlight.set(false)
            return
        }

        val model = (modelCombo.selectedItem ?: ModelCatalog.defaultModelId(provider)).toString()
        agentCancelled.set(false)
        activeLocalAgent.set(null)
        messageList.beginAgentTurn()
        // LOCAL_LLM agent mode → server /api/agent (not AgentSession OpenAI tool loop).
        // Cloud providers and Local LLM chat-only keep AgentSession + GabClient unchanged.
        val useLocalAgent = provider == ModelProvider.LOCAL_LLM && isLocalLlmAgentMode()
        val pathLabel = if (provider == ModelProvider.LOCAL_LLM) {
            LocalLlmSendUx.sendPathLabel(useLocalAgent, if (useLocalAgent) resolveAgentDryRun() else true)
        } else {
            provider.displayName
        }
        lastSendModel = model
        lastSendSkillId = skill?.id ?: "none"
        lastSendPathLabel = pathLabel
        errMirrorCount.set(0)
        sessionLog.system("send: model=$model skill=${skill?.id ?: "none"} path=$pathLabel")
        refreshSendPathBadge()

        if (useLocalAgent) {
            runLocalLlmAgent(conv, payload, model, skill)
            return
        }

        val worker = Thread {
            try {
                if (provider == ModelProvider.LOCAL_LLM) {
                    SwingUtilities.invokeLater {
                        messageList.appendToAgentTurn("▸ Checking LocalLLM…")
                    }
                    val offline = localLlmOfflineHintOrNull()
                    if (offline != null) {
                        sessionLog.error(offline)
                        val path = writeFailPackageInternal("local_llm_offline", offline)
                        SwingUtilities.invokeLater {
                            messageList.completeAgentTurn(offline + failPathNote(path))
                        }
                        return@Thread
                    }
                    SwingUtilities.invokeLater {
                        messageList.appendToAgentTurn("▸ Chat path (/v1/chat/completions)…")
                    }
                }
                val client = settings.createClient(provider, sessionLog)
                activeGabClient.set(client)
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
                            // Status lines should show promptly; flush any pending stream text first.
                            flushStreamCoalescerToUi()
                            messageList.appendToAgentTurn(status)
                        }
                    },
                    onStreamStart = {
                        // Clear coalescer on the producer thread *before* scheduling EDT work,
                        // so an early delta cannot land in the buffer and then be wiped by clear().
                        streamUiCoalescer.clear()
                        SwingUtilities.invokeLater {
                            // New completion iteration: reset live body for this completion only.
                            messageList.beginStreamingBody()
                            ensureStreamFlushTimerOnEdt()
                        }
                    },
                    onStreamDelta = { delta ->
                        // Background thread: batch only; timer (or size force) paints on EDT.
                        val forceFlush = streamUiCoalescer.offer(delta)
                        if (forceFlush) {
                            SwingUtilities.invokeLater { flushStreamCoalescerToUi() }
                        }
                    },
                    cancelled = agentCancelled,
                    presetOverride = preset
                )
                // Snapshot *before* AgentSession mutates the list (prepends system, appends turns).
                // Index-based suffix copy re-duplicates the user message after a system prepend.
                val apiMessages = prepareApiMessages(provider, conv)
                val beforeSnapshot = apiMessages.toList()
                val result = runBlocking {
                    session.run(model, apiMessages)
                }
                val toPersist = ConversationHistorySync.messagesToPersist(beforeSnapshot, apiMessages)
                for (msg in toPersist) {
                    conv.addMessage(msg)
                }
                sessionLog.system(
                    "history sync: persisted ${toPersist.size} message(s) " +
                        "(roles=${toPersist.joinToString { it.role.name }})"
                )
                // Auto-export when the agent hit a hard stop / empty / length style terminal.
                val content = result.finalContent
                if (agentCancelled.get() || content.contains("Stopped by user") ||
                    content.contains("stopped after") || content.contains("empty or incomplete")
                ) {
                    val trigger = when {
                        agentCancelled.get() || content.contains("Stopped by user") -> "user_stop"
                        content.contains("stopped after") -> "max_iterations"
                        else -> "agent_terminal"
                    }
                    writeFailPackageInternal(trigger, content.take(200))
                }
                SwingUtilities.invokeLater {
                    stopStreamFlushTimerOnEdt(flush = true)
                    lastAssistantAnswer = result.finalContent
                    conv.usage = conv.usage.plus(result.totalUsage)
                    messageList.completeAgentTurn(result.finalContent, result.toolCallCount)
                    localLlmWorkbench?.refreshStatus()
                    usageMeter.updateLastTurn(result.totalUsage)
                    refreshUsageMeters()
                    // Tab titles only — do not reload message list (would wipe live bubbles).
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
                val root = LocalLlmSendUx.normalizeRootUrl(settings.localLlmBaseUrl)
                // GROK_BUILD → GrokBuildSendUx.formatFailure (auth/proxy/network recovery);
                // LOCAL_LLM → LocalLlmSendUx; other cloud → generic Error (optional body).
                // No bare "Error: ${e.message}" for GROK_BUILD when formatter exists.
                val errorText = GrokBuildChatFailureUx.formatChatFailure(
                    error = e,
                    provider = provider,
                    rootUrl = root
                )
                val path = writeFailPackageInternal("request_failed", e.message ?: "error")
                SwingUtilities.invokeLater {
                    stopStreamFlushTimerOnEdt(flush = true)
                    messageList.completeAgentTurn(errorText + failPathNote(path))
                    if (provider == ModelProvider.GROK_BUILD) {
                        refreshSendPathBadge()
                    }
                }
            } finally {
                activeGabClient.set(null)
                activeWorkerThread.compareAndSet(Thread.currentThread(), null)
                SwingUtilities.invokeLater {
                    stopStreamFlushTimerOnEdt(flush = true)
                    sendButton.isEnabled = true
                    stopButton.isEnabled = false
                    sendInFlight.set(false)
                }
            }
        }
        activeWorkerThread.set(worker)
        worker.start()
    }

    /** Workbench toggle if present; else settings default. */
    private fun isLocalLlmAgentMode(): Boolean =
        localLlmWorkbench?.isAgentMode() ?: settings.localLlmAgentMode

    /**
     * Dry-run for agent start. Default true (safe). Apply only when workbench Apply is checked
     * (or settings dry-run is false). Never silent always-apply.
     */
    private fun resolveAgentDryRun(): Boolean {
        val wb = localLlmWorkbench
        if (wb != null) return wb.isDryRun()
        return settings.localLlmAgentDryRun
    }

    /**
     * Local LLM agent path: [AgentClient] start + poll. Surfaces state/plan/events/finalAnswer
     * and dryRun/repoRoot badges. Does not use [AgentSession].
     *
     * Goal = chat payload (path + content or read_file instruction) + path list from
     * [lastAttachmentPathsForAgent] captured at Send before clearAttachments.
     */
    private fun runLocalLlmAgent(
        conv: Conversation,
        userPayload: String,
        model: String,
        skill: SkillRegistry.GuidedSkill?
    ) {
        // Snapshot field once so background thread cannot race a later Send.
        val pathsForGoal = lastAttachmentPathsForAgent.toList()
        val goal = LocalLlmAgentSession.buildGoalWithAttachments(userPayload, pathsForGoal)
        val dryRun = resolveAgentDryRun()
        val preset = settings.localLlmAgentPreset.ifBlank { "agent-plan" }
        val maxSteps = settings.localLlmAgentMaxSteps.takeIf { it > 0 }

        val pathLabel = LocalLlmSendUx.sendPathLabel(agentMode = true, dryRun = dryRun)
        sessionLog.system(
            "local agent: dryRun=$dryRun preset=$preset model=$model " +
                "skill=${skill?.id ?: "none"} paths=${pathsForGoal.size} " +
                "pathList=${pathsForGoal.joinToString(limit = 8)} sendPath=$pathLabel"
        )
        localLlmWorkbench?.updateRunMeta(dryRun, null)
        refreshSendPathBadge()
        SwingUtilities.invokeLater {
            messageList.appendToAgentTurn(
                if (dryRun) "▸ Starting LocalLLM agent (dry-run) · /api/agent…"
                else "▸ Starting LocalLLM agent (APPLY) · /api/agent…"
            )
        }

        errMirrorCount.set(0)
        val worker = Thread {
            try {
                SwingUtilities.invokeLater {
                    messageList.appendToAgentTurn("▸ Checking LocalLLM…")
                }
                val offline = localLlmOfflineHintOrNull()
                if (offline != null) {
                    sessionLog.error(offline)
                    val path = writeFailPackageInternal("local_llm_offline", offline)
                    SwingUtilities.invokeLater {
                        messageList.completeAgentTurn(offline + failPathNote(path))
                    }
                    return@Thread
                }

                val agentClient = AgentClient(settings = settings, sessionLog = sessionLog)
                val session = LocalLlmAgentSession(
                    client = agentClient,
                    sessionLog = sessionLog,
                    onStatus = { status ->
                        SwingUtilities.invokeLater { messageList.appendToAgentTurn(status) }
                    },
                    onLogLine = { line ->
                        // Always keep full detail in Activity log; chat is soft-capped.
                        sessionLog.system(line)
                        SwingUtilities.invokeLater { messageList.appendToAgentTurn(line) }
                    },
                    cancelled = agentCancelled
                )
                activeLocalAgent.set(session)
                val result = session.run(
                    goal = goal,
                    dryRun = dryRun,
                    preset = preset,
                    model = model,
                    maxSteps = maxSteps
                )
                val assistantMsg = ChatMessage(ChatMessage.Role.assistant, result.finalContent)
                conv.addMessage(assistantMsg)
                if (agentCancelled.get() ||
                    result.run.state == "failed" ||
                    result.run.state == "cancelled" ||
                    result.finalContent.contains("Stopped by user")
                ) {
                    val trigger = when {
                        agentCancelled.get() || result.run.state == "cancelled" -> "user_stop"
                        result.run.state == "failed" -> "agent_failed"
                        else -> "agent_terminal"
                    }
                    writeFailPackageInternal(trigger, result.finalContent.take(200))
                }
                SwingUtilities.invokeLater {
                    lastAssistantAnswer = result.finalContent
                    messageList.completeAgentTurn(result.finalContent, result.toolCallCount)
                    localLlmWorkbench?.updateRunMeta(result.run.dryRun, result.run.repoRoot)
                    localLlmWorkbench?.refreshStatus()
                    refreshUsageMeters()
                    refreshConversationUi()
                }
            } catch (e: Exception) {
                val detail = (e as? AgentClient.AgentException)?.body?.take(400)
                sessionLog.error("agent run failed: ${e.message}${detail?.let { " — $it" }.orEmpty()}")
                val root = LocalLlmSendUx.normalizeRootUrl(settings.localLlmBaseUrl)
                val userMsg = LocalLlmSendUx.formatFailure(e, agentMode = true, rootUrl = root)
                val path = writeFailPackageInternal("agent_run_failed", e.message ?: "error")
                SwingUtilities.invokeLater {
                    messageList.completeAgentTurn(userMsg + failPathNote(path))
                }
            } finally {
                activeLocalAgent.set(null)
                activeWorkerThread.compareAndSet(Thread.currentThread(), null)
                SwingUtilities.invokeLater {
                    sendButton.isEnabled = true
                    stopButton.isEnabled = false
                    sendInFlight.set(false)
                }
            }
        }
        activeWorkerThread.set(worker)
        worker.start()
    }

    /** Paths captured at send time for agent goal context (attachments cleared right after). */
    private var lastAttachmentPathsForAgent: List<String> = emptyList()

    private fun buildMessagePayload(userText: String): String {
        val attachments = conversationManager.getAttachments()
        val snippets = attachments.map { att ->
            // Align label with agent path capture: path → displayName → chipLabel (never blank).
            val pathOrName = att.path?.trim()?.takeIf { it.isNotEmpty() }
                ?: att.displayName.trim().takeIf { it.isNotEmpty() }
                ?: att.chipLabel()
            pathOrName to att.content
        }
        return AttachmentPayload.buildMessagePayload(userText, snippets)
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
        // Local LLM agent: also POST /api/agent/runs/{id}/cancel when a run is active.
        activeLocalAgent.get()?.cancelActiveRun()
        // Unblock SSE readLine that is waiting on the network.
        activeGabClient.get()?.abortActiveStream()
        // Last resort if a tool or poll is stuck hard.
        activeWorkerThread.get()?.interrupt()
        sessionLog.system("stop requested")
        // Capture fail package while logs still include this turn.
        val path = writeFailPackageInternal("user_stop", "Stopped by user")
        stopButton.isEnabled = false
        sendButton.isEnabled = true
        // Allow a new send after stop; the in-flight thread clears sendInFlight in finally too.
        sendInFlight.set(false)
        // Flush any batched stream text so partial reply is visible, then cancel timer.
        stopStreamFlushTimerOnEdt(flush = true)
        messageList.appendToAgentTurn("Stopping agent…" + failPathNote(path))
    }

    /** Manual export from Activity log (or any time). Surfaces path in chat + clipboard. */
    private fun exportFailPackage(trigger: String) {
        val path = writeFailPackageInternal(trigger, "manual export")
        val text = path?.toString() ?: "(export failed — see Activity log)"
        if (path != null) {
            CopyPasteManager.getInstance().setContents(StringSelection(text))
        }
        messageList.addMessage(
            ChatMessageListPanel.MessageRole.SYSTEM,
            buildString {
                append("Fail package written")
                if (path != null) {
                    append(" (path copied):\n")
                    append(text)
                    append("\n\nSession log: ")
                    append(sessionLog.logFilePath() ?: FailPackageExporter.logsRoot(project.basePath))
                } else {
                    append(" failed — check Activity log.")
                }
            }
        )
    }

    /**
     * Write fail package to disk. Safe from EDT or worker threads.
     * @return absolute path or null on failure
     */
    private fun writeFailPackageInternal(trigger: String, note: String): Path? {
        return try {
            val meta = FailPackageMeta(
                trigger = trigger,
                provider = settings.activeProvider.displayName,
                model = lastSendModel.ifBlank {
                    modelCombo.selectedItem?.toString().orEmpty()
                },
                skillId = lastSendSkillId.ifBlank { selectedSkill()?.id.orEmpty() },
                pathLabel = lastSendPathLabel,
                projectBase = project.basePath,
                lastUserQuestion = lastUserQuestion,
                lastAssistantAnswer = lastAssistantAnswer.ifBlank { note },
                extra = mapOf(
                    "note" to note.take(500),
                    "sessionLog" to (sessionLog.logFilePath()?.toString() ?: ""),
                    "logsDir" to FailPackageExporter.logsRoot(project.basePath).toString(),
                )
            )
            val out = FailPackageExporter.writeFailPackage(
                meta = meta,
                logLines = sessionLog.snapshot(),
                sessionLogPath = sessionLog.logFilePath()
            )
            sessionLog.system("fail package: trigger=$trigger path=$out")
            SwingUtilities.invokeLater {
                activityLogPanel.setLogPathDisplay(
                    "fail: $out | session: ${sessionLog.logFilePath() ?: ""}"
                )
            }
            out
        } catch (e: Exception) {
            sessionLog.error("fail package write failed: ${e.message}")
            null
        }
    }

    private fun failPathNote(path: Path?): String {
        if (path == null) return ""
        return "\n\n— Fail package: $path\n— Session log: ${sessionLog.logFilePath() ?: "(none)"}"
    }

    companion object {
        /** Cap how many ERR lines get mirrored into the agent chat bubble per turn. */
        private const val MAX_ERR_MIRROR_PER_TURN = 3
    }

    /** Start the ~40ms EDT flush timer if not already running. Call only on EDT. */
    private fun ensureStreamFlushTimerOnEdt() {
        if (streamFlushTimer != null) return
        streamFlushTimer = Timer(StreamUiCoalescer.DEFAULT_FLUSH_INTERVAL_MS) {
            flushStreamCoalescerToUi()
        }.apply {
            isRepeats = true
            start()
        }
    }

    /** Drain coalescer into the message list. Call only on EDT. */
    private fun flushStreamCoalescerToUi() {
        val chunk = streamUiCoalescer.drain()
        if (chunk.isNotEmpty()) {
            messageList.appendStreamingDelta(chunk)
        }
    }

    /**
     * Stop the flush timer. When [flush] is true, deliver remaining pending text first;
     * otherwise discard it (e.g. stream body reset). Call only on EDT.
     */
    private fun stopStreamFlushTimerOnEdt(flush: Boolean) {
        streamFlushTimer?.stop()
        streamFlushTimer = null
        if (flush) {
            flushStreamCoalescerToUi()
        } else {
            streamUiCoalescer.clear()
        }
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
        val forceFlush = streamUiCoalescer.offer(delta)
        SwingUtilities.invokeLater {
            ensureStreamFlushTimerOnEdt()
            if (forceFlush) flushStreamCoalescerToUi()
        }
    }

    fun finishStreaming() {
        SwingUtilities.invokeLater {
            stopStreamFlushTimerOnEdt(flush = true)
            stopButton.isEnabled = false
            sendButton.isEnabled = true
        }
    }
}
