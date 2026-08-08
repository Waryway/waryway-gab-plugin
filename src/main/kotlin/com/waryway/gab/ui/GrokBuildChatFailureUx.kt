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
        else -> formatGenericCloudFailure(error, provider)
    }

    /**
     * Generic non-Local, non–Grok-Build cloud chat failure (Gab AI / Grok API).
     * Timeout → [AgentTimeoutUx]; else `Error:` with optional body snippet.
     */
    fun formatGenericCloudFailure(
        error: Throwable?,
        provider: ModelProvider = ModelProvider.GAB_AI
    ): String {
        val msg = error?.message?.trim().orEmpty()
        val cls = error?.javaClass?.simpleName.orEmpty()
        val combined = listOf(cls, msg).filter { it.isNotBlank() }.joinToString(" ")
        val apiEx = error as? GabClient.GabApiException
        if (AgentTimeoutUx.isTimeoutError(error) || AgentTimeoutUx.isTimeoutMessage(msg) ||
            AgentTimeoutUx.isTimeoutMessage(combined) ||
            apiEx?.kind == GabClient.GabApiException.Kind.TIMEOUT
        ) {
            val cloud = when (provider) {
                ModelProvider.GROK -> ModelProvider.GROK
                else -> ModelProvider.GAB_AI
            }
            return AgentTimeoutUx.formatTimeoutFailureWithPartial(
                provider = cloud,
                timeoutSeconds = AgentTimeoutUx.extractTimeoutSeconds(msg),
                detail = msg.takeIf { it.isNotBlank() && it.length <= 120 },
                partialContent = apiEx?.partialContent
            )
        }
        val base = msg.ifBlank { cls.ifBlank { "unknown failure" } }
        val body = apiEx?.body
        val snippet = GrokBuildSendUx.shortBodyDetail(body)
        val errorLine = if (snippet != null && !base.contains(snippet)) {
            "Error: $base ($snippet)"
        } else {
            "Error: $base"
        }
        val partial = apiEx?.partialContent?.trim().orEmpty()
        return if (partial.isNotEmpty()) {
            AgentTimeoutUx.mergePartialWithTimeout(partial, errorLine)
                .replace("— Timed out (partial reply kept) —", "— Interrupted (partial reply kept) —")
        } else {
            errorLine
        }
    }
}
