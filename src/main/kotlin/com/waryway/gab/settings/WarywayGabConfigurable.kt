package com.waryway.gab.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.*
import com.waryway.gab.client.GabClient
import com.waryway.gab.client.GrokBuildAuth
import com.waryway.gab.client.GrokBuildAuthRecovery
import com.waryway.gab.model.ModelCatalog
import com.waryway.gab.model.ModelProvider
import com.waryway.gab.ui.LocalLlmSendUx
import java.awt.Desktop
import java.net.URI
import javax.swing.SwingConstants
import kotlinx.coroutines.runBlocking

class WarywayGabConfigurable : BoundConfigurable("Waryway Agent") {

    private val settings = WarywayGabSettings.getInstance()

    private lateinit var grokApiKeyField: Cell<JBPasswordField>
    private lateinit var gabApiKeyField: Cell<JBPasswordField>
    private lateinit var localLlmApiKeyField: Cell<JBPasswordField>
    private lateinit var grokDefaultModelField: Cell<JBTextField>
    private lateinit var grokBuildDefaultModelField: Cell<JBTextField>
    private lateinit var gabDefaultModelField: Cell<JBTextField>
    private lateinit var localLlmBaseUrlField: Cell<JBTextField>
    private lateinit var localLlmDefaultModelField: Cell<JBTextField>
    private lateinit var localLlmPresetField: Cell<JBTextField>

    /** Live Grok Build session status — re-read via [refreshGrokBuildSessionLabel]. */
    private lateinit var grokBuildSessionSummaryLabel: JBLabel

