package com.waryway.gab.ui

import com.intellij.ide.dnd.DnDEvent
import com.intellij.ide.dnd.DnDNativeTarget
import com.intellij.ide.dnd.FileCopyPasteUtil
import com.intellij.ide.dnd.FileFlavorProvider
import com.intellij.ide.dnd.TransferableWrapper
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileSystemItem
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File
import java.net.URI
import java.nio.file.Path
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreeNode
import javax.swing.tree.TreePath

/**
 * Extracts [VirtualFile]s from IDE-internal and OS drag-and-drop transfers.
 *
 * ## Supported sources
 * - [FileCopyPasteUtil] attached-object list and file-list flavors
 * - Project View [TransferableWrapper] / [FileFlavorProvider] (PSI + tree paths + asFileList)
 * - [DnDNativeTarget.EventInfo] (OS drops via native target wrapper)
 * - JVM [DataFlavor] for [VirtualFile] / `VirtualFile[]` / [TransferableWrapper]
 * - [DataFlavor.javaFileListFlavor] (OS Explorer / desktop drops)
 * - [DataFlavor.stringFlavor] and plain-text flavors: one local path per line
 * - `text/uri-list` (`file://` URIs → local paths)
 * - Attached-object walk: [VirtualFile], [File], [Path], [PsiFile] / [PsiDirectory] /
 *   [PsiElement] (via containing file), [TreePath], [DefaultMutableTreeNode],
 *   arrays / collections, and mild `getValue` / `getVirtualFile` / `getPsiElement(s)` unwrap
 *
 * ## Unsupported / known gaps
 * - Remote VFS entries with no local path (e.g. some HTTP / non-local file systems)
 * - Library / JAR roots that are not backed by [LocalFileSystem]
 * - Non-`file` URI schemes in `text/uri-list` (http/https ignored)
 * - Arbitrary custom MIME types beyond string / uri-list / javaFileList / VirtualFile JVM flavors
 * - Directories may be returned as [VirtualFile]s; callers typically filter [VirtualFile.isDirectory]
 *
 * Results are de-duplicated with stable order ([LinkedHashSet]).
 * All transferable access is null-safe / [runCatching]-guarded to avoid blank/null crashes.
 */
object FileDropUtil {

    fun canAccept(event: DnDEvent): Boolean = canAccept(event as Transferable, event.attachedObject)

    fun canAccept(transferable: Transferable): Boolean = canAccept(transferable, null)

    fun extractFiles(event: DnDEvent): List<VirtualFile> =
        extractFiles(event as Transferable, event.attachedObject)

    fun extractFiles(transferable: Transferable): List<VirtualFile> = extractFiles(transferable, null)

    private fun canAccept(transferable: Transferable, attachedObject: Any?): Boolean = runCatching {
        // Fast path: Project View TransferableWrapper / FileFlavorProvider look file-like
        // even before VFS resolution (avoids rejecting drag-over when resolve is slow).
        if (attachedLooksAcceptable(attachedObject)) return@runCatching true
        if (transferableLooksAcceptable(transferable)) return@runCatching true
        extractFiles(transferable, attachedObject).isNotEmpty()
    }.getOrDefault(false)

    private fun extractFiles(transferable: Transferable, attachedObject: Any?): List<VirtualFile> {
        val result = linkedSetOf<VirtualFile>()

        if (attachedObject != null) {
            // Prefer Project View TransferableWrapper PSI → VirtualFile (no File round-trip).
            collectFromAttached(attachedObject, result)
            runCatching {
                FileCopyPasteUtil.getVirtualFileListFromAttachedObject(attachedObject).forEach { result.add(it) }
            }
            // Nested transferable from native OS drops (EventInfo).
            if (attachedObject is DnDNativeTarget.EventInfo) {
                runCatching {
                    extractFiles(attachedObject.transferable, null).forEach { result.add(it) }
                }
            }
        }

        runCatching {
            if (FileCopyPasteUtil.isFileListFlavorAvailable(transferable.transferDataFlavors)) {
                FileCopyPasteUtil.getFileList(transferable)?.forEach { file ->
                    resolveIoFile(file)?.let { result.add(it) }
                }
            }
        }

        collectJvmFlavor(transferable, virtualFileFlavor(transferable), result)
        collectJvmFlavor(transferable, arrayVirtualFileFlavor(transferable), result)
        collectJvmFlavor(transferable, transferableWrapperFlavor(transferable), result)

        runCatching {
            if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                when (val data = transferable.getTransferData(DataFlavor.javaFileListFlavor)) {
                    is List<*> -> data.forEach { item ->
                        when (item) {
                            is File -> resolveIoFile(item)?.let { result.add(it) }
                            is Path -> resolvePath(item)?.let { result.add(it) }
                            is String -> resolveLocalPath(item)?.let { result.add(it) }
                            is VirtualFile -> result.add(item)
                            else -> collectFromAttached(item, result)
                        }
                    }
                    // DnDEventImpl may return the attached TransferableWrapper when asFileList() is null.
                    else -> collectFromAttached(data, result)
                }
            }
        }

