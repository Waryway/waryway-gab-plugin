package com.waryway.gab.skills

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * Pure filesystem skill discovery: scan each child dir for SKILL.md under a skills root
 * and parse YAML frontmatter.
 *
 * No Swing / IDE APIs - safe for unit tests with temp directories.
 */
object SkillDiscovery {

    const val SKILL_FILE_NAME: String = "SKILL.md"
    const val SKILLS_DIR_NAME: String = "skills"
    const val GROK_DIR_NAME: String = ".grok"

    /**
     * Default user skills root: USERPROFILE/.grok/skills on Windows,
     * ~/.grok/skills elsewhere (user.home / .grok / skills).
     */
    fun defaultUserSkillsRoot(): Path =
        Paths.get(System.getProperty("user.home"), GROK_DIR_NAME, SKILLS_DIR_NAME)

    /** `{projectBasePath}/.grok/skills`, or null when [projectBasePath] is blank. */
    fun projectSkillsRoot(projectBasePath: String?): Path? {
        val base = projectBasePath?.trim().orEmpty()
        if (base.isEmpty()) return null
        return Paths.get(base, GROK_DIR_NAME, SKILLS_DIR_NAME)
    }

    /**
     * Discover all skills under [root] (each immediate child directory with a SKILL.md).
     * Missing or non-directory [root] returns an empty list (no-op).
     */
    fun discoverFromDir(root: Path?, source: SkillSource): List<SkillRef> {
        if (root == null || !root.exists() || !root.isDirectory()) return emptyList()
        return try {
            root.listDirectoryEntries()
                .asSequence()
                .filter { it.isDirectory() }
                .mapNotNull { dir -> loadSkillDir(dir, source) }
                .sortedBy { it.id.lowercase() }
                .toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Load a single skill from a directory that contains SKILL.md.
     * [id] defaults to the directory name.
     */
    fun loadSkillDir(dir: Path, source: SkillSource, id: String = dir.name): SkillRef? {
        val skillFile = dir.resolve(SKILL_FILE_NAME)
        if (!skillFile.isRegularFile()) return null
        return parseSkillMd(skillFile, source, id)
    }

    /**
     * Parse a SKILL.md file into a [SkillRef].
     * Frontmatter keys used: `name`, `description` (optional). Body is everything after frontmatter.
     */
    fun parseSkillMd(file: Path, source: SkillSource, id: String = file.parent?.name ?: file.name): SkillRef? {
        return try {
            val text = file.readText(StandardCharsets.UTF_8)
            parseSkillMdContent(text, source, id, path = file.toAbsolutePath().normalize().toString())
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Parse SKILL.md content (testable without touching disk).
     */
    fun parseSkillMdContent(
        content: String,
        source: SkillSource,
        id: String,
        path: String? = null
    ): SkillRef? {
        val trimmedId = id.trim()
        if (trimmedId.isEmpty()) return null

        val (front, body) = splitFrontmatter(content)
        val name = front["name"]?.takeIf { it.isNotBlank() } ?: trimmedId
        val description = front["description"]?.trim().orEmpty()

        return SkillRef(
            id = trimmedId,
            name = name.trim(),
            source = source,
            path = path,
            description = description,
            bodyOrRails = body.trim(),
            template = null,
            category = "fs",
            hint = description.lineSequence().firstOrNull()?.trim().orEmpty()
        )
    }

    /**
     * Split YAML-ish frontmatter between leading `---` fences from the markdown body.
     * Supports simple `key: value` and folded `key: >` multi-line blocks (common in org skills).
     */
    fun splitFrontmatter(content: String): Pair<Map<String, String>, String> {
        val text = content.replace("\r\n", "\n").replace('\r', '\n')
        if (!text.startsWith("---")) {
            return emptyMap<String, String>() to text
        }
        val afterOpen = text.removePrefix("---").removePrefix("\n")
        val closeIdx = afterOpen.indexOf("\n---")
        if (closeIdx < 0) {
            return emptyMap<String, String>() to text
        }
        val yamlBlock = afterOpen.substring(0, closeIdx)
        val body = afterOpen.substring(closeIdx + "\n---".length).removePrefix("\n")
        return parseSimpleYaml(yamlBlock) to body
    }

    /**
     * Minimal frontmatter parser for skill metadata (not a full YAML engine).
     */
    internal fun parseSimpleYaml(yaml: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val lines = yaml.replace("\r\n", "\n").replace('\r', '\n').lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                i++
                continue
            }
            val colon = line.indexOf(':')
            if (colon <= 0) {
                i++
                continue
            }
            val key = line.substring(0, colon).trim()
            if (key.isEmpty() || key.any { it.isWhitespace() }) {
                i++
                continue
            }
            var value = line.substring(colon + 1).trim()
            if (value == ">" || value == "|") {
                // Folded / literal block: following indented lines
                val buf = StringBuilder()
                i++
                while (i < lines.size) {
                    val next = lines[i]
                    if (next.isNotEmpty() && !next[0].isWhitespace() && next.trim().isNotEmpty() &&
                        !next.trimStart().startsWith("#")
                    ) {
                        // Next top-level key
                        break
                    }
                    if (buf.isNotEmpty()) buf.append('\n')
                    buf.append(next.trim())
                    i++
                }
                value = buf.toString().trim()
                result[key] = value
                continue
            }
            // Strip surrounding quotes
            if ((value.startsWith("\"") && value.endsWith("\"")) ||
                (value.startsWith("'") && value.endsWith("'"))
            ) {
                value = value.substring(1, value.length - 1)
            }
            result[key] = value
            i++
        }
        return result
    }
}
