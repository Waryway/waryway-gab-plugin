package com.waryway.gab.tools

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * In-process IDE tool execution when JetBrains MCP Server HTTP is unavailable.
 *
 * Covers the core agent surface (read/search/edit/run/open files) so cloud agent
 * turns are not stuck with `tools=off` when Settings → Tools → MCP Server is disabled
 * or MCP list_tools returns 404 while the built-in server still answers /api/about.
 */
class NativeIdeToolExecutor(private val project: Project) {

    fun supports(name: String): Boolean = name in SUPPORTED

    fun execute(name: String, argumentsJson: String): String {
        val args = JsonArgs(argumentsJson)
        val root = resolveProjectRoot(args)
        return try {
            when (name) {
                "read_file", "get_file_text_by_path" -> readFile(args, root)
                "list_directory_tree" -> listDirectoryTree(args, root)
                "find_files_by_name_keyword" -> findFilesByName(args, root)
                "find_files_by_glob" -> findFilesByGlob(args, root)
                "search_in_files_by_text", "search_text", "search_file" -> searchText(args, root)
                "get_all_open_file_paths" -> openFilePaths()
                "open_file_in_editor" -> openInEditor(args, root)
                "create_new_file" -> createNewFile(args, root)
                "replace_text_in_file" -> replaceText(args, root)
                "execute_terminal_command" -> executeTerminal(args, root)
                "build_project" -> executeTerminal(
                    JsonArgs("""{"command":${ToolDefinition.jsonString(defaultBuildCommand())}}"""),
                    root
                )
                else -> "error: native tool not implemented: $name (enable Settings → Tools → MCP Server for full toolset)"
            }
        } catch (e: Exception) {
            "error: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun defaultBuildCommand(): String {
        val win = System.getProperty("os.name", "").lowercase().contains("win")
        val root = project.basePath?.let { File(it) }
        return when {
            root != null && File(root, "build.bat").isFile -> "build.bat"
            root != null && File(root, "gradlew.bat").isFile && win -> "gradlew.bat build"
            root != null && File(root, "gradlew").isFile -> "./gradlew build"
            else -> if (win) "gradlew.bat build" else "./gradlew build"
        }
    }

    private fun resolveProjectRoot(args: JsonArgs): Path {
        val fromArgs = args.string("projectPath")
            ?: args.string("project_path")
        val base = fromArgs?.takeIf { it.isNotBlank() } ?: project.basePath.orEmpty()
        return Path.of(base.ifBlank { System.getProperty("user.dir") })
    }

    private fun resolvePath(root: Path, relativeOrAbsolute: String): Path {
        val raw = relativeOrAbsolute.trim().ifBlank { "." }
        val p = Path.of(raw)
        return if (p.isAbsolute) p.normalize() else root.resolve(raw).normalize()
    }

    private fun readFile(args: JsonArgs, root: Path): String {
        val pathArg = args.string("file_path")
            ?: args.string("pathInProject")
            ?: args.string("path")
            ?: args.string("filePath")
            ?: return "error: file_path required"
        val path = resolvePath(root, pathArg)
        if (!Files.isRegularFile(path)) return "error: not a file: $path"
        val lines = Files.readAllLines(path, StandardCharsets.UTF_8)
        val max = 4000
        val body = lines.take(max).mapIndexed { i, line -> "${i + 1}|$line" }.joinToString("\n")
        return if (lines.size > max) {
            body + "\n… (${lines.size - max} more lines truncated)"
        } else {
            body.ifEmpty { "(empty file)" }
        }
    }

    private fun listDirectoryTree(args: JsonArgs, root: Path): String {
        val dirArg = args.string("directoryPath")
            ?: args.string("path")
            ?: "."
        val maxDepth = args.int("maxDepth")?.coerceIn(1, 6) ?: 3
        val start = resolvePath(root, dirArg)
        if (!Files.isDirectory(start)) return "error: not a directory: $start"
        val sb = StringBuilder()
        fun walk(dir: Path, depth: Int, prefix: String) {
            if (depth > maxDepth) return
            val children = try {
                Files.list(dir).use { stream ->
                    stream.sorted().limit(200).toList()
                }
            } catch (_: Exception) {
                emptyList()
            }
            for (child in children) {
                val name = child.fileName?.toString() ?: continue
                if (name.startsWith(".") || name in SKIP_DIRS) continue
                val isDir = Files.isDirectory(child)
                sb.append(prefix).append(if (isDir) "📁 " else "📄 ").append(name).append('\n')
                if (isDir && depth < maxDepth) {
                    walk(child, depth + 1, "$prefix  ")
                }
            }
        }
        sb.append(start.fileName?.toString() ?: start.toString()).append('\n')
        walk(start, 1, "  ")
        return sb.toString().ifBlank { "(empty)" }.take(16_000)
    }

    private fun findFilesByName(args: JsonArgs, root: Path): String {
        val keyword = args.string("nameKeyword")
            ?: args.string("name")
            ?: return "error: nameKeyword required"
        val limit = args.int("fileCountLimit")?.coerceIn(1, 200) ?: 40
        val hits = mutableListOf<String>()
        walkFiles(root, maxFiles = 8000) { path ->
            if (path.fileName.toString().contains(keyword, ignoreCase = true)) {
                hits.add(root.relativize(path).toString().replace('\\', '/'))
            }
            hits.size < limit
        }
        return if (hits.isEmpty()) "(no matches)" else hits.joinToString("\n")
    }

    private fun findFilesByGlob(args: JsonArgs, root: Path): String {
        val glob = args.string("globPattern")
            ?: args.string("glob")
            ?: return "error: globPattern required"
        val limit = args.int("fileCountLimit")?.coerceIn(1, 200) ?: 40
        val matcher = try {
            root.fileSystem.getPathMatcher("glob:$glob")
        } catch (_: Exception) {
            return "error: invalid glob: $glob"
        }
        val hits = mutableListOf<String>()
        walkFiles(root, maxFiles = 8000) { path ->
            val rel = root.relativize(path)
            if (matcher.matches(rel) || matcher.matches(path.fileName)) {
                hits.add(rel.toString().replace('\\', '/'))
            }
            hits.size < limit
        }
        return if (hits.isEmpty()) "(no matches)" else hits.joinToString("\n")
    }

    private fun searchText(args: JsonArgs, root: Path): String {
        val query = args.string("searchText")
            ?: args.string("text")
            ?: args.string("query")
            ?: args.string("pattern")
            ?: return "error: searchText required"
        val limit = 40
        val hits = mutableListOf<String>()
        walkFiles(root, maxFiles = 4000) { path ->
            val name = path.fileName.toString()
            if (isBinaryish(name)) return@walkFiles true
            try {
                val lines = Files.readAllLines(path, StandardCharsets.UTF_8)
                lines.forEachIndexed { idx, line ->
                    if (hits.size >= limit) return@forEachIndexed
                    if (line.contains(query)) {
                        val rel = root.relativize(path).toString().replace('\\', '/')
                        val snippet = line.trim().take(120)
                        hits.add("$rel:${idx + 1}: $snippet")
                    }
                }
            } catch (_: Exception) {
                // skip unreadable
            }
            hits.size < limit
        }
        return if (hits.isEmpty()) "(no matches)" else hits.joinToString("\n")
    }

    private fun openFilePaths(): String {
        val paths = AtomicReference<List<String>>(emptyList())
        ApplicationManager.getApplication().invokeAndWait {
            val open = FileEditorManager.getInstance(project).openFiles
            val root = project.basePath
            paths.set(
                open.map { vf ->
                    val p = vf.path
                    if (root != null && p.replace('\\', '/').startsWith(root.replace('\\', '/'))) {
                        p.removePrefix(root).trimStart('/', '\\').replace('\\', '/')
                    } else {
                        p
                    }
                }
            )
        }
        val list = paths.get()
        return if (list.isEmpty()) "(no open files)" else list.joinToString("\n")
    }

    private fun openInEditor(args: JsonArgs, root: Path): String {
        val pathArg = args.string("filePath")
            ?: args.string("pathInProject")
            ?: args.string("path")
            ?: args.string("file_path")
            ?: return "error: filePath required"
        val path = resolvePath(root, pathArg)
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
            ?: return "error: file not found: $path"
        ApplicationManager.getApplication().invokeAndWait {
            FileEditorManager.getInstance(project).openFile(vf, true)
        }
        return "opened: ${pathArg.trim()}"
    }

    private fun createNewFile(args: JsonArgs, root: Path): String {
        val rel = args.string("pathInProject")
            ?: args.string("path")
            ?: args.string("filePath")
            ?: return "error: pathInProject required"
        val text = args.string("text").orEmpty()
        val overwrite = args.bool("overwrite") ?: false
        val path = resolvePath(root, rel)
        if (Files.exists(path) && !overwrite) {
            return "error: file exists (set overwrite=true): $path"
        }
        Files.createDirectories(path.parent ?: root)
        val error = AtomicReference<String?>(null)
        ApplicationManager.getApplication().invokeAndWait {
            try {
                WriteCommandAction.runWriteCommandAction(project, "Waryway create file", null, Runnable {
                    val parent = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path.parent)
                        ?: VfsUtil.createDirectories(path.parent.toString())
                    val existing = parent.findChild(path.fileName.toString())
                    val vf = existing ?: parent.createChildData(this, path.fileName.toString())
                    VfsUtil.saveText(vf, text)
                })
            } catch (e: Exception) {
                error.set(e.message ?: e.javaClass.simpleName)
            }
        }
        return error.get()?.let { "error: $it" } ?: "created: ${rel.trim()}"
    }

