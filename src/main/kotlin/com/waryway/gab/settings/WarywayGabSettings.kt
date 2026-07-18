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
import com.waryway.gab.client.GrokBuildAuth
import com.waryway.gab.client.GrokBuildAuthRecovery
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
        var grokBuildDefaultModel: String = ModelCatalog.GROK_BUILD_DEFAULT_MODEL_ID,
        var grokBuildLastUsedModel: String = ModelCatalog.GROK_BUILD_DEFAULT_MODEL_ID,
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
        /** 0 = auto-discover IDE MCP port (63342–63352). */
        var golandMcpIdePort: Int = 0,
        var localLlmUseMcpTools: Boolean = false,
        /**
         * Local LLM agent mode uses server /api/agent (not OpenAI tools).
         * When true, IDE posts dryRun=true (safe default — no workspace writes).
         * Apply requires explicit opt-out via localLlmAgentDryRun=false (UI toggle).
         */
        var localLlmAgentDryRun: Boolean = true,
        /** Default preset for /api/agent/runs (server agent-plan). Separate from chat localLlmPreset. */
        var localLlmAgentPreset: String = "agent-plan",
        /** Max plan/execute steps for agent runs; 0 = server default. */
        var localLlmAgentMaxSteps: Int = 30,
        /**
         * When true and provider is LOCAL_LLM, Send uses AgentClient (/api/agent)
         * instead of AgentSession chat completions.
         */
        var localLlmAgentMode: Boolean = true,
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

    var golandMcpIdePort: Int
        get() = myState.golandMcpIdePort.coerceIn(0, 65535)
        set(value) { myState.golandMcpIdePort = value.coerceIn(0, 65535) }

    var localLlmUseMcpTools: Boolean
        get() = myState.localLlmUseMcpTools
        set(value) { myState.localLlmUseMcpTools = value }

    /** Safe default true — never silent apply for IDE agent runs. */
    var localLlmAgentDryRun: Boolean
        get() = myState.localLlmAgentDryRun
        set(value) { myState.localLlmAgentDryRun = value }

    var localLlmAgentPreset: String
        get() = myState.localLlmAgentPreset.ifBlank { "agent-plan" }
        set(value) { myState.localLlmAgentPreset = value.trim().ifBlank { "agent-plan" } }

    var localLlmAgentMaxSteps: Int
        get() = myState.localLlmAgentMaxSteps.coerceIn(0, 200)
        set(value) { myState.localLlmAgentMaxSteps = value.coerceIn(0, 200) }

    /** LOCAL_LLM only: true → /api/agent; false → chat completions (AgentSession). */
    var localLlmAgentMode: Boolean
        get() = myState.localLlmAgentMode
        set(value) { myState.localLlmAgentMode = value }

    fun getDefaultModel(provider: ModelProvider = activeProvider): String = when (provider) {
        ModelProvider.GAB_AI -> myState.gabDefaultModel
        ModelProvider.GROK -> myState.grokDefaultModel
        ModelProvider.GROK_BUILD -> myState.grokBuildDefaultModel
        ModelProvider.LOCAL_LLM -> myState.localLlmDefaultModel
    }

    fun setDefaultModel(value: String, provider: ModelProvider = activeProvider) {
        when (provider) {
            ModelProvider.GAB_AI -> myState.gabDefaultModel = value
            ModelProvider.GROK -> myState.grokDefaultModel = value
            ModelProvider.GROK_BUILD -> myState.grokBuildDefaultModel = value
            ModelProvider.LOCAL_LLM -> myState.localLlmDefaultModel = value
        }
    }

    fun getLastUsedModel(provider: ModelProvider = activeProvider): String = when (provider) {
        ModelProvider.GAB_AI -> myState.gabLastUsedModel
        ModelProvider.GROK -> myState.grokLastUsedModel
        ModelProvider.GROK_BUILD -> myState.grokBuildLastUsedModel
        ModelProvider.LOCAL_LLM -> myState.localLlmLastUsedModel
    }

    fun setLastUsedModel(value: String, provider: ModelProvider = activeProvider) {
        when (provider) {
            ModelProvider.GAB_AI -> myState.gabLastUsedModel = value
            ModelProvider.GROK -> myState.grokLastUsedModel = value
            ModelProvider.GROK_BUILD -> myState.grokBuildLastUsedModel = value
            ModelProvider.LOCAL_LLM -> myState.localLlmLastUsedModel = value
        }
    }

    /**
     * Provider-scoped credential.
     * - Grok API + Gab AI: **separate** PasswordSafe entries keyed by [ModelProvider.name]
     * - Grok Build: see [grokBuildAccessToken] precedence
     * - Local LLM: state field only
     */
    fun getApiKey(provider: ModelProvider = activeProvider): String? {
        return when (provider) {
            ModelProvider.LOCAL_LLM -> {
                val stored = myState.localLlmApiKey.trim()
                stored.ifBlank { "localllm-local" }
            }
            ModelProvider.GROK_BUILD -> grokBuildAccessToken()
            // GROK and GAB_AI: distinct CredentialAttributes — never share a key.
            ModelProvider.GROK, ModelProvider.GAB_AI -> {
                val creds = PasswordSafe.instance.get(credentialAttributes(provider))
                creds?.getPasswordAsString()
            }
        }
    }

    /**
     * Grok Build token resolution (always live-read; no in-memory token cache).
     *
     * Precedence:
     * 1. **Usable live session** from `~/.grok/auth.json` via [GrokBuildAuth.readSession]
     *    (non-blank token and not expired / near-expired). A fresh `grok login` always wins
     *    over any PasswordSafe override — stale overrides never shadow a usable live session.
     * 2. **PasswordSafe override** only when live session is missing or unusable (expired,
     *    blank token, or no auth.json). Use for emergency/manual token paste; prefer re-login.
     */
    fun grokBuildAccessToken(): String? {
        val session = GrokBuildAuth.readSession()
        if (session != null && !session.isExpired() && session.accessToken.isNotBlank()) {
            return session.accessToken.trim()
        }
        val creds = PasswordSafe.instance.get(credentialAttributes(ModelProvider.GROK_BUILD))
        return creds?.getPasswordAsString()?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun setApiKey(key: String?, provider: ModelProvider = activeProvider) {
        when (provider) {
            ModelProvider.LOCAL_LLM -> myState.localLlmApiKey = key?.trim().orEmpty()
            ModelProvider.GROK, ModelProvider.GAB_AI, ModelProvider.GROK_BUILD -> {
                val attributes = credentialAttributes(provider)
                if (key.isNullOrBlank()) {
                    PasswordSafe.instance.set(attributes, null)
                } else {
                    PasswordSafe.instance.set(attributes, Credentials(provider.name.lowercase(), key))
                }
            }
        }
    }

    /**
     * Provider-scoped base URL.
     * Cloud providers always use the enum constant ([ModelProvider.GROK] → `https://api.x.ai/v1`);
     * only Local LLM allows a user override.
     */
    fun getBaseUrl(provider: ModelProvider = activeProvider): String = when (provider) {
        ModelProvider.LOCAL_LLM -> localLlmBaseUrl
        ModelProvider.GROK, ModelProvider.GROK_BUILD, ModelProvider.GAB_AI -> provider.baseUrl
    }

    /**
     * Whether the provider has a credential the client can send.
     *
     * For [ModelProvider.GROK_BUILD]: true only when [GrokBuildAuth.hasUsableSession] (live,
     * non-expired token) **or** a non-blank PasswordSafe override is present.
     * An expired-only live token does **not** count as usable by itself — [getApiKey] will
     * still fall through to override if one exists.
     */
    fun hasApiKey(provider: ModelProvider): Boolean = when (provider) {
        ModelProvider.LOCAL_LLM -> localLlmBaseUrl.isNotBlank()
        ModelProvider.GROK_BUILD ->
            GrokBuildAuth.hasUsableSession() || !grokBuildAccessToken().isNullOrBlank()
        ModelProvider.GROK, ModelProvider.GAB_AI -> !getApiKey(provider).isNullOrBlank()
    }

    /**
     * Human-readable Grok Build session status for settings / coaching.
     * Missing vs expired copy comes from [GrokBuildAuthRecovery] (single string table).
     * Always re-reads disk — no memoized session.
     */
    fun grokBuildSessionSummary(): String {
        val session = GrokBuildAuth.readSession()
        return when (GrokBuildAuthRecovery.classifySession(session)) {
            GrokBuildAuthRecovery.SessionState.MISSING ->
                GrokBuildAuthRecovery.coachingMissingSession()
            GrokBuildAuthRecovery.SessionState.EXPIRED ->
                GrokBuildAuthRecovery.coachingExpiredSession(email = session?.email)
            GrokBuildAuthRecovery.SessionState.USABLE -> {
                val email = session?.email ?: "signed in"
                "Signed in as $email (cli-chat-proxy session; same quota as Grok Build CLI)."
            }
        }
    }

    /**
     * Re-read `~/.grok/auth.json` after `grok login` without IDE restart and return an
     * updated summary. Sessions are never cached in settings state — this is an explicit
     * operator affordance that re-invokes [GrokBuildAuth.readSession] + summary.
     */
    fun refreshGrokBuildSession(): String = grokBuildSessionSummary()

    fun hasAnyApiKey(): Boolean = ModelProvider.entries.any { hasApiKey(it) }

    /**
     * Builds a [GabClient] bound to a single [provider].
     * Key, base URL, and [GabClient.provider] always come from the same [provider] argument —
     * so `createClient(GROK)` never sends the Gab key to gab.ai (or vice versa).
     */
    fun createClient(provider: ModelProvider = activeProvider, sessionLog: SessionLog? = null): GabClient {
        val key = getApiKey(provider).orEmpty()
        val baseUrl = getBaseUrl(provider)
        return GabClient(
            apiKey = key,
            provider = provider,
            baseUrlOverride = baseUrl,
            localLlmPreset = if (provider == ModelProvider.LOCAL_LLM) localLlmPreset else null,
            sessionLog = sessionLog
        )
    }

    /**
     * PasswordSafe service name is per-provider (`WarywayAgent` + [ModelProvider.name]),
     * e.g. `GROK` vs `GAB_AI` never collide.
     */
    internal fun credentialServiceName(provider: ModelProvider): String =
        generateServiceName("WarywayAgent", provider.name)

    private fun credentialAttributes(provider: ModelProvider): CredentialAttributes =
        CredentialAttributes(credentialServiceName(provider))

    companion object {
        fun getInstance(): WarywayGabSettings =
            com.intellij.openapi.application.ApplicationManager.getApplication()
                .getService(WarywayGabSettings::class.java)
    }
}