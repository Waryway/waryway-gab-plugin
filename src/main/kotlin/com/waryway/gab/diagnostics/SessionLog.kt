package com.waryway.gab.diagnostics

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
 * Timestamped session diagnostics streamed to the Activity log panel.
 */
class SessionLog(
    private val onLine: (String) -> Unit = {},
    private val maxLines: Int = 800
) {
    private val lines = ArrayDeque<String>(maxLines)

    fun log(level: LogLevel, message: String) {
        val text = message.trim()
        if (text.isEmpty()) return
        val line = format(level, text)
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > maxLines) lines.removeFirst()
        }
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

    companion object {
        const val CLEAR_SENTINEL = "\u0000CLEAR\u0000"

        fun format(level: LogLevel, message: String): String {
            val ts = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
            return "[$ts] ${level.tag}  $message"
        }
    }
}