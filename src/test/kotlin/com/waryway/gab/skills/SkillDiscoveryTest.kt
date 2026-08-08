package com.waryway.gab.skills

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkillDiscoveryTest {

    @Test
    fun `discoverFromDir returns empty for missing root`() {
        val missing = Path.of("C:/definitely-not-a-real-skills-root-xyz")
        assertTrue(SkillDiscovery.discoverFromDir(missing, SkillSource.USER).isEmpty())
        assertTrue(SkillDiscovery.discoverFromDir(null, SkillSource.PROJECT).isEmpty())
    }

    @Test
    fun `discoverFromDir loads SKILL md with frontmatter`() {
        val root = Files.createTempDirectory("gab-skills-user")
        try {
            writeSkill(
                root,
                "ceo",
                """
                ---
                name: ceo
                description: >
                  CEO orchestrator for multi-phase delivery.
                ---

                # CEO Skill

                You are the CEO. Spawn Director.
                """.trimIndent()
            )
            val found = SkillDiscovery.discoverFromDir(root, SkillSource.USER)
            assertEquals(1, found.size)
            val ceo = found.single()
            assertEquals("ceo", ceo.id)
            assertEquals("ceo", ceo.name)
            assertEquals(SkillSource.USER, ceo.source)
            assertNotNull(ceo.path)
            assertTrue(ceo.path!!.endsWith("SKILL.md") || ceo.path!!.endsWith("SKILL.md".replace('/', '\\')))
            assertContains(ceo.description, "CEO orchestrator")
            assertContains(ceo.bodyOrRails, "You are the CEO")
            assertContains(ceo.bodyOrRails, "# CEO Skill")
            // Must not collapse body to keywords
            assertTrue(ceo.bodyOrRails.length > 20)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `parseSkillMdContent without frontmatter uses id as name`() {
        val ref = SkillDiscovery.parseSkillMdContent(
            "# Hello\n\nBody text only.",
            SkillSource.PROJECT,
            id = "local-skill"
        )
        assertNotNull(ref)
        assertEquals("local-skill", ref!!.id)
        assertEquals("local-skill", ref.name)
        assertContains(ref.bodyOrRails, "Body text only")
    }

    @Test
    fun `projectSkillsRoot builds path`() {
        val p = SkillDiscovery.projectSkillsRoot("C:/repo")
        assertNotNull(p)
        assertTrue(p!!.toString().replace('\\', '/').endsWith(".grok/skills") ||
            p.toString().endsWith(".grok\\skills"))
        assertNull(SkillDiscovery.projectSkillsRoot(null))
        assertNull(SkillDiscovery.projectSkillsRoot("  "))
    }

    @Test
    fun `merge project overrides user overrides bundled`() {
        val bundled = listOf(
            SkillRef(id = "explain", name = "Explain code", source = SkillSource.BUNDLED, bodyOrRails = "bundled-rails"),
            SkillRef(id = "none", name = "(free chat)", source = SkillSource.BUNDLED, bodyOrRails = ""),
            SkillRef(id = "only-bundled", name = "Only bundled", source = SkillSource.BUNDLED, bodyOrRails = "b")
        )
        val user = listOf(
            SkillRef(id = "explain", name = "User explain", source = SkillSource.USER, bodyOrRails = "user-body"),
            SkillRef(id = "ceo", name = "ceo", source = SkillSource.USER, bodyOrRails = "user-ceo")
        )
        val project = listOf(
            SkillRef(id = "ceo", name = "project-ceo", source = SkillSource.PROJECT, bodyOrRails = "project-ceo"),
            SkillRef(id = "proj-only", name = "proj", source = SkillSource.PROJECT, bodyOrRails = "p")
        )

        val merged = SkillCatalog.merge(bundled, user, project)
        val byId = merged.associateBy { it.id }

        // none first
        assertEquals("none", merged.first().id)

        // project wins for ceo
        assertEquals(SkillSource.PROJECT, byId["ceo"]!!.source)
        assertEquals("project-ceo", byId["ceo"]!!.name)

        // user wins for explain over bundled
        assertEquals(SkillSource.USER, byId["explain"]!!.source)
        assertEquals("User explain", byId["explain"]!!.name)

        // unique ids kept
        assertNotNull(byId["only-bundled"])
        assertNotNull(byId["proj-only"])
        assertEquals(5, merged.size)
    }

    @Test
    fun `discover merges temp user and project with bundled`() {
        val userRoot = Files.createTempDirectory("gab-skills-user2")
        val projectRoot = Files.createTempDirectory("gab-skills-proj")
        try {
            writeSkill(
                userRoot,
                "ceo",
                """
                ---
                name: ceo
                description: user ceo
                ---
                User CEO body with full instructions.
                """.trimIndent()
            )
            writeSkill(
                projectRoot,
                "ceo",
                """
                ---
                name: project-ceo
                description: project wins
                ---
                Project CEO body overrides user.
                """.trimIndent()
            )
            writeSkill(
                projectRoot,
                "ship",
                """
                ---
                name: ship
                ---
                Ship it carefully.
                """.trimIndent()
            )

            val catalog = SkillCatalog.discover(
                projectBasePath = null,
                userSkillsRoot = userRoot,
                projectSkillsRoot = projectRoot,
                bundled = SkillRegistry.all
            )

            val byId = catalog.associateBy { it.id }
            assertEquals(SkillSource.PROJECT, byId["ceo"]!!.source)
            assertEquals("project-ceo", byId["ceo"]!!.name)
            assertContains(byId["ceo"]!!.bodyOrRails, "Project CEO body")
            assertEquals(SkillSource.PROJECT, byId["ship"]!!.source)
            // Bundled guided skills still present
            assertNotNull(byId["explain"])
            assertEquals(SkillSource.BUNDLED, byId["explain"]!!.source)
            assertNotNull(byId["none"])
            assertEquals(SkillSource.BUNDLED, byId["none"]!!.source)
        } finally {
            userRoot.toFile().deleteRecursively()
            projectRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `apply fs skill emits marker path body and user text`() {
        val skill = SkillRef(
            id = "ceo",
            name = "ceo",
            source = SkillSource.USER,
            path = "C:/Users/me/.grok/skills/ceo/SKILL.md",
            description = "CEO orchestrator",
            bodyOrRails = "# CEO Skill\n\nYou are the CEO. Full rails and workflow body."
        )
        val out = SkillCatalog.apply(skill, "run the org for oauth")
        assertTrue(out.startsWith("[skill:ceo]"))
        assertContains(out, "C:/Users/me/.grok/skills/ceo/SKILL.md")
        assertContains(out, "You are the CEO")
        assertContains(out, "Full rails and workflow body")
        assertContains(out, "run the org for oauth")
        // Not keyword-only
        assertTrue(out.length > 40)
    }

    @Test
    fun `apply bundled skill emits marker rails and template`() {
        val guided = SkillRegistry.find("explain")!!
        val ref = SkillCatalog.fromGuided(guided)
        val out = SkillCatalog.apply(ref, "main.kt")
        assertTrue(out.startsWith("[skill:explain]"))
        assertContains(out, "Plain language")
        assertContains(out, "Explain this code or area:")
        assertContains(out, "main.kt")
    }

    @Test
    fun `apply none and null return user input only`() {
        val none = SkillCatalog.fromGuided(SkillRegistry.all.first { it.id == "none" })
        assertEquals("hello world", SkillCatalog.apply(none, "hello world"))
        assertEquals("hello world", SkillCatalog.apply(null, "hello world"))
    }

    @Test
    fun `apply truncates huge fs body`() {
        val huge = "X".repeat(20_000)
        val skill = SkillRef(
            id = "big",
            name = "big",
            source = SkillSource.USER,
            path = "/tmp/big/SKILL.md",
            bodyOrRails = huge
        )
        val out = SkillCatalog.apply(skill, "goal", maxBodyChars = 100)
        assertContains(out, "[skill:big]")
        assertContains(out, "...(skill body truncated)")
        assertContains(out, "goal")
        assertTrue(out.length < 500)
    }

    @Test
    fun `skillMarker format is stable for HeuristicPlan`() {
        assertEquals("[skill:ceo]", SkillCatalog.skillMarker("ceo"))
        assertEquals("[skill:explain]", SkillCatalog.skillMarker(" explain "))
    }

    @Test
    fun `SkillRegistry apply still works for bundled guided skills`() {
        val skill = SkillRegistry.find("tests")!!
        val out = SkillRegistry.apply(skill, "FooService")
        assertContains(out, "Name the test file")
        assertContains(out, "Write tests for:")
        assertContains(out, "FooService")
    }

    private fun writeSkill(root: Path, id: String, content: String) {
        val dir = root.resolve(id)
        dir.createDirectories()
        dir.resolve("SKILL.md").writeText(content)
    }
}
