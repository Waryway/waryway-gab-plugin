package com.waryway.gab.model

/**
 * LLM API backends. Gab AI, xAI API Grok, Grok Build, and Local LLM use separate
 * credentials, quotas, and (where applicable) model catalogs.
 *
 * Cloud / local providers must never share credentials or base URLs:
 * - [GAB_AI] → gab.ai + Gab PasswordSafe key
 * - [GROK] → api.x.ai + Grok PasswordSafe API key (prepaid team credits)
 * - [GROK_BUILD] → cli-chat-proxy.grok.com + `~/.grok/auth.json` session (`grok login`)
 * - [LOCAL_LLM] → configurable local base URL + local key field
 */
enum class ModelProvider(
    val displayName: String,
    val baseUrl: String,
    val supportsCredits: Boolean,
    val keyHelpUrl: String
) {
    GAB_AI(
        displayName = "Gab AI",
        baseUrl = "https://gab.ai/v1",
        supportsCredits = true,
        keyHelpUrl = "https://gab.ai"
    ),
    /** First-class xAI Grok path — OpenAI-compatible API at api.x.ai (API key / team credits). */
    GROK(
        displayName = "Grok (API)",
        baseUrl = "https://api.x.ai/v1",
        supportsCredits = false,
        keyHelpUrl = "https://console.x.ai"
    ),
    /**
     * Grok Build / Grok CLI quota — same path as local `grok` and GoLand AI Chat ACP.
     * Uses OIDC session from `~/.grok/auth.json`, not console API keys.
     */
    GROK_BUILD(
        displayName = "Grok Build",
        baseUrl = "https://cli-chat-proxy.grok.com/v1",
        supportsCredits = false,
        keyHelpUrl = "https://x.ai/cli"
    ),
    LOCAL_LLM(
        displayName = "Local LLM",
        baseUrl = "http://127.0.0.1:7400/v1",
        supportsCredits = false,
        keyHelpUrl = "http://127.0.0.1:7400"
    );

    companion object {
        fun fromId(id: String?): ModelProvider =
            entries.find { it.name.equals(id, ignoreCase = true) } ?: GROK

        val selectable: List<ModelProvider> = entries.toList()
    }
}