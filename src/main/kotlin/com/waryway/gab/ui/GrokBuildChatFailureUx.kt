package com.waryway.gab.ui

import com.waryway.gab.client.GabClient
import com.waryway.gab.model.ModelProvider

/**
 * Chat-completions failure formatting for the tool-window Send path.
 *
 * **Not** a second string catalog. Thin provider-aware facade:
 * - [ModelProvider.GROK_BUILD] → [GrokBuildSendUx.formatFailure]
 *   (auth via [com.waryway.gab.client.GrokBuildAuthRecovery], network, body snippet)
 * - [ModelProvider.LOCAL_LLM] → [LocalLlmSendUx.formatFailure] (must not be stolen by
 *   callers that already branch on Local; available for single-entry dispatch)
 * - other cloud providers → generic `Error: …` (optional body detail when present)
 *
 * Prefer this or [GrokBuildSendUx.formatFailure] at chat catch sites so operators
 * see recovery-oriented copy instead of bare exception text.
 */
object GrokBuildChatFailureUx {

    /**
     * Map a chat-path failure to operator-facing text for [provider].
     *
     * @param error throwable from GabClient / network stack (may be null)
     * @param provider active model provider for this Send
     * @param rootUrl Local LLM root when [provider] is [ModelProvider.LOCAL_LLM]
     * @param proxyRoot Grok Build proxy root when [provider] is [ModelProvider.GROK_BUILD]
     */
    fun formatChatFailure(
        error: Throwable?,
        provider: ModelProvider,
        rootUrl: String = LocalLlmSendUx.DEFAULT_ROOT,
        proxyRoot: String = GrokBuildSendUx.DEFAULT_PROXY_ROOT
    ): String = when (provider) {
        ModelProvider.LOCAL_LLM ->
            LocalLlmSendUx.formatFailure(error, agentMode = false, rootUrl = rootUrl)
        ModelProvider.GROK_BUILD ->
            GrokBuildSendUx.formatFailure(error = error, proxyRoot = proxyRoot)
        else -> formatGenericCloudFailure(error)
    }

    /**
     * Generic non-Local, non–Grok-Build cloud chat failure (Gab AI / Grok API).
     * Keeps `Error:` prefix; includes short body when [GabClient.GabApiException] carries one.
     */
    fun formatGenericCloudFailure(error: Throwable?): String {
        val msg = error?.message?.trim().orEmpty()
        val cls = error?.javaClass?.simpleName.orEmpty()
        val base = msg.ifBlank { cls.ifBlank { "unknown failure" } }
        val body = (error as? GabClient.GabApiException)?.body
        val snippet = GrokBuildSendUx.shortBodyDetail(body)
        return if (snippet != null && !base.contains(snippet)) {
            "Error: $base ($snippet)"
        } else {
            "Error: $base"
        }
    }
}
