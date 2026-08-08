package com.waryway.gab.ui

import com.waryway.gab.skills.SkillRef
import com.waryway.gab.skills.SkillSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SkillSlashQueryTest {

    private val sample = listOf(
        SkillRef(id = "none", name = "(free chat)", source = SkillSource.BUNDLED),
        SkillRef(id = "explain", name = "Explain code", source = SkillSource.BUNDLED),
        SkillRef(id = "ceo", name = "ceo", source = SkillSource.USER, path = "/tmp/ceo/SKILL.md"),
        SkillRef(id = "tests", name = "Write tests", source = SkillSource.BUNDLED),
        SkillRef(id = "implement", name = "Implement feature", source = SkillSource.BUNDLED)
    )

    @Test
    fun `findActiveSlash after typing slash only`() {
        val slash = SkillSlashQuery.findActiveSlash("/", 1)
        assertEquals(0, slash!!.startOffset)
        assertEquals(1, slash.endOffset)
        assertEquals("", slash.query)
    }

    @Test
    fun `findActiveSlash query progress`() {
        val slash = SkillSlashQuery.findActiveSlash("/ceo", 4)
        assertEquals(0, slash!!.startOffset)
        assertEquals(4, slash.endOffset)
        assertEquals("ceo", slash.query)
    }

    @Test
    fun `findActiveSlash mid query`() {
        val slash = SkillSlashQuery.findActiveSlash("/explain", 3) // after "/ex"
        assertEquals("explain", slash!!.query)
        assertEquals(0, slash.startOffset)
    }

    @Test
    fun `findActiveSlash after whitespace`() {
        val text = "hello /ex"
        val slash = SkillSlashQuery.findActiveSlash(text, text.length)
        assertEquals(6, slash!!.startOffset)
        assertEquals("ex", slash.query)
    }

    @Test
    fun `findActiveSlash after newline`() {
        val text = "hello\n/ceo"
        val slash = SkillSlashQuery.findActiveSlash(text, text.length)
        assertEquals(6, slash!!.startOffset)
        assertEquals("ceo", slash.query)
    }

    @Test
    fun `findActiveSlash rejects path-like slash`() {
        assertNull(SkillSlashQuery.findActiveSlash("src/main", 8))
        assertNull(SkillSlashQuery.findActiveSlash("a/b", 3))
    }

    @Test
    fun `findActiveSlash null when caret before slash or on space`() {
        assertNull(SkillSlashQuery.findActiveSlash("/ceo", 0))
        assertNull(SkillSlashQuery.findActiveSlash("hi /ceo ", 8))
        assertNull(SkillSlashQuery.findActiveSlash("", 0))
    }

    @Test
    fun `filterSkills empty query returns all`() {
        assertEquals(sample.size, SkillSlashQuery.filterSkills(sample, "").size)
        assertEquals(sample.size, SkillSlashQuery.filterSkills(sample, "  ").size)
    }

    @Test
    fun `filterSkills by id prefix`() {
        val hit = SkillSlashQuery.filterSkills(sample, "ce")
        assertEquals(listOf("ceo"), hit.map { it.id })
    }

    @Test
    fun `filterSkills by name contains`() {
        val hit = SkillSlashQuery.filterSkills(sample, "write")
        assertEquals(listOf("tests"), hit.map { it.id })
    }

    @Test
    fun `filterSkills ranks id prefix first`() {
        val skills = listOf(
            SkillRef(id = "x-explain", name = "Other", source = SkillSource.USER),
            SkillRef(id = "other", name = "explain stuff", source = SkillSource.USER),
            SkillRef(id = "explain", name = "Explain code", source = SkillSource.BUNDLED)
        )
        val hit = SkillSlashQuery.filterSkills(skills, "explain")
        assertEquals("explain", hit.first().id)
    }

    @Test
    fun `removeSlashToken strips token and keeps prompt`() {
        val text = "please /ceo help"
        val mid = SkillSlashQuery.ActiveSlash(7, 11, "ceo")
        val (out, caret) = SkillSlashQuery.removeSlashToken(text, mid)
        assertEquals("please help", out)
        assertEquals(7, caret)
        assertTrue(out.contains("help"))
    }

    @Test
    fun `listLabel includes source badge`() {
        val label = SkillSlashQuery.listLabel(sample.first { it.id == "ceo" })
        assertTrue(label.contains("ceo"))
        assertTrue(label.contains("user"))
    }

    @Test
    fun `comboLabel for bundled uses name`() {
        assertEquals("Explain code", SkillSlashQuery.comboLabel(sample.first { it.id == "explain" }))
    }
}
