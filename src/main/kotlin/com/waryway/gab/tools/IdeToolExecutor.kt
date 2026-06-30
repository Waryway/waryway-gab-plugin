package com.waryway.gab.tools

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Executes agent tools against the open IDE project using IntelliJ Platform APIs.
 */
class IdeToolExecutor(private val project: Project) {

    fun execute(name: String, argumentsJson: String): String {
        val args = JsonArgs.parse(argumentsJson)
        return when (name) {
            "read_file" -> readFile(args)
            "search_text" -> searchText(args)
            "get_open_files" -> getOpenFiles()
            "list_directory" -> listDirectory(args)
            "replace_text_in_file" -> replaceText(args)
            "write_file" -> writeFile(args)
            "run_shell_command" -> runShellCommand(args)
            else -> "error: unknown tool '$name'"
        }
    }

    private fun readFile(args: JsonArgs): String {
        val path = args.string("file_path", "path") ?: return "error: file_path is required"
        val offset = args.int("offset", default = 1).coerceAtLeast(1)
        val limit = args.int("limit", default = 500).coerceIn(1, 2000)

        val file = resolveFile(path) ?: return "error: file not found: $path"
        if (file.isDirectory) return "error: path is a directory: $path"

        val text = readVirtualFileText(file) ?: return "error: could not read file: $path"
        val lines = text.lines()
        if (lines.isEmpty()) return "(empty file)"

        val startIdx = (offset - 1).coerceAtMost(lines.lastIndex)
        val endIdx = (startIdx + limit).coerceAtMost(lines.size)
        return (startIdx until endIdx).joinToString("\n") { i ->
            "${i + 1}|${lines[i]}"
        }
    }

    private fun searchText(args: JsonArgs): String {
        val query = args.string("query", "q") ?: return "error: query is required"
        if (query.isEmpty()) return "error: query is empty"
        val limit = args.int("limit", default = 30).coerceIn(1, 100)

        val baseDir = project.baseDir ?: return "error: project root not found"
        val results = mutableListOf<String>()
        searchInDirectory(baseDir, query, results, limit)
        return if (results.isEmpty()) "no matches found" else results.joinToString("\n")
    }

    private fun searchInDirectory(dir: VirtualFile, query: String, results: MutableList<String>, limit: Int) {
        if (results.size >= limit) return
        if (shouldSkipDir(dir.name)) return

        for (child in dir.children) {
            if (results.size >= limit) return
            when {
                child.isDirectory -> searchInDirectory(child, query, results, limit)
                child.isValid && !child.isDirectory && isSearchableFile(child) -> {
                    val text = readVirtualFileText(child) ?: continue
                    val rel = toRelativePath(child) ?: child.path
                    text.lineSequence().forEachIndexed { idx, line ->
                        if (results.size >= limit) return@forEachIndexed
                        if (line.contains(query, ignoreCase = true)) {
                            results.add("$rel:${idx + 1}: ${line.trim().take(200)}")
                        }
                    }
                }
            }
        }
    }

    private fun getOpenFiles(): String = ApplicationManager.getApplication().runReadAction<String> {
        val fem = FileEditorManager.getInstance(project)
        val sb = StringBuilder()

        val selected = fem.selectedFiles.firstOrNull()
        if (selected != null) {
            sb.appendLine("active: ${toRelativePath(selected) ?: selected.path}")
            val editor = fem.selectedTextEditor
            if (editor != null) {
                val sel = editor.selectionModel
                if (sel.hasSelection()) {
                    sb.appendLine("selection:")
                    sb.appendLine(sel.selectedText?.take(4000) ?: "")
                } else {
                    val doc = editor.document
                    val line = doc.getLineNumber(editor.caretModel.offset) + 1
                    sb.appendLine("caret_line: $line")
                }
            }
        }

        val open = fem.openFiles
        if (open.isNotEmpty()) {
            sb.appendLine("open_files:")
            open.forEach { f -> sb.appendLine("- ${toRelativePath(f) ?: f.path}") }
        }
        if (sb.isEmpty()) "no open files" else sb.toString().trimEnd()
    }

    private fun listDirectory(args: JsonArgs): String {
        val path = args.string("path") ?: ""
        val dir = if (path.isBlank()) project.baseDir else resolveFile(path)
        if (dir == null) return "error: directory not found: $path"
        if (!dir.isDirectory) return "error: not a directory: $path"

        return dir.children
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            .joinToString("\n") { child ->
                val prefix = if (child.isDirectory) "dir " else "file"
                "$prefix ${child.name}"
            }
            .ifEmpty { "(empty directory)" }
    }

