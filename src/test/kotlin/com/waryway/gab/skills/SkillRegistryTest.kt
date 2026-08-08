package com.waryway.gab.skills

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkillRegistryTest {

    @Test
    fun `apply skill adds rails`() {
        val skill = SkillRegistry.find("explain")!!
        val out = SkillRegistry.apply(skill, "main.kt")
        assertContains(out, "main.kt")
        assertContains(out, "Plain language")
    }

    @Test
    fun `llm skills have presets`() {
        val grow = SkillRegistry.find("llm-grow")!!
        assertTrue(grow.localLlmPreset == "stack")
    }

    @Test
    fun `catalog fromGuided preserves id name rails template and preset`() {
        val grow = SkillRegistry.find("llm-grow")!!
        val ref = SkillCatalog.fromGuided(grow)
        assertEquals("llm-grow", ref.id)
        assertEquals(SkillSource.BUNDLED, ref.source)
        assertEquals(grow.rails, ref.bodyOrRails)
        assertEquals(grow.template, ref.template)
        assertEquals("stack", ref.localLlmPreset)

        val applied = SkillCatalog.apply(ref, "improve offline model")
        assertTrue(applied.startsWith("[skill:llm-grow]"))
        assertContains(applied, grow.rails)
        assertContains(applied, "improve offline model")
    }

    @Test
    fun `catalog discover with empty fs still includes all bundled`() {
        val catalog = SkillCatalog.discover(
            projectBasePath = null,
            userSkillsRoot = null,
            projectSkillsRoot = null,
            bundled = SkillRegistry.all
        )
        assertEquals(SkillRegistry.all.size, catalog.size)
        assertEquals("none", catalog.first().id)
        assertTrue(catalog.any { it.id == "implement" })
    }
}