package com.waryway.gab.model

import com.waryway.gab.client.GabClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelCatalogTest {

    @Test
    fun `grok fallbacks list grok models first`() {
        assertEquals("grok-4.3", ModelCatalog.GROK_FALLBACK_MODEL_IDS.first())
        assertTrue(ModelCatalog.GROK_FALLBACK_MODEL_IDS.contains("grok-4.2"))
        assertTrue(ModelCatalog.GROK_FALLBACK_MODEL_IDS.contains("grok-4"))
    }

    @Test
    fun `gab fallbacks exclude grok`() {
        assertFalse(ModelCatalog.GAB_FALLBACK_MODEL_IDS.any { ModelCatalog.isGrok(it) })
        assertEquals("gpt-5", ModelCatalog.GAB_FALLBACK_MODEL_IDS.first())
    }

    @Test
    fun `sortForDisplay puts grok models first for grok provider`() {
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
        assertEquals("grok-4.3", sorted.first().id)
        assertTrue(sorted.indexOfFirst { it.id == "grok-4" } < sorted.indexOfFirst { it.id == "gpt-5" })
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
    fun `resolveSelection prefers provider default`() {
        val models = ModelCatalog.fallbackAsModelInfo(ModelProvider.GROK)
        val selected = ModelCatalog.resolveSelection(models, "missing", "also-missing", ModelProvider.GROK)
        assertEquals(ModelCatalog.GROK_DEFAULT_MODEL_ID, selected)
    }
}