    private fun replaceText(args: JsonArgs, root: Path): String {
        val rel = args.string("pathInProject")
            ?: args.string("path")
            ?: args.string("file_path")
            ?: args.string("filePath")
            ?: return "error: pathInProject required"
        val oldText = args.string("oldText")
            ?: args.string("old_text")
            ?: return "error: oldText required"
        val newText = args.string("newText")
            ?: args.string("new_text")
            ?: return "error: newText required"
        val path = resolvePath(root, rel)
        if (!Files.isRegularFile(path)) return "error: not a file: $path"
        val content = Files.readString(path, StandardCharsets.UTF_8)
        if (!content.contains(oldText)) return "error: oldText not found in $rel"
        val updated = content.replaceFirst(oldText, newText)
        val error = AtomicReference<String?>(null)
        ApplicationManager.getApplication().invokeAndWait {
            try {
                WriteCommandAction.runWriteCommandAction(project, "Waryway replace text", null, Runnable {
                    val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
                        ?: throw IllegalStateException("VFS miss: $path")
                    val doc = FileDocumentManager.getInstance().getDocument(vf)
                    if (doc != null) {
                        doc.setText(updated)
                        FileDocumentManager.getInstance().saveDocument(doc)
                    } else {
                        VfsUtil.saveText(vf, updated)
                    }
                })
            } catch (e: Exception) {
                error.set(e.message ?: e.javaClass.simpleName)
            }
        }
        return error.get()?.let { "error: $it" } ?: "replaced text in ${rel.trim()}"
    }

