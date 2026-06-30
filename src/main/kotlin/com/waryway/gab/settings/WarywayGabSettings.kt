package com.waryway.gab.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.waryway.gab.client.GabClient
import com.waryway.gab.diagnostics.SessionLog
import com.waryway.gab.model.ModelCatalog
import com.waryway.gab.model.ModelProvider

/**
 * Application-level settings for Waryway Agent plugin.
 * API keys are stored per provider in PasswordSafe (secure storage).
 * Other preferences are persisted via XML state.
 */
@Service
@State(name = "WarywayGabSettings", storages = [Storage("WarywayGabSettings.xml")])
class WarywayGabSettings : PersistentStateComponent<WarywayGabSettings.State> {

    data class State(
        var activeProvider: String = ModelProvider.LOCAL_LLM.name,
        var gabDefaultModel: String = ModelCatalog.GAB_DEFAULT_MODEL_ID,
        var gabLastUsedModel: String = ModelCatalog.GAB_DEFAULT_MODEL_ID,
        var grokDefaultModel: String = ModelCatalog.GROK_DEFAULT_MODEL_ID,
        var grokLastUsedModel: String = ModelCatalog.GROK_DEFAULT_MODEL_ID,
        var localLlmBaseUrl: String = ModelProvider.LOCAL_LLM.baseUrl,
        var localLlmApiKey: String = "localllm-local",
        var localLlmPreset: String = "gab-chat",
        var localLlmDefaultModel: String = ModelCatalog.LOCAL_LLM_DEFAULT_MODEL_ID,
        var localLlmLastUsedModel: String = ModelCatalog.LOCAL_LLM_DEFAULT_MODEL_ID,
        var selectedSkillId: String = "none",
        var thinkingLevel: String = "auto",
        var localLlmContextCompaction: Boolean = true,
        var localLlmContextTokens: Int = 4096,
        var localLlmKeepRecentTurns: Int = 4,
        /** Legacy single-provider fields — migrated on load. */
        var defaultModel: String = "",
        var lastUsedModel: String = ""
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
        migrateLegacyModels()
    }

    private fun migrateLegacyModels() {
        val legacyDefault = myState.defaultModel.trim()
        val legacyLast = myState.lastUsedModel.trim()
        if (legacyDefault.isEmpty() && legacyLast.isEmpty()) return

        val model = legacyLast.ifBlank { legacyDefault }
        if (ModelCatalog.isGrok(model)) {
            if (legacyDefault.isNotBlank()) myState.grokDefaultModel = legacyDefault
            if (legacyLast.isNotBlank()) myState.grokLastUsedModel = legacyLast
            myState.activeProvider = ModelProvider.GROK.name
        } else {
            if (legacyDefault.isNotBlank()) myState.gabDefaultModel = legacyDefault
            if (legacyLast.isNotBlank()) myState.gabLastUsedModel = legacyLast
        }
        myState.defaultModel = ""
        myState.lastUsedModel = ""
    }

    var activeProvider: ModelProvider
        get() = ModelProvider.fromId(myState.activeProvider)
        set(value) { myState.activeProvider = value.name }

    var thinkingLevel: String
        get() = myState.thinkingLevel
        set(value) { myState.thinkingLevel = value }

    var localLlmBaseUrl: String
        get() = myState.localLlmBaseUrl.ifBlank { ModelProvider.LOCAL_LLM.baseUrl }
        set(value) { myState.localLlmBaseUrl = value.trim() }

    var localLlmPreset: String
        get() = myState.localLlmPreset.ifBlank { "gab-chat" }
        set(value) { myState.localLlmPreset = value.trim() }

    var selectedSkillId: String
        get() = myState.selectedSkillId.ifBlank { "none" }
        set(value) { myState.selectedSkillId = value.trim() }

    var localLlmContextCompaction: Boolean
        get() = myState.localLlmContextCompaction
        set(value) { myState.localLlmContextCompaction = value }

