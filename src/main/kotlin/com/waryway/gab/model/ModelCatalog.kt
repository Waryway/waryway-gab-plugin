package com.waryway.gab.model

import com.waryway.gab.client.GabClient

/**
 * Provider-aware model preferences for Waryway Agent.
 * Gab AI, Grok API (xAI), Grok Build (CLI session), and Local LLM have separate catalogs/defaults.
 *
 * Product intent (Grok API):
 * - Default / first preferred: [GROK_DEFAULT_MODEL_ID] (`grok-4.3`)
 * - Fallbacks / preferred order: 4.3 → 4.2 → 4 → 3 → build-0.1
 * - xAI models may be identified by id containing "grok" **or** `ownedBy` containing "xai"
 * - Local and Gab-only models never appear in the Grok list (and vice versa)
 *
 * Grok Build (cli-chat-proxy) currently surfaces models such as `grok-4.5` under Build quota.
 */
object ModelCatalog {

    const val GROK_DEFAULT_MODEL_ID = "grok-4.3"
    const val GROK_BUILD_DEFAULT_MODEL_ID = "grok-4.5"
    const val GAB_DEFAULT_MODEL_ID = "gpt-5"
    const val LOCAL_LLM_DEFAULT_MODEL_ID = "localllm-coder"
    const val LOCAL_LLM_DEFAULT_CONTEXT = 4096

    /** @deprecated Use [GROK_DEFAULT_MODEL_ID] or [GAB_DEFAULT_MODEL_ID] */
    @Deprecated("Use provider-specific default", ReplaceWith("GROK_DEFAULT_MODEL_ID"))
    const val DEFAULT_MODEL_ID = GROK_DEFAULT_MODEL_ID

    val GROK_FALLBACK_MODEL_IDS = listOf(
        "grok-4.3",
        "grok-4.2",
        "grok-4",
        "grok-3",
        "grok-build-0.1"
    )

    val GROK_BUILD_FALLBACK_MODEL_IDS = listOf(
        "grok-4.5",
        "grok-build",
        "grok-composer-2.5-fast"
    )

    val GAB_FALLBACK_MODEL_IDS = listOf(
        "gpt-5",
        "claude-sonnet-4.5",
        "claude-opus-4.7",
        "deepseek-v3",
        "qwen-3.5-thinking",
        "arya"
    )

    val LOCAL_LLM_FALLBACK_MODEL_IDS = listOf(
        "localllm-coder",
        "localllm-default",
        "localllm-starter",
        "localllm-balanced"
    )

    /** @deprecated Use [fallbackModelIds] */
    @Deprecated("Use provider-specific fallbacks", ReplaceWith("GROK_FALLBACK_MODEL_IDS"))
    val FALLBACK_MODEL_IDS = GROK_FALLBACK_MODEL_IDS

    private val GROK_PREFERRED_ORDER = listOf(
        "grok-4.3",
        "grok-4.2",
        "grok-4",
        "grok-3",
        "grok-build-0.1"
    )

    private val GROK_BUILD_PREFERRED_ORDER = listOf(
        "grok-4.5",
        "grok-build",
        "grok-composer-2.5-fast"
    )

    private val GAB_PREFERRED_ORDER = listOf(
        "gpt-5",
        "claude-opus-4.7",
        "claude-sonnet-4.5",
        "deepseek-v3",
        "qwen-3.5-thinking",
        "arya"
    )

    fun defaultModelId(provider: ModelProvider): String = when (provider) {
        ModelProvider.GROK -> GROK_DEFAULT_MODEL_ID
        ModelProvider.GROK_BUILD -> GROK_BUILD_DEFAULT_MODEL_ID
        ModelProvider.GAB_AI -> GAB_DEFAULT_MODEL_ID
        ModelProvider.LOCAL_LLM -> LOCAL_LLM_DEFAULT_MODEL_ID
    }

    fun fallbackModelIds(provider: ModelProvider): List<String> = when (provider) {
        ModelProvider.GROK -> GROK_FALLBACK_MODEL_IDS
        ModelProvider.GROK_BUILD -> GROK_BUILD_FALLBACK_MODEL_IDS
        ModelProvider.GAB_AI -> GAB_FALLBACK_MODEL_IDS
        ModelProvider.LOCAL_LLM -> LOCAL_LLM_FALLBACK_MODEL_IDS
    }

