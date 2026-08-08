package com.waryway.gab.model

import com.waryway.gab.client.GabClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ModelCatalogTest {

    @Test
    fun `grok default remains grok-4_3`() {
        assertEquals("grok-4.3", ModelCatalog.GROK_DEFAULT_MODEL_ID)
        assertEquals(ModelCatalog.GROK_DEFAULT_MODEL_ID, ModelCatalog.defaultModelId(ModelProvider.GROK))
    }

    @Test
    fun `grok build default is grok-4_5`() {
        assertEquals("grok-4.5", ModelCatalog.GROK_BUILD_DEFAULT_MODEL_ID)
        assertEquals(
            ModelCatalog.GROK_BUILD_DEFAULT_MODEL_ID,
            ModelCatalog.defaultModelId(ModelProvider.GROK_BUILD)
        )
        assertEquals(
            listOf("grok-4.5", "grok-build", "grok-composer-2.5-fast"),
            ModelCatalog.fallbackModelIds(ModelProvider.GROK_BUILD)
        )
    }

    @Test
    fun `grok fallbacks list grok models first in product order`() {
        assertEquals(
            listOf("grok-4.3", "grok-4.2", "grok-4", "grok-3", "grok-build-0.1"),
            ModelCatalog.GROK_FALLBACK_MODEL_IDS
        )
        assertEquals(ModelCatalog.GROK_FALLBACK_MODEL_IDS, ModelCatalog.fallbackModelIds(ModelProvider.GROK))
        assertEquals("grok-4.3", ModelCatalog.GROK_FALLBACK_MODEL_IDS.first())
    }

    @Test
    fun `gab fallbacks exclude grok and local`() {
        assertFalse(ModelCatalog.GAB_FALLBACK_MODEL_IDS.any { ModelCatalog.isGrok(it) })
        assertFalse(ModelCatalog.GAB_FALLBACK_MODEL_IDS.any { ModelCatalog.isLocalLLM(it) })
        assertEquals("gpt-5", ModelCatalog.GAB_FALLBACK_MODEL_IDS.first())
    }

    @Test
    fun `sortForDisplay puts grok models first for grok provider and excludes gab`() {
        val sorted = ModelCatalog.sortForDisplay(
            listOf(
                GabClient.ModelInfo("arya"),
                GabClient.ModelInfo("grok-4.3"),
                GabClient.ModelInfo("grok-3"),
                GabClient.ModelInfo("gpt-5"),
                GabClient.ModelInfo("grok-4")
            ),
            ModelProvider.GROK
        )
        assertEquals(listOf("grok-4.3", "grok-4", "grok-3"), sorted.map { it.id })
        assertFalse(sorted.any { it.id == "gpt-5" || it.id == "arya" })
    }

    @Test
    fun `sortForDisplay excludes grok for gab provider`() {
        val sorted = ModelCatalog.sortForDisplay(
            listOf(
                GabClient.ModelInfo("grok-4.3"),
                GabClient.ModelInfo("grok-4"),
                GabClient.ModelInfo("gpt-5"),
                GabClient.ModelInfo("claude-sonnet-4.5")
            ),
            ModelProvider.GAB_AI
        )
        assertFalse(sorted.any { ModelCatalog.isGrok(it) })
        assertEquals("gpt-5", sorted.first().id)
    }

    @Test
    fun `local llm fallbacks use localllm prefix`() {
        assertEquals("localllm-coder", ModelCatalog.LOCAL_LLM_FALLBACK_MODEL_IDS.first())
        assertTrue(ModelCatalog.isLocalLLM("localllm-coder"))
        assertTrue(ModelCatalog.isLocalLLM("qwen2.5-coder-1.5b-instruct-q4_k_m.gguf"))
    }

    @Test
    fun `sortForDisplay keeps only local models for local provider`() {
        val sorted = ModelCatalog.sortForDisplay(
            listOf(
                GabClient.ModelInfo("grok-4.3"),
                GabClient.ModelInfo("localllm-coder", ownedBy = "localllm"),
                GabClient.ModelInfo("gpt-5")
            ),
            ModelProvider.LOCAL_LLM
        )
        assertEquals("localllm-coder", sorted.first().id)
        assertFalse(sorted.any { it.id == "grok-4.3" })
    }

    @Test
    fun `resolveSelection prefers last used then configured then provider default`() {
        val models = ModelCatalog.fallbackAsModelInfo(ModelProvider.GROK)
        assertEquals(
            "grok-4",
            ModelCatalog.resolveSelection(models, "grok-4", "grok-3", ModelProvider.GROK)
        )
        assertEquals(
            "grok-3",
            ModelCatalog.resolveSelection(models, "missing", "grok-3", ModelProvider.GROK)
        )
        assertEquals(
            ModelCatalog.GROK_DEFAULT_MODEL_ID,
            ModelCatalog.resolveSelection(models, "missing", "also-missing", ModelProvider.GROK)
        )
    }

    @Test
    fun `ownedBy xai counts as grok even without grok in id`() {
        val xaiOnly = GabClient.ModelInfo("frontier-code-1", ownedBy = "xai")
        assertTrue(ModelCatalog.isGrok(xaiOnly))
        assertTrue(ModelCatalog.belongsToProvider(xaiOnly, ModelProvider.GROK))
        assertFalse(ModelCatalog.belongsToProvider(xaiOnly, ModelProvider.GAB_AI))
        assertFalse(ModelCatalog.belongsToProvider(xaiOnly, ModelProvider.LOCAL_LLM))
    }

    @Test
    fun `filterForProvider isolates grok gab and local catalogs`() {
        val mixed = listOf(
            GabClient.ModelInfo("grok-4.3", ownedBy = "xai"),
            GabClient.ModelInfo("frontier-code-1", ownedBy = "xai"),
            GabClient.ModelInfo("gpt-5"),
            GabClient.ModelInfo("claude-sonnet-4.5"),
            GabClient.ModelInfo("localllm-coder", ownedBy = "localllm"),
            GabClient.ModelInfo("weights.gguf")
        )

        val grok = ModelCatalog.filterForProvider(mixed, ModelProvider.GROK)
        assertEquals(setOf("grok-4.3", "frontier-code-1"), grok.map { it.id }.toSet())
        // Regression: GROK filter must never surface Gab defaults (e.g. gpt-5).
        assertFalse(grok.any { it.id == "gpt-5" })
        assertFalse(grok.any { !ModelCatalog.isGrok(it) })

        val gab = ModelCatalog.filterForProvider(mixed, ModelProvider.GAB_AI)
        assertEquals(setOf("gpt-5", "claude-sonnet-4.5"), gab.map { it.id }.toSet())
        assertFalse(gab.any { ModelCatalog.isGrok(it) })

        val local = ModelCatalog.filterForProvider(mixed, ModelProvider.LOCAL_LLM)
        assertEquals(setOf("localllm-coder", "weights.gguf"), local.map { it.id }.toSet())
    }

    @Test
    fun `belongsToProvider and isGrok keep gab models out of grok defaults`() {
        assertFalse(ModelCatalog.isGrok("gpt-5"))
        assertFalse(ModelCatalog.isGrok("claude-sonnet-4.5"))
        assertTrue(ModelCatalog.isGrok("grok-4.3"))
        assertTrue(ModelCatalog.isGrok("grok-build-0.1"))

        val gabDefault = GabClient.ModelInfo("gpt-5")
        assertFalse(ModelCatalog.belongsToProvider(gabDefault, ModelProvider.GROK))
        assertTrue(ModelCatalog.belongsToProvider(gabDefault, ModelProvider.GAB_AI))

        val grokDefault = GabClient.ModelInfo(ModelCatalog.GROK_DEFAULT_MODEL_ID, ownedBy = "xai")
        assertTrue(ModelCatalog.belongsToProvider(grokDefault, ModelProvider.GROK))
        assertFalse(ModelCatalog.belongsToProvider(grokDefault, ModelProvider.GAB_AI))
    }

    @Test
    fun `local model with grok in filename is not classified as cloud grok`() {
        val localGrokNamed = GabClient.ModelInfo("grok-local-q4.gguf", ownedBy = "localllm")
        assertFalse(ModelCatalog.isGrok(localGrokNamed))
        assertFalse(ModelCatalog.belongsToProvider(localGrokNamed, ModelProvider.GROK))
        assertTrue(ModelCatalog.belongsToProvider(localGrokNamed, ModelProvider.LOCAL_LLM))
        assertFalse(ModelCatalog.belongsToProvider(localGrokNamed, ModelProvider.GAB_AI))
    }

    @Test
    fun `fallbackAsModelInfo tags grok with ownedBy xai`() {
        val fallbacks = ModelCatalog.fallbackAsModelInfo(ModelProvider.GROK)
        assertTrue(fallbacks.isNotEmpty())
        assertTrue(fallbacks.all { it.ownedBy == "xai" })
        assertEquals(ModelCatalog.GROK_DEFAULT_MODEL_ID, fallbacks.first().id)
        assertTrue(fallbacks.all { ModelCatalog.belongsToProvider(it, ModelProvider.GROK) })
    }

    @Test
    fun `sortForDisplay falls back when no provider models present`() {
        val sorted = ModelCatalog.sortForDisplay(
            listOf(GabClient.ModelInfo("gpt-5"), GabClient.ModelInfo("arya")),
            ModelProvider.GROK
        )
        assertEquals(ModelCatalog.GROK_FALLBACK_MODEL_IDS, sorted.map { it.id })
    }

    @Test
    fun `model provider grok identity is product accurate`() {
        val grok = ModelProvider.GROK
        assertEquals("Grok (API)", grok.displayName)
        assertEquals("https://api.x.ai/v1", grok.baseUrl)
        assertFalse(grok.supportsCredits)
        assertEquals("https://console.x.ai", grok.keyHelpUrl)

        val build = ModelProvider.GROK_BUILD
        assertEquals("Grok Build", build.displayName)
        assertEquals("https://cli-chat-proxy.grok.com/v1", build.baseUrl)
        assertFalse(build.supportsCredits)
        assertEquals("https://x.ai/cli", build.keyHelpUrl)

        assertNotEquals(ModelProvider.GAB_AI.baseUrl, grok.baseUrl)
        assertNotEquals(build.baseUrl, grok.baseUrl)
        assertEquals("https://gab.ai/v1", ModelProvider.GAB_AI.baseUrl)
        assertTrue(ModelProvider.GAB_AI.supportsCredits)
    }

    @Test
    fun `passwordsafe credential service names stay per provider`() {
        // Contract: WarywayGabSettings.credentialServiceName uses
        // generateServiceName("WarywayAgent", provider.name) — names must stay distinct.
        assertNotEquals(ModelProvider.GROK.name, ModelProvider.GAB_AI.name)
        assertNotEquals(ModelProvider.GROK.name, ModelProvider.LOCAL_LLM.name)
        assertNotEquals(ModelProvider.GROK.name, ModelProvider.GROK_BUILD.name)
        assertEquals("GROK", ModelProvider.GROK.name)
        assertEquals("GROK_BUILD", ModelProvider.GROK_BUILD.name)
        assertEquals("GAB_AI", ModelProvider.GAB_AI.name)
        assertEquals("LOCAL_LLM", ModelProvider.LOCAL_LLM.name)
        assertTrue(ModelProvider.selectable.contains(ModelProvider.GROK_BUILD))
    }
}