        collectStringPathFlavors(transferable, result)
        collectUriListFlavors(transferable, result)

        return result.toList()
    }

    /**
     * Lightweight accept probe for Project View / FileFlavorProvider attached objects.
     * Pure-ish shape checks + null-safe method probes (no VFS).
     */
    private fun attachedLooksAcceptable(attached: Any?): Boolean {
        if (attached == null) return false
        return runCatching {
            when (attached) {
                is TransferableWrapper -> {
                    val psi = attached.psiElements
                    if (psi != null && psi.isNotEmpty()) return@runCatching true
                    val paths = attached.treePaths
                    if (paths != null && paths.isNotEmpty()) return@runCatching true
                    val nodes = attached.treeNodes
                    if (nodes != null && nodes.isNotEmpty()) return@runCatching true
                    val files = attached.asFileList()
                    !files.isNullOrEmpty()
                }
                is FileFlavorProvider -> !attached.asFileList().isNullOrEmpty()
                is DnDNativeTarget.EventInfo -> {
                    val flavors = attached.flavors
                    flavors != null && flavors.any { isFileishFlavor(it) }
                }
                is VirtualFile, is File, is Path, is PsiFile, is PsiDirectory -> true
                is PsiElement -> true
                is TreePath, is DefaultMutableTreeNode -> true
                is Array<*> -> attached.isNotEmpty()
                is Collection<*> -> attached.isNotEmpty()
                else -> false
            }
        }.getOrDefault(false)
    }

    private fun transferableLooksAcceptable(transferable: Transferable): Boolean = runCatching {
        // Do not treat bare stringFlavor / text/plain as accept — extract still handles path text.
        transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) ||
            transferable.transferDataFlavors.any { isFileishFlavor(it) }
    }.getOrDefault(false)

    private fun isFileishFlavor(flavor: DataFlavor?): Boolean {
        if (flavor == null) return false
        if (flavor == DataFlavor.javaFileListFlavor) return true
        val mime = flavor.mimeType.orEmpty().lowercase()
        return mime.startsWith("text/uri-list") ||
            mime.contains("java-file-list") ||
            mime.startsWith("application/x-java-jvm-local-objectref") ||
            mime.startsWith("application/x-java-file-list")
    }

    private fun collectStringPathFlavors(transferable: Transferable, result: MutableSet<VirtualFile>) {
        for (flavor in stringLikeFlavors(transferable)) {
            runCatching {
                val text = transferable.getTransferData(flavor) as? String ?: return@runCatching
                parsePathLines(text).forEach { path ->
                    resolveLocalPath(path)?.let { result.add(it) }
                }
            }
        }
    }

    private fun collectUriListFlavors(transferable: Transferable, result: MutableSet<VirtualFile>) {
        for (flavor in uriListFlavors(transferable)) {
            runCatching {
                when (val data = transferable.getTransferData(flavor)) {
                    is String -> {
                        parseUriList(data).forEach { path ->
                            resolveLocalPath(path)?.let { result.add(it) }
                        }
                    }
                    is java.io.Reader -> {
                        val text = data.readText()
                        parseUriList(text).forEach { path ->
                            resolveLocalPath(path)?.let { result.add(it) }
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun stringLikeFlavors(transferable: Transferable): List<DataFlavor> {
        val out = mutableListOf<DataFlavor>()
        runCatching {
            if (transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                out.add(DataFlavor.stringFlavor)
            }
        }
        runCatching {
            for (flavor in transferable.transferDataFlavors) {
                if (flavor == null) continue
                val mime = flavor.mimeType.orEmpty().lowercase()
                val isPlainText = mime.startsWith("text/plain") &&
                    flavor.representationClass == String::class.java
                if (isPlainText && flavor !in out) out.add(flavor)
            }
        }
        return out
    }

    private fun uriListFlavors(transferable: Transferable): List<DataFlavor> {
        val out = mutableListOf<DataFlavor>()
        runCatching {
            for (flavor in transferable.transferDataFlavors) {
                if (flavor == null) continue
                val mime = flavor.mimeType.orEmpty().lowercase()
                if (mime.startsWith("text/uri-list")) out.add(flavor)
            }
        }
        // Explicit constructor in case the platform omits it from transferDataFlavors probe.
        runCatching {
            val explicit = DataFlavor("text/uri-list;class=java.lang.String")
            if (transferable.isDataFlavorSupported(explicit) && explicit !in out) {
                out.add(explicit)
            }
        }
        return out
    }

    /**
     * Walks Project View / tree / PSI-style attached objects without hard-coding every node type.
     * Depth-capped to avoid cycles in wrapper graphs.
     *
     * Project View attaches [TransferableWrapper] (implements [FileFlavorProvider]) with
     * PSI elements + tree paths — that path is preferred over File→VFS round-trip.
     */
    private fun collectFromAttached(obj: Any?, result: MutableSet<VirtualFile>, depth: Int = 0) {
        if (obj == null || depth > 8) return
        when (obj) {
            is VirtualFile -> {
                result.add(obj)
                return
            }
            is File -> {
                resolveIoFile(obj)?.let { result.add(it) }
                return
            }
            is Path -> {
                resolvePath(obj)?.let { result.add(it) }
                return
            }
            is PsiFile -> {
                obj.virtualFile?.let { result.add(it) }
                return
            }
            is PsiDirectory -> {
                result.add(obj.virtualFile)
                return
            }
            is PsiFileSystemItem -> {
                obj.virtualFile?.let { result.add(it) }
                return
            }
            is PsiElement -> {
                obj.containingFile?.virtualFile?.let { result.add(it) }
                return
            }
            is TreePath -> {
                collectFromAttached(obj.lastPathComponent, result, depth + 1)
                return
            }
            is DefaultMutableTreeNode -> {
                collectFromAttached(obj.userObject, result, depth + 1)
                return
            }
            is TreeNode -> {
                // Non-DefaultMutableTreeNode platform nodes (e.g. CachingTreePath last components).
                unwrapByMethod(obj, "getUserObject")?.let {
                    if (it !== obj) collectFromAttached(it, result, depth + 1)
                }
                unwrapByMethod(obj, "getValue")?.let {
                    if (it !== obj) collectFromAttached(it, result, depth + 1)
                }
                if (result.isEmpty()) {
                    unwrapByMethod(obj, "getVirtualFile")?.let {
                        collectFromAttached(it, result, depth + 1)
                    }
                }
                return
            }
            is TransferableWrapper -> {
                collectFromTransferableWrapper(obj, result, depth)
                return
            }
            is FileFlavorProvider -> {
                // Non-wrapper FileFlavorProvider: only asFileList.
                runCatching {
                    obj.asFileList()?.forEach { file ->
                        resolveIoFile(file)?.let { result.add(it) }
                    }
                }
                // Still try mild unwrap in case a custom provider also has PSI accessors.
                unwrapTransferableLike(obj, result, depth)
                return
            }
            is DnDNativeTarget.EventInfo -> {
                runCatching {
                    extractFiles(obj.transferable, null).forEach { result.add(it) }
                }
                return
            }
            is Array<*> -> {
                obj.forEach { collectFromAttached(it, result, depth + 1) }
                return
            }
            is Collection<*> -> {
                obj.forEach { collectFromAttached(it, result, depth + 1) }
                return
            }
        }

        unwrapTransferableLike(obj, result, depth)
    }

    private fun collectFromTransferableWrapper(
        wrapper: TransferableWrapper,
        result: MutableSet<VirtualFile>,
        depth: Int
    ) {
        // 1) PSI elements → VirtualFile directly (preferred for Project View).
        runCatching {
            wrapper.psiElements?.forEach { el ->
                collectFromAttached(el, result, depth + 1)
            }
        }
        // 2) Tree paths → node user objects / AbstractTreeNode values.
        runCatching {
            wrapper.treePaths?.forEach { path ->
                collectFromAttached(path, result, depth + 1)
            }
        }
        // 3) Tree nodes fallback.
        runCatching {
            wrapper.treeNodes?.forEach { node ->
                collectFromAttached(node, result, depth + 1)
            }
        }
        // 4) asFileList File → VFS (same as FileCopyPasteUtil; fills gaps).
        runCatching {
            wrapper.asFileList()?.forEach { file ->
                resolveIoFile(file)?.let { result.add(it) }
            }
        }
    }

    /**
     * Mild unwrap for AbstractTreeNode / PresentableNodeDescriptor / TransferableWrapper-style wrappers.
     * Also used when the concrete type is not on the compile classpath of a given IDE version.
     */
    private fun unwrapTransferableLike(obj: Any, result: MutableSet<VirtualFile>, depth: Int) {
        // Plural PSI accessors first (TransferableWrapper shape via reflection).
        unwrapByMethod(obj, "getPsiElements")?.let {
            collectFromAttached(it, result, depth + 1)
        }
        unwrapByMethod(obj, "getTreePaths")?.let {
            collectFromAttached(it, result, depth + 1)
        }
        unwrapByMethod(obj, "getTreeNodes")?.let {
            collectFromAttached(it, result, depth + 1)
        }
        unwrapByMethod(obj, "asFileList")?.let {
            collectFromAttached(it, result, depth + 1)
        }

        unwrapByMethod(obj, "getVirtualFile")?.let {
            collectFromAttached(it, result, depth + 1)
        }
        unwrapByMethod(obj, "getPsiElement")?.let {
            collectFromAttached(it, result, depth + 1)
        }
        unwrapByMethod(obj, "getValue")?.let { value ->
            if (value !== obj) collectFromAttached(value, result, depth + 1)
        }
        unwrapByMethod(obj, "getUserObject")?.let { value ->
            if (value !== obj) collectFromAttached(value, result, depth + 1)
        }
    }

    private fun unwrapByMethod(obj: Any, name: String): Any? = runCatching {
        val method = obj.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
            ?: return@runCatching null
        method.isAccessible = true
        method.invoke(obj)
    }.getOrNull()

    private fun virtualFileFlavor(transferable: Transferable): DataFlavor? {
        val flavor = FileCopyPasteUtil.createJvmDataFlavor(VirtualFile::class.java) ?: return null
        return flavor.takeIf { runCatching { transferable.isDataFlavorSupported(it) }.getOrDefault(false) }
    }

    private fun arrayVirtualFileFlavor(transferable: Transferable): DataFlavor? {
        val flavor = FileCopyPasteUtil.createJvmDataFlavor(VirtualFile::class.java.arrayType()) ?: return null
        return flavor.takeIf { runCatching { transferable.isDataFlavorSupported(it) }.getOrDefault(false) }
    }

    /**
     * JVM local objectref flavor for [TransferableWrapper] (DnDEventImpl.ourDataFlavor class).
     * When supported, [Transferable.getTransferData] yields the Project View wrapper.
     */
    private fun transferableWrapperFlavor(transferable: Transferable): DataFlavor? {
        val flavor = FileCopyPasteUtil.createJvmDataFlavor(TransferableWrapper::class.java) ?: return null
        return flavor.takeIf { runCatching { transferable.isDataFlavorSupported(it) }.getOrDefault(false) }
    }

    private fun collectJvmFlavor(
        transferable: Transferable,
        flavor: DataFlavor?,
        result: MutableSet<VirtualFile>
    ) {
        if (flavor == null) return
        runCatching {
            when (val data = transferable.getTransferData(flavor)) {
                is VirtualFile -> result.add(data)
                is Array<*> -> data.forEach { collectFromAttached(it, result) }
                is Collection<*> -> data.forEach { collectFromAttached(it, result) }
                else -> collectFromAttached(data, result)
            }
        }
    }

    private fun resolveIoFile(file: File): VirtualFile? = runCatching {
        VfsUtil.findFileByIoFile(file, true)
            ?: LocalFileSystem.getInstance().findFileByIoFile(file)
            ?: LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
    }.getOrNull()

    private fun resolvePath(path: Path): VirtualFile? = runCatching {
        resolveIoFile(path.toFile())
            ?: LocalFileSystem.getInstance().findFileByPath(path.toAbsolutePath().toString().replace('\\', '/'))
    }.getOrNull()

    private fun resolveLocalPath(path: String): VirtualFile? {
        val trimmed = path.trim().trim('"')
        if (trimmed.isEmpty()) return null
        return runCatching {
            // Allow file:// strings that arrived via stringFlavor rather than uri-list.
            val local = if (trimmed.startsWith("file:", ignoreCase = true)) {
                uriLineToLocalPath(trimmed) ?: return@runCatching null
            } else {
                trimmed
            }
            val asFile = File(local)
            resolveIoFile(asFile)
                ?: LocalFileSystem.getInstance().findFileByPath(local.replace('\\', '/'))
                ?: LocalFileSystem.getInstance().refreshAndFindFileByPath(local.replace('\\', '/'))
        }.getOrNull()
    }
}

/**
 * Parses multi-line path text into individual path strings.
 * One path per line; blanks skipped; surrounding quotes stripped.
 * Pure helper for unit tests (no VFS).
 */
internal fun parsePathLines(text: String): List<String> {
    if (text.isBlank()) return emptyList()
    return text.lineSequence()
        .map { it.trim().trim('"').trim('\'') }
        .filter { it.isNotEmpty() }
        .toList()
}

/**
 * Parses a `text/uri-list` body into local filesystem path strings.
 * Skips `#` comment lines; converts `file:` URIs; ignores other schemes.
 * Bare paths (no scheme) are kept as-is.
 * Pure helper for unit tests (no VFS).
 */
internal fun parseUriList(text: String): List<String> {
    if (text.isBlank()) return emptyList()
    val out = ArrayList<String>()
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) continue
        val path = uriLineToLocalPath(line) ?: continue
        if (path.isNotEmpty()) out.add(path)
    }
    return out
}

/**
 * Converts a single uri-list line to a local path, or null if not a local file reference.
 */
internal fun uriLineToLocalPath(line: String): String? {
    val trimmed = line.trim().trim('"')
    if (trimmed.isEmpty()) return null
    return try {
        when {
            trimmed.startsWith("file:", ignoreCase = true) -> {
                val uri = URI(trimmed)
                if (!uri.scheme.equals("file", ignoreCase = true)) return null
                runCatching { File(uri).absolutePath }.getOrNull()
                    ?: uri.path?.takeIf { it.isNotEmpty() }?.let { path ->
                        // file:///C:/x → /C:/x on some parsers; normalize Windows drive paths
                        if (path.length >= 3 && path[0] == '/' && path[2] == ':') path.substring(1) else path
                    }
            }
            "://" in trimmed -> null // non-file scheme
            else -> trimmed // bare path
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * Pure helper: whether a mime / flavor description looks like a file-list transfer.
 * Used by unit tests and [FileDropUtil] flavor probes.
 */
internal fun isFileishMime(mimeType: String?): Boolean {
    if (mimeType.isNullOrBlank()) return false
    val mime = mimeType.lowercase()
    return mime.startsWith("text/uri-list") ||
        mime.contains("java-file-list") ||
        mime.startsWith("application/x-java-file-list") ||
        mime.startsWith("application/x-java-jvm-local-objectref") ||
        mime == "application/x-java-serialized-object" ||
        mime.startsWith("text/plain")
}

/**
 * Pure helper: extract path-like strings from a heterogeneous list (File / Path / String).
 * Mirrors the javaFileListFlavor item handling without VFS.
 */
internal fun collectPathStringsFromList(items: List<*>?): List<String> {
    if (items.isNullOrEmpty()) return emptyList()
    val out = ArrayList<String>(items.size)
    for (item in items) {
        when (item) {
            is File -> {
                val p = item.path
                if (p.isNotBlank()) out.add(p)
            }
            is Path -> out.add(item.toAbsolutePath().toString())
            is String -> {
                val t = item.trim().trim('"')
                if (t.isNotEmpty()) out.add(t)
            }
        }
    }
    return out
}
