package com.waryway.gab.skills

import com.waryway.gab.chat.LocalLlmAgentSession
import com.waryway.gab.ui.AttachmentPayload
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Send-path injection helpers: Frank on user text only, then SkillCatalog apply,
 * then payload/goal retain [skill:…] + body (no Swing).
 */
class SkillSendInjectionTest {

    @Test
    fun `prepare free chat leaves user text without skill marker`() {
        val skills = SkillCatalog.discover(projectBasePath = null, userSkillsRoot = null, projectSkillsRoot = null)
        val prepared = SkillSendInjection.prepare(
            rawComposerText = "how do I commit local changes",
            skillId = "none",
            skills = skills
        )
        // Frank may strip articles ("a"/"the") but keeps substance; no skill marker in free chat
        assertTrue(prepared.normalizedUser.contains("commit"))
        assertEquals(prepared.normalizedUser, prepared.outboundText)
        assertFalse(prepared.outboundText.contains("[skill:"))
        assertEquals("none", prepared.skillId)
        assertNull(prepared.displaySkillName)
        assertFalse(prepared.hasSkill)
    }

    @Test
    fun `prepare bundled skill injects marker rails template into outbound`() {
        val skills = listOf(SkillCatalog.fromGuided(SkillRegistry.find("explain")!!))
        val prepared = SkillSendInjection.prepare(
            rawComposerText = "  main.kt  ",
            skillId = "explain",
            skills = skills
        )
        assertEquals("main.kt", prepared.normalizedUser)
        assertTrue(prepared.outboundText.startsWith("[skill:explain]"))
        assertTrue(prepared.outboundText.contains("Plain language"))
        assertTrue(prepared.outboundText.contains("Explain this code or area:"))
        assertTrue(prepared.outboundText.contains("main.kt"))
        assertEquals("Explain code", prepared.displaySkillName)
        assertEquals("explain", prepared.skillId)
        assertTrue(prepared.hasSkill)
    }

    @Test
    fun `prepare fs skill injects marker path body and user - not keywords only`() {
        val skill = SkillRef(
            id = "ceo",
            name = "ceo",
            source = SkillSource.USER,
            path = "C:/Users/me/.grok/skills/ceo/SKILL.md",
            description = "CEO orchestrator",
            bodyOrRails = "# CEO Skill\n\nYou are the CEO. Full rails and workflow body for org pipeline."
        )
        val prepared = SkillSendInjection.prepare(
            rawComposerText = "I think run the org for oauth",
            skillId = "ceo",
            skills = listOf(skill)
        )
        // Frank strips "I think"
        assertFalse(prepared.normalizedUser.contains("I think", ignoreCase = true))
        assertTrue(prepared.outboundText.startsWith("[skill:ceo]"))
        assertTrue(prepared.outboundText.contains("C:/Users/me/.grok/skills/ceo/SKILL.md"))
        assertTrue(prepared.outboundText.contains("You are the CEO"))
        assertTrue(prepared.outboundText.contains("Full rails and workflow body"))
        assertTrue(prepared.outboundText.contains(prepared.normalizedUser))
        // Body must not be reduced to bare keywords
        assertTrue(prepared.outboundText.length > 60)
    }

    @Test
    fun `Frank does not strip skill body when fillers appear only in rails`() {
        // Skill body intentionally contains filler words that Frank would strip from user text.
        val skill = SkillRef(
            id = "rails-test",
            name = "rails-test",
            source = SkillSource.USER,
            path = "/tmp/rails-test/SKILL.md",
            bodyOrRails = "Basically you should actually just really follow these rails carefully."
        )
        val prepared = SkillSendInjection.prepare(
            rawComposerText = "run oauth flow",
            skillId = "rails-test",
            skills = listOf(skill)
        )
        // Body preserved with filler words intact (Frank only touched user text)
        assertTrue(prepared.outboundText.contains("Basically you should actually just really follow these rails carefully."))
        assertTrue(prepared.outboundText.contains("[skill:rails-test]"))
        assertTrue(prepared.outboundText.contains(prepared.normalizedUser))
        assertTrue(prepared.normalizedUser.contains("oauth"))
    }

    @Test
    fun `prepare empty after Frank yields isEmpty`() {
        val skills = emptyList<SkillRef>()
        val prepared = SkillSendInjection.prepare(
            rawComposerText = "   just basically actually   ",
            skillId = null,
            skills = skills
        )
        // All fillers/articles — may be empty or near-empty depending on residual tokens
        // "just basically actually" all match filler patterns → empty
        assertTrue(prepared.isEmpty)
        assertEquals("", prepared.outboundText)
    }