    var localLlmContextTokens: Int
        get() = myState.localLlmContextTokens.coerceIn(1024, 32_768)
        set(value) { myState.localLlmContextTokens = value.coerceIn(1024, 32_768) }

    var localLlmKeepRecentTurns: Int
        get() = myState.localLlmKeepRecentTurns.coerceIn(1, 12)
        set(value) { myState.localLlmKeepRecentTurns = value.coerceIn(1, 12) }

    fun getDefaultModel(provider: ModelProvider = activeProvider): String = when (provider) {
        ModelProvider.GAB_AI -> myState.gabDefaultModel
        ModelProvider.GROK -> myState.grokDefaultModel
        ModelProvider.LOCAL_LLM -> myState.localLlmDefaultModel
    }

    fun setDefaultModel(value: String, provider: ModelProvider = activeProvider) {
        when (provider) {
            ModelProvider.GAB_AI -> myState.gabDefaultModel = value
            ModelProvider.GROK -> myState.grokDefaultModel = value
            ModelProvider.LOCAL_LLM -> myState.localLlmDefaultModel = value
        }
    }

    fun getLastUsedModel(provider: ModelProvider = activeProvider): String = when (provider) {
        ModelProvider.GAB_AI -> myState.gabLastUsedModel
        ModelProvider.GROK -> myState.grokLastUsedModel
        ModelProvider.LOCAL_LLM -> myState.localLlmLastUsedModel
    }

    fun setLastUsedModel(value: String, provider: ModelProvider = activeProvider) {
        when (provider) {
            ModelProvider.GAB_AI -> myState.gabLastUsedModel = value
            ModelProvider.GROK -> myState.grokLastUsedModel = value
            ModelProvider.LOCAL_LLM -> myState.localLlmLastUsedModel = value
        }
    }

    fun getApiKey(provider: ModelProvider = activeProvider): String? = when (provider) {
        ModelProvider.LOCAL_LLM -> {
            val stored = myState.localLlmApiKey.trim()
            stored.ifBlank { "localllm-local" }
        }
        else -> {
            val creds = PasswordSafe.instance.get(credentialAttributes(provider))
            creds?.getPasswordAsString()
        }
    }

    fun setApiKey(key: String?, provider: ModelProvider = activeProvider) {
        when (provider) {
            ModelProvider.LOCAL_LLM -> myState.localLlmApiKey = key?.trim().orEmpty()
            else -> {
                val attributes = credentialAttributes(provider)
                if (key.isNullOrBlank()) {
                    PasswordSafe.instance.set(attributes, null)
                } else {
                    PasswordSafe.instance.set(attributes, Credentials(provider.name.lowercase(), key))
                }
            }
        }
    }

    fun getBaseUrl(provider: ModelProvider = activeProvider): String = when (provider) {
        ModelProvider.LOCAL_LLM -> localLlmBaseUrl
        else -> provider.baseUrl
    }

    fun hasApiKey(provider: ModelProvider): Boolean = when (provider) {
        ModelProvider.LOCAL_LLM -> localLlmBaseUrl.isNotBlank()
        else -> !getApiKey(provider).isNullOrBlank()
    }

    fun hasAnyApiKey(): Boolean = ModelProvider.entries.any { hasApiKey(it) }

    fun createClient(provider: ModelProvider = activeProvider, sessionLog: SessionLog? = null): GabClient {
        val key = getApiKey(provider).orEmpty()
        return GabClient(
            apiKey = key,
            provider = provider,
            baseUrlOverride = getBaseUrl(provider),
            localLlmPreset = if (provider == ModelProvider.LOCAL_LLM) localLlmPreset else null,
            sessionLog = sessionLog
        )
    }

    private fun credentialAttributes(provider: ModelProvider): CredentialAttributes =
        CredentialAttributes(generateServiceName("WarywayAgent", provider.name))

    companion object {
        fun getInstance(): WarywayGabSettings =
            com.intellij.openapi.application.ApplicationManager.getApplication()
                .getService(WarywayGabSettings::class.java)
    }
}