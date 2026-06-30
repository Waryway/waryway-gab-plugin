package com.waryway.gab.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.*
import com.waryway.gab.client.GabClient
import com.waryway.gab.model.ModelCatalog
import com.waryway.gab.model.ModelProvider
import java.awt.Desktop
import java.net.URI
import kotlinx.coroutines.runBlocking

class WarywayGabConfigurable : BoundConfigurable("Waryway Agent") {

    private val settings = WarywayGabSettings.getInstance()
    private val displayName = "Waryway Agent"

    private lateinit var grokApiKeyField: Cell<JBPasswordField>
    private lateinit var gabApiKeyField: Cell<JBPasswordField>
    private lateinit var localLlmApiKeyField: Cell<JBPasswordField>
    private lateinit var grokDefaultModelField: Cell<JBTextField>
    private lateinit var gabDefaultModelField: Cell<JBTextField>
    private lateinit var localLlmBaseUrlField: Cell<JBTextField>
    private lateinit var localLlmDefaultModelField: Cell<JBTextField>
    private lateinit var localLlmPresetField: Cell<JBTextField>

    override fun createPanel(): DialogPanel {
        return panel {
            group("Grok (xAI)") {
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
                    comment("Get your key at console.x.ai. Separate quota from Gab AI.")
                }
                row {
                    button("Test Grok Connection") {
                        testConnection(ModelProvider.GROK, grokApiKeyField.component.password.concatToString())
                    }
                }
                row("Default Grok Model:") {
                    grokDefaultModelField = textField()
                        .columns(20)
                        .bindText(
                            { settings.getDefaultModel(ModelProvider.GROK) },
                            { settings.setDefaultModel(it, ModelProvider.GROK) }
                        )
                }
                row {
                    comment("Recommended: ${ModelCatalog.GROK_DEFAULT_MODEL_ID}. Also: grok-4.2, grok-4, grok-3, grok-build-0.1.")
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
                row("API Key (optional):") {
                    localLlmApiKeyField = passwordField()
                        .columns(40)
                        .bindText(
                            { settings.getApiKey(ModelProvider.LOCAL_LLM) ?: "" },
                            { settings.setApiKey(it, ModelProvider.LOCAL_LLM) }
                        )
                }
                row {
                    comment("Default: localllm-local — must match data/localllm/config.json openai.apiKey if set.")
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
                        "Enable Settings → Tools → MCP Server in GoLand. Port auto-scans 63342–63352 " +
                            "(or IDE_PORT env). Agent tools call the built-in MCP HTTP API."
                    )
                }
                row {
                    checkBox("Enable MCP tools for Local LLM")
                        .bindSelected(
                            { settings.localLlmUseMcpTools },
                            { settings.localLlmUseMcpTools = it }
                        )
                }
                row {
                    comment("Off by default — small local models often mishandle tool_calls.")
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

            row {
                button("Open console.x.ai") {
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

    private fun testConnection(provider: ModelProvider, key: String) {
        if (provider != ModelProvider.LOCAL_LLM && key.isBlank()) {
            com.intellij.openapi.ui.Messages.showErrorDialog(
                "Please enter a ${provider.displayName} API key first.",
                displayName
            )
            return
        }
        try {
            if (provider != ModelProvider.LOCAL_LLM) {
                settings.setApiKey(key, provider)
            }
            val client = settings.createClient(provider)
            val models = runBlocking { client.listModels() }
            val filtered = ModelCatalog.filterForProvider(models, provider)
            com.intellij.openapi.ui.Messages.showInfoMessage(
                "${provider.displayName} connection successful! ${filtered.size} models available.",
                displayName
            )
        } catch (e: Exception) {
            com.intellij.openapi.ui.Messages.showErrorDialog(
                "Test failed: ${e.message}",
                displayName
            )
        }
    }
}