    fun isLocalLLM(modelId: String): Boolean =
        modelId.startsWith("localllm-", ignoreCase = true) ||
            modelId.endsWith(".gguf", ignoreCase = true)

    /** Id-only heuristic (no ownership metadata). Prefer [isGrok] for API [GabClient.ModelInfo]. */
    fun isGrok(modelId: String): Boolean = modelId.contains("grok", ignoreCase = true)

    /**
     * True for xAI Grok models: id contains "grok", or [GabClient.ModelInfo.ownedBy] contains "xai".
     * Local LLM artifacts are never treated as Grok even if the filename mentions "grok".
     */
    fun isGrok(model: GabClient.ModelInfo): Boolean {
        if (isLocalLLM(model.id) || model.ownedBy.equals("localllm", ignoreCase = true)) return false
        return isGrok(model.id) || model.ownedBy?.contains("xai", ignoreCase = true) == true
    }

    fun belongsToProvider(model: GabClient.ModelInfo, provider: ModelProvider): Boolean =
        when (provider) {
            // Exclude local artifacts so a *.gguf name with "grok" never lands in the cloud Grok list.
            ModelProvider.GROK, ModelProvider.GROK_BUILD -> isGrok(model)
            ModelProvider.GAB_AI -> !isGrok(model) && !isLocalLLM(model.id) &&
                !model.ownedBy.equals("localllm", ignoreCase = true)
            ModelProvider.LOCAL_LLM -> isLocalLLM(model.id) ||
                model.ownedBy.equals("localllm", ignoreCase = true)
        }

    fun filterForProvider(models: List<GabClient.ModelInfo>, provider: ModelProvider): List<GabClient.ModelInfo> =
        models.filter { belongsToProvider(it, provider) }

    fun fallbackAsModelInfo(provider: ModelProvider): List<GabClient.ModelInfo> =
        fallbackModelIds(provider).map { id ->
            GabClient.ModelInfo(
                id = id,
                ownedBy = when (provider) {
                    ModelProvider.GROK, ModelProvider.GROK_BUILD -> "xai"
                    ModelProvider.LOCAL_LLM -> "localllm"
                    ModelProvider.GAB_AI -> null
                },
                contextWindow = when (provider) {
                    ModelProvider.LOCAL_LLM -> LOCAL_LLM_DEFAULT_CONTEXT
                    ModelProvider.GROK_BUILD -> 500_000
                    else -> null
                }
            )
        }

    fun sortForDisplay(models: List<GabClient.ModelInfo>, provider: ModelProvider): List<GabClient.ModelInfo> {
        val filtered = filterForProvider(models, provider)
        if (filtered.isEmpty()) return fallbackAsModelInfo(provider)
        val order = preferredOrder(provider).withIndex().associate { (index, id) -> id to index }
        return filtered.sortedWith(
            compareBy<GabClient.ModelInfo>(
                { model -> order[model.id] ?: 100 },
                { it.id.lowercase() }
            )
        )
    }

    fun resolveSelection(
        models: List<GabClient.ModelInfo>,
        lastUsed: String,
        configuredDefault: String,
        provider: ModelProvider
    ): String {
        val ids = models.map { it.id }
        val defaultId = defaultModelId(provider)
        return when {
            lastUsed in ids -> lastUsed
            configuredDefault in ids -> configuredDefault
            defaultId in ids -> defaultId
            else -> models.firstOrNull()?.id ?: defaultId
        }
    }

    private fun preferredOrder(provider: ModelProvider): List<String> = when (provider) {
        ModelProvider.GROK -> GROK_PREFERRED_ORDER
        ModelProvider.GROK_BUILD -> GROK_BUILD_PREFERRED_ORDER
        ModelProvider.GAB_AI -> GAB_PREFERRED_ORDER
        ModelProvider.LOCAL_LLM -> LOCAL_LLM_FALLBACK_MODEL_IDS
    }
}