    override fun createPanel(): DialogPanel {
        return panel {
            group("Grok Build (CLI session — recommended)") {
                row {
                    grokBuildSessionSummaryLabel = JBLabel(htmlWrap(settings.grokBuildSessionSummary())).apply {
                        verticalTextPosition = SwingConstants.TOP
                    }
                    cell(grokBuildSessionSummaryLabel)
                }
                row {
                    comment(
                        "Prefers live ~/.grok/auth.json from `grok login` (same session as GoLand AI Chat ACP). " +
                            "Optional PasswordSafe override is a fallback only when the live session is missing or " +
                            "unusable (expired). Quota is separate from console.x.ai API keys."
                    )
                }
                row {
                    comment("Auth file: ${GrokBuildAuth.authJsonPath()}")
                }
                row("Default Grok Build Model:") {
                    grokBuildDefaultModelField = textField()
                        .columns(20)
                        .bindText(
                            { settings.getDefaultModel(ModelProvider.GROK_BUILD) },
                            { settings.setDefaultModel(it, ModelProvider.GROK_BUILD) }
                        )
                }
                row {
                    comment("Recommended: ${ModelCatalog.GROK_BUILD_DEFAULT_MODEL_ID} (cli-chat-proxy).")
                }
                row {
                    button("Refresh session") {
                        refreshGrokBuildSessionLabel()
                    }
                    button("Test Grok Build Connection") {
                        testConnection(ModelProvider.GROK_BUILD, settings.getApiKey(ModelProvider.GROK_BUILD).orEmpty())
                    }
                    button("Open x.ai/cli") {
                        browse(ModelProvider.GROK_BUILD.keyHelpUrl)
                    }
                }
            }

            separator()

            group("Grok API (console.x.ai — prepaid credits)") {
                row("Grok API Key") {
                    grokApiKeyField = passwordField()
                        .columns(40)
                        .applyToComponent {
                            toolTipText = "Stored securely with IntelliJ PasswordSafe"
                        }
                        .bindText(
                            { settings.getApiKey(ModelProvider.GROK) ?: "" },
                            { settings.setApiKey(it, ModelProvider.GROK) }
                        )
                }
                row {
                    comment(
                        "console.x.ai API keys bill against team API credits/licenses — " +
                            "not the same quota as Grok Build. Prefer Grok Build above when available."
                    )
                }
                row {
                    button("Test Grok API Connection") {
                        testConnection(ModelProvider.GROK, grokApiKeyField.component.password.concatToString())
                    }
                }
                row("Default Grok API Model:") {
                    grokDefaultModelField = textField()
                        .columns(20)
                        .bindText(
                            { settings.getDefaultModel(ModelProvider.GROK) },
                            { settings.setDefaultModel(it, ModelProvider.GROK) }
                        )
                }
                row {
                    // Keep ids in sync with ModelCatalog.GROK_* (wo-01-01 catalog ownership)
                    val alsoGrok = ModelCatalog.fallbackModelIds(ModelProvider.GROK)
                        .filter { it != ModelCatalog.GROK_DEFAULT_MODEL_ID }
                        .joinToString(", ")
                    comment("Recommended: ${ModelCatalog.GROK_DEFAULT_MODEL_ID}. Also: $alsoGrok.")
                }
            }

            separator()

            group("Gab AI") {
                row("Gab AI API Key") {
                    gabApiKeyField = passwordField()
                        .columns(40)
                        .applyToComponent {
                            toolTipText = "Stored securely with IntelliJ PasswordSafe"
                        }
                        .bindText(
                            { settings.getApiKey(ModelProvider.GAB_AI) ?: "" },
                            { settings.setApiKey(it, ModelProvider.GAB_AI) }
                        )
                }
                row {
                    comment("Get your key at gab.ai → Settings > API Settings. Requires Gab AI Plus subscription.")
                }
                row {
                    button("Test Gab Connection") {
                        testConnection(ModelProvider.GAB_AI, gabApiKeyField.component.password.concatToString())
                    }
                }
                row("Default Gab Model:") {
                    gabDefaultModelField = textField()
                        .columns(20)
                        .bindText(
                            { settings.getDefaultModel(ModelProvider.GAB_AI) },
                            { settings.setDefaultModel(it, ModelProvider.GAB_AI) }
                        )
                }
                row {
                    comment("Recommended: ${ModelCatalog.GAB_DEFAULT_MODEL_ID}. Also: claude-sonnet-4.5, deepseek-v3.")
                }
            }

            separator()

            group("Local LLM (offline)") {
                row("Base URL:") {
                    localLlmBaseUrlField = textField()
                        .columns(40)
                        .bindText(
                            { settings.localLlmBaseUrl },
                            { settings.localLlmBaseUrl = it }
                        )
                }
                row {
                    comment("OpenAI-compatible endpoint from apps/localllm (default http://127.0.0.1:7400/v1). Run scripts\\localllm-run.bat first.")
                }
                row("API Key:") {
                    localLlmApiKeyField = passwordField()
                        .columns(40)
                        .bindText(
                            { settings.getApiKey(ModelProvider.LOCAL_LLM) ?: "" },
                            { settings.setApiKey(it, ModelProvider.LOCAL_LLM) }
                        )
                }
                row {
                    comment(
                        "Default: localllm-local — must match data/localllm/config.json openai.apiKey when set. " +
                            "Wrong/empty key → HTTP 401 (visible error), not a blank chat reply. " +
                            "Only optional if server openai.apiKey is empty (open mode)."
                    )
                }
                row("Default Local Model:") {
                    localLlmDefaultModelField = textField()
                        .columns(20)
                        .bindText(
                            { settings.getDefaultModel(ModelProvider.LOCAL_LLM) },
                            { settings.setDefaultModel(it, ModelProvider.LOCAL_LLM) }
                        )
                }
                row("Prompt Preset:") {
                    localLlmPresetField = textField()
                        .columns(20)
                        .bindText(
                            { settings.localLlmPreset },
                            { settings.localLlmPreset = it }
                        )
                }
                row {
                    comment("Recommended: gab-chat (plugin chat), goland (Bazel context), or code-edit (file patches).")
                }
                row {
                    button("Test Local LLM Connection") {
                        testConnection(ModelProvider.LOCAL_LLM, localLlmApiKeyField.component.password.concatToString())
                    }
                }
                row {
                    checkBox("Agent mode → /api/agent (plan + tools) — default ON")
                        .bindSelected(
                            { settings.localLlmAgentMode },
                            { settings.localLlmAgentMode = it }
                        )
                }
                row {
                    comment(
                        "On: next Send uses /api/agent (Agent · dry-run or APPLY in the workbench). " +
                            "Off: next Send uses /v1/chat/completions (Chat). Unrelated to MCP tools below."
                    )
                }
                row {
                    checkBox("Dry-run agent runs (no workspace writes) — recommended")
                        .bindSelected(
                            { settings.localLlmAgentDryRun },
                            { settings.localLlmAgentDryRun = it }
                        )
                }
                row {
                    comment(
                        "Default on. Uncheck only when you want the server to apply file changes " +
                            "(dryRun: false). Workbench also has an Apply toggle per session."
                    )
                }
                row("Agent preset:") {
                    textField()
                        .columns(20)
                        .bindText(
                            { settings.localLlmAgentPreset },
                            { settings.localLlmAgentPreset = it }
                        )
                }
                row {
                    comment("Default: agent-plan. Separate from chat Prompt Preset above.")
                }
                row("Agent max steps (0 = server default):") {
                    intTextField(IntRange(0, 200))
                        .bindIntText(
                            { settings.localLlmAgentMaxSteps },
                            { settings.localLlmAgentMaxSteps = it }
                        )
                }
                row("Agent poll timeout (minutes):") {
                    intTextField(IntRange(5, 120))
                        .bindIntText(
                            { settings.localLlmAgentTimeoutMinutes },
                            { settings.localLlmAgentTimeoutMinutes = it }
                        )
                }
                row {
                    comment(
                        "How long GoLand waits for /api/agent runs (default 30). " +
                            "Pure-Go go-cpu planning can take several minutes; raise for slow CPUs. " +
                            "Polls are quiet (no per-GET spam) and back off while stuck."
                    )
                }
            }

            separator()

            group("GoLand MCP Server") {
                row("IDE MCP Port (0 = auto):") {
                    intTextField(IntRange(0, 65535))
                        .bindIntText(
                            { settings.golandMcpIdePort },
                            { settings.golandMcpIdePort = it }
                        )
                }
                row {
                    comment(
                        "Optional: Enable Settings → Tools → MCP Server for the full IDE toolset. " +
                            "If MCP HTTP is off (common: /api/about works, list_tools 404), the plugin " +
                            "uses in-process native tools (read/search/edit/run). Port auto-scans " +
                            "63342–63352 (or IDE_PORT). Pin the port here once known."
                    )
                }
                row {
                    checkBox("Enable IDE tools for Local LLM")
                        .bindSelected(
                            { settings.localLlmUseMcpTools },
                            { settings.localLlmUseMcpTools = it }
                        )
                }
                row {
                    comment(
                        "Off by default — small local models often mishandle tool_calls. " +
                            "When on, uses MCP if reachable else native tools."
                    )
                }
            }

            separator()

            row("Active Provider:") {
                comboBox(ModelProvider.selectable.map { it.displayName })
                    .bindItem(
                        { settings.activeProvider.displayName },
                        { name ->
                            settings.activeProvider = ModelProvider.selectable
                                .find { it.displayName == name } ?: settings.activeProvider
                        }
                    )
            }
            row {
                comment("The chat tool window uses this provider by default. You can switch per conversation.")
            }

            row("Default Thinking Level:") {
                comboBox(listOf("auto", "none", "standard", "deep"))
                    .bindItem(
                        { settings.thinkingLevel },
                        { settings.thinkingLevel = it ?: "auto" }
                    )
            }

            row("Chat stream timeout (minutes, 0 = auto):") {
                intTextField(IntRange(0, 120))
                    .bindIntText(
                        { settings.chatStreamTimeoutMinutes },
                        { settings.chatStreamTimeoutMinutes = it }
                    )
            }
            row {
                comment(
                    "SSE budget for Gab / Grok / Grok Build / Local chat (not Local agent poll). " +
                        "0 = auto (cloud 15 min, local chat 30 min). Was 3.5 min hard-coded and " +
                        "timed out long reasoning. Transient timeouts are retried automatically."
                )
            }

            row {
                button("Open x.ai/cli (Grok Build)") {
                    browse(ModelProvider.GROK_BUILD.keyHelpUrl)
                }
                button("Open console.x.ai (API)") {
                    browse(ModelProvider.GROK.keyHelpUrl)
                }
                button("Open gab.ai") {
                    browse(ModelProvider.GAB_AI.keyHelpUrl)
                }
                button("Open Local LLM") {
                    browse("http://127.0.0.1:7400")
                }
            }
        }
    }