    @Test
    fun `displayText is short and does not embed skill body`() {
        val skill = SkillRef(
            id = "ceo",
            name = "ceo",
            source = SkillSource.USER,
            path = "/x/SKILL.md",
            bodyOrRails = "LONG BODY THAT MUST NOT APPEAR IN DISPLAY"
        )
        val prepared = SkillSendInjection.prepare("run oauth", "ceo", listOf(skill))
        val display = SkillSendInjection.displayText(prepared, "run oauth")
        assertTrue(display.startsWith("[ceo] "))
        assertTrue(display.contains("run oauth"))
        assertFalse(display.contains("LONG BODY"))
        assertFalse(display.contains("[skill:ceo]"))
    }

    @Test
    fun `prepareWithDiscovery loads user skill and injects`() {
        val userRoot = Files.createTempDirectory("gab-send-user-skills")
        try {
            writeSkill(
                userRoot,
                "director",
                """
                ---
                name: director
                description: Director role
                ---
                # Director
                Full director body for planning.
                """.trimIndent()
            )
            val prepared = SkillSendInjection.prepareWithDiscovery(
                rawComposerText = "plan section two",
                skillId = "director",
                projectBasePath = null,
                userSkillsRoot = userRoot,
                projectSkillsRoot = null
            )
            assertTrue(prepared.hasSkill)
            assertTrue(prepared.outboundText.startsWith("[skill:director]"))
            assertTrue(prepared.outboundText.contains("Full director body"))
            assertTrue(prepared.outboundText.contains("plan section two"))
        } finally {
            userRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `chat payload retains skill marker and body after AttachmentPayload`() {
        val skill = SkillCatalog.fromGuided(SkillRegistry.find("tests")!!)
        val prepared = SkillSendInjection.prepare("FooService", "tests", listOf(skill))
        val payload = AttachmentPayload.buildMessagePayload(prepared.outboundText, emptyList())
        assertTrue(payload.startsWith("[skill:tests]"))
        assertTrue(payload.contains("Name the test file"))
        assertTrue(payload.contains("FooService"))
    }

    @Test
    fun `LocalLLM agent goal retains skill context via buildGoalWithAttachments`() {
        val skill = SkillRef(
            id = "ceo",
            name = "ceo",
            source = SkillSource.USER,
            path = "C:/skills/ceo/SKILL.md",
            bodyOrRails = "You are the CEO. Orchestrate workers. Do not keyword-search aimlessly."
        )
        val prepared = SkillSendInjection.prepare(
            "how do I commit local changes",
            "ceo",
            listOf(skill)
        )
        val payload = AttachmentPayload.buildMessagePayload(
            prepared.outboundText,
            listOf("AGENTS.md" to "# AGENTS\nUse scripts/push-and-pr.sh")
        )
        val goal = LocalLlmAgentSession.buildGoalWithAttachments(
            payload,
            listOf("AGENTS.md", "scripts/push-and-pr.sh")
        )
        // Marker + body survive goal build (not stripped to 3 search keywords)
        assertTrue(goal.contains("[skill:ceo]"))
        assertTrue(goal.contains("You are the CEO"))
        assertTrue(goal.contains("Orchestrate workers"))
        assertTrue(goal.contains(prepared.normalizedUser) || goal.contains("commit"))
        assertTrue(goal.contains("Attached paths (prefer read_file / workspace-relative):"))
        assertTrue(goal.contains("\n- AGENTS.md"))
        // Workspace context from payload still present
        assertTrue(goal.contains("--- Workspace context ---"))
        assertNotNull(prepared.skill)
    }

    @Test
    fun `missing skill id falls back to free chat outbound`() {
        val skills = listOf(SkillCatalog.fromGuided(SkillRegistry.find("explain")!!))
        val prepared = SkillSendInjection.prepare("hello world", "does-not-exist", skills)
        assertEquals("none", prepared.skillId)
        assertEquals("hello world", prepared.outboundText)
        assertFalse(prepared.outboundText.contains("[skill:"))
    }

    private fun writeSkill(root: java.nio.file.Path, id: String, content: String) {
        val dir = root.resolve(id)
        dir.createDirectories()
        dir.resolve("SKILL.md").writeText(content)
    }
}
