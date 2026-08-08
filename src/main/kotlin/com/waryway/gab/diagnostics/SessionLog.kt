package com.waryway.gab.diagnostics

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalTime
import java.time.format.DateTimeFormatter

enum class LogLevel(val tag: String) {
    SYSTEM("SYS"),
    HTTP("HTTP"),
    SSE("SSE"),
    ERROR("ERR"),
    TOOL("TOOL"),
}

/**
 * Timestamped session diagnostics streamed to the Activity log panel
 * and optionally mirrored to a durable log file on disk.
 */
class SessionLog(
    private val onLine: (String) -> Unit = {},
    private val maxLines: Int = 800,
    /** When set, every line is also appended to this file (created if missing). */
    private var logFile: Path? = null,
) {
    private val lines = ArrayDeque<String>(maxLines)
    private val fileLock = Any()

    /** Absolute path of the durable session log file, if file sink is active. */
    fun logFilePath(): Path? = logFile?.toAbsolutePath()?.normalize()

    /**
     * Attach or replace the file sink. Creates parent dirs and the file.
     * Returns the absolute path used.
     */
    fun attachLogFile(path: Path): Path {
        val abs = path.toAbsolutePath().normalize()
        Files.createDirectories(abs.parent)
        if (!Files.exists(abs)) {
            Files.writeString(
                abs,
                "# Waryway Gab session log\n# started ${java.time.LocalDateTime.now()}\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE
            )
        }
        logFile = abs
        return abs
    }

    fun log(level: LogLevel, message: String) {
        val text = message.trim()
        if (text.isEmpty()) return
        val line = format(level, text)
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > maxLines) lines.removeFirst()
        }
        appendToFile(line)
        onLine(line)
    }

    fun system(message: String) = log(LogLevel.SYSTEM, message)
    fun http(message: String) = log(LogLevel.HTTP, message)
    fun sse(message: String) = log(LogLevel.SSE, message)
    fun error(message: String) = log(LogLevel.ERROR, message)
    fun tool(message: String) = log(LogLevel.TOOL, message)

    fun clear() {
        synchronized(lines) { lines.clear() }
        onLine(CLEAR_SENTINEL)
    }

    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }

    /**
     * Write current in-memory snapshot to [path] (full replace).
     * Does not change the live file sink.
     */
    fun writeSnapshotTo(path: Path): Path {
        val abs = path.toAbsolutePath().normalize()
        Files.createDirectories(abs.parent)
        val snap = snapshot()
        val body = if (snap.isEmpty()) "" else snap.joinToString("\n") + "\n"
        Files.writeString(abs, body, StandardCharsets.UTF_8)
        return abs
    }

    private fun appendToFile(line: String) {
        val file = logFile ?: return
        synchronized(fileLock) {
            try {
                Files.writeString(
                    file,
                    line + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND
                )
            } catch (_: Exception) {
                // Never break the agent for disk issues.
            }
        }
    }

    companion object {
        const val CLEAR_SENTINEL = "\u0000CLEAR\u0000"

        fun format(level: LogLevel, message: String): String {
            val ts = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
            return "[$ts] ${level.tag}  $message"
        }
    }
}