    private fun browse(url: String) {
        try {
            Desktop.getDesktop().browse(URI(url))
        } catch (_: Exception) {
        }
    }

    /**
     * Re-reads `~/.grok/auth.json` via [WarywayGabSettings.refreshGrokBuildSession] and updates
     * the visible summary without reopening Settings. Missing vs expired stay distinct.
     */
    private fun refreshGrokBuildSessionLabel() {
        if (!::grokBuildSessionSummaryLabel.isInitialized) return
        val summary = settings.refreshGrokBuildSession()
        grokBuildSessionSummaryLabel.text = htmlWrap(summary)
        grokBuildSessionSummaryLabel.revalidate()
        grokBuildSessionSummaryLabel.repaint()
    }

    private fun htmlWrap(text: String): String {
        val escaped = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        return "<html><body style='width: 420px'>$escaped</body></html>"
    }

    /**
     * Probes connectivity via [GabClient.listModels] on [settings.createClient] for [provider],
     * then filters with [ModelCatalog.filterForProvider].
     * Grok API uses PasswordSafe key + api.x.ai; Grok Build uses ~/.grok/auth.json + cli-chat-proxy.
     */
    private fun testConnection(provider: ModelProvider, key: String) {
        val needsPastedKey = provider != ModelProvider.LOCAL_LLM &&
            provider != ModelProvider.GROK_BUILD &&
            key.isBlank()
        if (needsPastedKey) {
            com.intellij.openapi.ui.Messages.showErrorDialog(
                "Please enter a ${provider.displayName} API key first.",
                "${provider.displayName} connection"
            )
            return
        }
        if (provider == ModelProvider.GROK_BUILD && !settings.hasApiKey(ModelProvider.GROK_BUILD)) {
            // Live re-read; summary already distinguishes missing vs expired via recovery helper.
            refreshGrokBuildSessionLabel()
            com.intellij.openapi.ui.Messages.showErrorDialog(
                settings.grokBuildSessionSummary(),
                "${provider.displayName} connection"
            )
            return
        }
        try {
            if (provider != ModelProvider.GROK_BUILD && key.isNotBlank()) {
                // Include Local LLM so the field value under test is what we send (401 if wrong).
                settings.setApiKey(key, provider)
            }
            val client = settings.createClient(provider)
            val models = runBlocking { client.listModels() }
            val filtered = ModelCatalog.filterForProvider(models, provider)
            val extra = if (provider == ModelProvider.GROK_BUILD) {
                refreshGrokBuildSessionLabel()
                "\n${settings.grokBuildSessionSummary()}"
            } else {
                ""
            }
            com.intellij.openapi.ui.Messages.showInfoMessage(
                "${provider.displayName} connection successful! ${filtered.size} models available.$extra",
                "${provider.displayName} connection"
            )
        } catch (e: Exception) {
            val message = when (provider) {
                ModelProvider.GROK_BUILD -> {
                    refreshGrokBuildSessionLabel()
                    val body = (e as? GabClient.GabApiException)?.body
                    GrokBuildAuthRecovery.formatAuthFailure(message = e.message, body = body)
                        ?: "${provider.displayName} connection failed: ${e.message}"
                }
                ModelProvider.LOCAL_LLM ->
                    LocalLlmSendUx.formatAuthFailure(e, agentMode = false)
                        ?: LocalLlmSendUx.formatFailure(e, agentMode = false, rootUrl = settings.localLlmBaseUrl)
                else ->
                    "${provider.displayName} connection failed: ${e.message}"
            }
            com.intellij.openapi.ui.Messages.showErrorDialog(
                message,
                "${provider.displayName} connection"
            )
        }
    }
}