    private fun executeTerminal(args: JsonArgs, root: Path): String {
        val command = args.string("command")
            ?: args.string("cmd")
            ?: return "error: command required"
        val timeoutSec = args.int("timeout")?.coerceIn(5, 600) ?: 120
        val workDir = root.toFile().takeIf { it.isDirectory } ?: File(System.getProperty("user.dir"))
        val isWin = System.getProperty("os.name", "").lowercase().contains("win")
        val pb = if (isWin) {
            ProcessBuilder("cmd.exe", "/c", command)
        } else {
            ProcessBuilder("sh", "-c", command)
        }
        pb.directory(workDir)
        pb.redirectErrorStream(true)
        val process = pb.start()
        val finished = process.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return "error: command timed out after ${timeoutSec}s: $command"
        }
        val out = process.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
        val code = process.exitValue()
        val body = out.take(14_000).ifBlank { "(no output)" }
        return "exit=$code\n$body"
    }

    private fun walkFiles(root: Path, maxFiles: Int, visitor: (Path) -> Boolean) {
        if (!Files.isDirectory(root)) return
        var count = 0
        Files.walk(root).use { stream ->
            val it = stream.iterator()
            while (it.hasNext() && count < maxFiles) {
                val p = it.next()
                if (!Files.isRegularFile(p)) continue
                val name = p.fileName?.toString() ?: continue
                if (name.startsWith(".")) continue
                val rel = try {
                    root.relativize(p).toString().replace('\\', '/')
                } catch (_: Exception) {
                    continue
                }
                if (SKIP_DIRS.any { seg -> rel.split('/').any { it == seg } }) continue
                count++
                if (!visitor(p)) break
            }
        }
    }

    private fun isBinaryish(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
            lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".ico") ||
            lower.endsWith(".jar") || lower.endsWith(".zip") || lower.endsWith(".class") ||
            lower.endsWith(".exe") || lower.endsWith(".dll") || lower.endsWith(".so") ||
            lower.endsWith(".pdf") || lower.endsWith(".woff") || lower.endsWith(".woff2")
    }

    /** Minimal JSON string/int/bool extractor for tool arguments. */
    class JsonArgs(raw: String) {
        private val json = raw.trim().ifBlank { "{}" }

        fun string(field: String): String? {
            val pattern = Regex(""""$field"\s*:\s*"((?:[^"\\]|\\.)*)"""")
            val m = pattern.find(json) ?: return null
            return m.groupValues[1]
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
        }

        fun int(field: String): Int? {
            val pattern = Regex(""""$field"\s*:\s*(-?\d+)""")
            return pattern.find(json)?.groupValues?.get(1)?.toIntOrNull()
        }

        fun bool(field: String): Boolean? {
            val pattern = Regex(""""$field"\s*:\s*(true|false)""")
            val v = pattern.find(json)?.groupValues?.get(1) ?: return null
            return v == "true"
        }
    }

    companion object {
        val SUPPORTED: Set<String> = setOf(
            "read_file",
            "get_file_text_by_path",
            "list_directory_tree",
            "find_files_by_name_keyword",
            "find_files_by_glob",
            "search_in_files_by_text",
            "search_text",
            "search_file",
            "get_all_open_file_paths",
            "open_file_in_editor",
            "create_new_file",
            "replace_text_in_file",
            "execute_terminal_command",
            "build_project"
        )

        private val SKIP_DIRS = setOf(
            ".git", ".idea", "build", "out", "node_modules", ".gradle",
            "target", "dist", ".waryway-gab", "agent-tools"
        )
    }
}
