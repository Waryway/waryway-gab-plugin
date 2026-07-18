package com.waryway.gab.ui

import java.nio.charset.Charset

/**
 * Pure helpers for file-attachment path display and model payload injection.
 * No Swing/Project deps — unit-testable from wo-03-04.
 *
 * Path separators are normalized to `/` for stable display and model context.
 */
object AttachmentPayload {

    /** Injected when preview content is null/blank so the model still gets an actionable path. */
    const val CONTENT_UNAVAILABLE =
        "Content unavailable (binary or unreadable). Agent should use read_file on this path."

    const val DEFAULT_PREVIEW_MAX_CHARS = 8000

    private const val BINARY_SAMPLE_BYTES = 8192

    /**
     * Prefer project-relative path when [relativeFromVfs] is non-blank or [absolutePath]
     * sits under [projectBasePath]. Outside the project: keep absolute path.
     * Never returns an empty string — falls back to [fallbackName] then `"unknown"`.
     *
     * Separators are normalized to `/`.
     */
    fun resolveAttachmentPath(
        absolutePath: String?,
        projectBasePath: String?,
        relativeFromVfs: String?,
        fallbackName: String
    ): String {
        val rel = relativeFromVfs?.trim()?.takeIf { it.isNotEmpty() }
        if (rel != null) {
            return normalizeSeparators(rel)
        }

        val abs = absolutePath?.trim().orEmpty()
        if (abs.isNotEmpty()) {
            val absNorm = normalizeSeparators(abs)
            val base = projectBasePath?.trim()?.takeIf { it.isNotEmpty() }
            if (base != null) {
                val baseNorm = normalizeSeparators(base).trimEnd('/')
                val underBase = absNorm.equals(baseNorm, ignoreCase = true) ||
                    absNorm.startsWith("$baseNorm/", ignoreCase = true)
                if (underBase) {
                    val stripped = absNorm.removePrefix(baseNorm).trimStart('/')
                    if (stripped.isNotEmpty()) return stripped
                }
            }
            return absNorm
        }

        return fallbackName.trim().ifEmpty { "unknown" }
    }

    /** Forward-slash path for display consistency (Windows + Unix). */
    fun normalizeSeparators(path: String): String = path.replace('\\', '/')

    /**
     * Decode [bytes] as text preview, or `null` for binary / empty / decode failure.
     * Truncates to [maxChars] with a trailing marker when longer.
     */
    fun previewTextFromBytes(
        bytes: ByteArray,
        charset: Charset = Charsets.UTF_8,
        maxChars: Int = DEFAULT_PREVIEW_MAX_CHARS
    ): String? {
        if (looksBinary(bytes)) return null
        return try {
            val text = String(bytes, charset)
            if (text.isEmpty()) return null
            if (text.length <= maxChars) text
            else text.take(maxChars) + "\n… (truncated)"
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Heuristic: null bytes in the leading sample ⇒ binary.
     * Empty files are treated as non-binary (caller may still get null from empty decode).
     */
    fun looksBinary(bytes: ByteArray, sampleSize: Int = BINARY_SAMPLE_BYTES): Boolean {
        val n = minOf(bytes.size, sampleSize)
        for (i in 0 until n) {
            if (bytes[i] == 0.toByte()) return true
        }
        return false
    }

    /**
     * One attachment block: always non-empty after the header.
     * - Non-blank content → fenced preview
     * - Null/blank content → explicit [CONTENT_UNAVAILABLE] line (never empty ``` ```)
     */
    fun formatAttachmentBlock(pathOrName: String, content: String?): String {
        val label = pathOrName.trim().ifEmpty { "unknown" }
        val header = "[Attached: $label]"
        val body = content?.takeIf { it.isNotBlank() }
        return if (body != null) {
            "$header\n```\n$body\n```"
        } else {
            "$header\n$CONTENT_UNAVAILABLE"
        }
    }

    /**
     * Full user message payload. Empty [attachments] returns bare [userText].
     * Each entry is (pathOrName, content) — content null/blank triggers unavailable instruction.
     */
    fun buildMessagePayload(
        userText: String,
        attachments: List<Pair<String, String?>>
    ): String {
        if (attachments.isEmpty()) return userText
        val contextBlock = attachments.joinToString("\n\n") { (pathOrName, content) ->
            formatAttachmentBlock(pathOrName, content)
        }
        return "$userText\n\n--- Workspace context ---\n$contextBlock"
    }
}
