package com.waryway.gab.model

/**
 * LLM API backends. Gab AI and xAI Grok use separate keys, quotas, and model catalogs.
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
    GROK(
        displayName = "Grok",
        baseUrl = "https://api.x.ai/v1",
        supportsCredits = false,
        keyHelpUrl = "https://console.x.ai"
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