    private fun replaceText(args: JsonArgs): String {
        val path = args.string("path", "pathInProject") ?: return "error: path is required"
        val oldText = args.string("old_text", "oldText") ?: return "error: old_text is required"
        val newText = args.string("new_text", "newText") ?: return "error: new_text is required"
        val replaceAll = args.boolean("replace_all", "replaceAll", default = true)

        val file = resolveFile(path) ?: return "error: file not found: $path"
        val result = AtomicReference<String>()

        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                val doc = FileDocumentManager.getInstance().getDocument(file)
                    ?: run {
                        result.set("error: could not get document for $path")
                        return@runWriteCommandAction
                    }
                val content = doc.text
                if (!content.contains(oldText)) {
                    result.set("error: old_text not found in $path")
                    return@runWriteCommandAction
                }
                val updated = if (replaceAll) content.replace(oldText, newText) else content.replaceFirst(oldText, newText)
                doc.setText(updated)
                FileDocumentManager.getInstance().saveDocument(doc)
                val count = if (replaceAll) content.split(oldText).size - 1 else 1
                result.set("ok: replaced $count occurrence(s) in $path")
            }
        }
        return result.get() ?: "error: replace failed"
    }

    private fun writeFile(args: JsonArgs): String {
        val path = args.string("path") ?: return "error: path is required"
        val content = args.string("content") ?: return "error: content is required"

        val normalized = path.replace('\\', '/').trimStart('/')
        val base = project.basePath ?: return "error: project root not found"
        val targetFile = File(base, normalized)
        val parent = targetFile.parentFile

        val result = AtomicReference<String>()
        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                try {
                    parent?.mkdirs()
                    targetFile.writeText(content, StandardCharsets.UTF_8)
                    LocalFileSystem.getInstance().refreshAndFindFileByIoFile(targetFile)?.let { vf ->
                        vf.refresh(false, false); FileDocumentManager.getInstance().getDocument(vf)?.let { doc -> FileDocumentManager.getInstance().reloadFromDisk(doc) }
                    }
                    result.set("ok: wrote ${targetFile.length()} bytes to $normalized")
                } catch (e: Exception) {
                    result.set("error: ${e.message}")
                }
            }
        }
        return result.get() ?: "error: write failed"
    }

    private fun runShellCommand(args: JsonArgs): String {
        val command = args.string("command") ?: return "error: command is required"
        val timeoutSec = args.int("timeout_seconds", "timeout", default = 120).coerceIn(5, 600)
        val base = project.basePath ?: return "error: project root not found"

        return try {
            val process = if (System.getProperty("os.name").lowercase().contains("win")) {
                ProcessBuilder("cmd.exe", "/c", command)
            } else {
                ProcessBuilder("sh", "-c", command)
            }.directory(File(base))
                .redirectErrorStream(true)
                .start()

            val finished = process.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)
            val output = process.inputStream.bufferedReader().readText()
            val truncated = if (output.length > 16_000) output.take(16_000) + "\n… (truncated)" else output

            if (!finished) {
                process.destroyForcibly()
                "timed out after ${timeoutSec}s\n$truncated"
            } else {
                "exit_code: ${process.exitValue()}\n$truncated"
            }
        } catch (e: Exception) {
            "error: ${e.message}"
        }
    }

    private fun resolveFile(path: String): VirtualFile? {
        val trimmed = path.trim().replace('\\', '/')
        val baseDir = project.baseDir

        if (trimmed.isNotEmpty()) {
            baseDir?.let { base ->
                VfsUtil.findRelativeFile(trimmed, base)?.let { return it }
            }
            LocalFileSystem.getInstance().findFileByPath(trimmed)?.let { return it }
        }

        val base = project.basePath ?: return null
        val absolute = if (File(trimmed).isAbsolute) trimmed else "$base/$trimmed"
        return LocalFileSystem.getInstance().findFileByPath(absolute.replace('/', File.separatorChar))
    }

    private fun toRelativePath(file: VirtualFile): String? {
        val baseDir = project.baseDir ?: return null
        return VfsUtil.getRelativePath(file, baseDir)
    }

    private fun readVirtualFileText(file: VirtualFile): String? = try {
        ApplicationManager.getApplication().runReadAction<String?> {
            if (!file.isValid || file.isDirectory) return@runReadAction null
            if (file.length > 2_000_000) return@runReadAction null
            String(file.contentsToByteArray(), file.charset)
        }
    } catch (_: Exception) {
        null
    }

    private fun shouldSkipDir(name: String): Boolean =
        name in SKIP_DIRS || name.startsWith('.')

    private fun isSearchableFile(file: VirtualFile): Boolean {
        if (file.length > 512_000) return false
        val ext = file.extension?.lowercase() ?: return true
        return ext !in SKIP_EXTENSIONS
    }

    companion object {
        private val SKIP_DIRS = setOf(
            ".git", ".idea", ".gradle", "node_modules", "build", "out", "dist",
            "target", ".intellijPlatform", "vendor"
        )
        private val SKIP_EXTENSIONS = setOf(
            "jar", "zip", "png", "jpg", "jpeg", "gif", "webp", "ico", "pdf",
            "class", "exe", "dll", "so", "dylib", "woff", "woff2", "ttf"
        )
    }
}

/** Minimal JSON argument parser for tool call payloads. */
internal class JsonArgs private constructor(private val values: Map<String, String>) {

    fun string(vararg keys: String): String? {
        for (key in keys) {
            values[key]?.let { return it }
        }
        return null
    }

    fun int(vararg keys: String, default: Int = 0): Int {
        for (key in keys) {
            values[key]?.toIntOrNull()?.let { return it }
        }
        return default
    }

    fun boolean(vararg keys: String, default: Boolean = false): Boolean {
        for (key in keys) {
            values[key]?.let { return it.equals("true", ignoreCase = true) }
        }
        return default
    }

    companion object {
        fun parse(json: String): JsonArgs {
            if (json.isBlank()) return JsonArgs(emptyMap())
            val map = mutableMapOf<String, String>()

            // String values: "key": "value"
            Regex(""""(\w+)"\s*:\s*"((?:[^"\\]|\\.)*)"""")
                .findAll(json)
                .forEach { m -> map[m.groupValues[1]] = unescape(m.groupValues[2]) }

            // Boolean / number values: "key": true / "key": 42
            Regex(""""(\w+)"\s*:\s*(true|false|-?\d+)""")
                .findAll(json)
                .forEach { m -> map.putIfAbsent(m.groupValues[1], m.groupValues[2]) }

            return JsonArgs(map)
        }

        private fun unescape(s: String): String = s
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }
}