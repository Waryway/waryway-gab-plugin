package com.waryway.gab.diagnostics

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Metadata for a fail / stop / stuck-loop dump that another agent can evaluate offline.
 */
data class FailPackageMeta(
    /** Short machine-ish reason: user_stop | agent_error | max_iterations | stream_error | export | … */
    val trigger: String,
    val provider: String = "",
    val model: String = "",
    val skillId: String = "",
    val pathLabel: String = "",
    val projectBase: String? = null,
    val lastUserQuestion: String = "",
    val lastAssistantAnswer: String = "",
    val chatTranscript: String = "",
    val extra: Map<String, String> = emptyMap(),
)

/**
 * Writes fail packages and session log files under `.waryway-gab/logs/`
 * (project base when available, else `~/.waryway-gab/logs`).
 */
object FailPackageExporter {

    private val stampFmt = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    private val isoFmt = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    fun logsRoot(projectBase: String?): Path {
        val base = projectBase?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { Paths.get(it, ".waryway-gab", "logs") }
            ?: Paths.get(System.getProperty("user.home"), ".waryway-gab", "logs")
        Files.createDirectories(base)
        return base
    }

    fun newSessionLogFile(projectBase: String?): Path {
        val stamp = LocalDateTime.now().format(stampFmt)
        return logsRoot(projectBase).resolve("session_$stamp.log")
    }

    /**
     * Write a Markdown fail package and return its absolute path.
     * Safe to call from background threads.
     */
    fun writeFailPackage(
        meta: FailPackageMeta,
        logLines: List<String>,
        sessionLogPath: Path? = null,
    ): Path {
        val root = logsRoot(meta.projectBase)
        val stamp = LocalDateTime.now().format(stampFmt)
        val safeTrigger = sanitizeFilename(meta.trigger).ifEmpty { "unknown" }
        val out = root.resolve("fail_${stamp}_$safeTrigger.md")
        Files.writeString(out, buildReport(meta, logLines, sessionLogPath, out))
        return out.toAbsolutePath().normalize()
    }

    fun buildReport(
        meta: FailPackageMeta,
        logLines: List<String>,
        sessionLogPath: Path?,
        packagePath: Path? = null,
    ): String = buildString {
        appendLine("# Waryway Gab fail package")
        appendLine()
        appendLine("Generated: ${LocalDateTime.now().format(isoFmt)}")
        appendLine()
        appendLine("## Paths")
        appendLine()
        packagePath?.let { appendLine("- **This package:** `${it.toAbsolutePath().normalize()}`") }
        sessionLogPath?.let { appendLine("- **Session log file:** `${it.toAbsolutePath().normalize()}`") }
        meta.projectBase?.takeIf { it.isNotBlank() }?.let {
            appendLine("- **Project base:** `$it`")
        }
        appendLine("- **Logs directory:** `${logsRoot(meta.projectBase).toAbsolutePath().normalize()}`")
        appendLine()
        appendLine("## Trigger")
        appendLine()
        appendLine("```")
        appendLine(meta.trigger.ifBlank { "(none)" })
        appendLine("```")
        appendLine()
        appendLine("## Context")
        appendLine()
        appendLine("| Field | Value |")
        appendLine("|-------|-------|")
        appendLine("| Provider | ${escCell(meta.provider)} |")
        appendLine("| Model | ${escCell(meta.model)} |")
        appendLine("| Skill | ${escCell(meta.skillId)} |")
        appendLine("| Path | ${escCell(meta.pathLabel)} |")
        for ((k, v) in meta.extra) {
            appendLine("| ${escCell(k)} | ${escCell(v)} |")
        }
        appendLine()
        appendLine("## Last user question")
        appendLine()
        appendLine("```")
        appendLine(meta.lastUserQuestion.ifBlank { "(empty)" }.take(8_000))
        appendLine("```")
        appendLine()
        appendLine("## Last assistant answer (snippet)")
        appendLine()
        appendLine("```")
        appendLine(meta.lastAssistantAnswer.ifBlank { "(empty)" }.take(12_000))
        appendLine("```")
        appendLine()
        if (meta.chatTranscript.isNotBlank()) {
            appendLine("## Chat transcript (recent)")
            appendLine()
            appendLine("```")
            appendLine(meta.chatTranscript.take(20_000))
            appendLine("```")
            appendLine()
        }
        appendLine("## Activity log (${logLines.size} lines)")
        appendLine()
        appendLine("```")
        if (logLines.isEmpty()) {
            appendLine("(empty)")
        } else {
            logLines.forEach { appendLine(it) }
        }
        appendLine("```")
        appendLine()
        appendLine("---")
        appendLine()
        appendLine("Hand this file (and the session log path above) to another agent to reproduce / diagnose.")
    }

    fun sanitizeFilename(raw: String): String =
        raw.trim()
            .replace(Regex("[^a-zA-Z0-9._-]+"), "_")
            .trim('_')
            .take(48)

    private fun escCell(s: String): String =
        s.replace('|', '/').replace('\n', ' ').take(200).ifBlank { "—" }
}
