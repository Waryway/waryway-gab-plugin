package com.waryway.gab.ui

import com.intellij.ide.dnd.DnDEvent
import com.intellij.ide.dnd.FileCopyPasteUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File

/** Extracts VirtualFiles from IDE-internal and OS drag-and-drop transfers. */
object FileDropUtil {

    fun canAccept(event: DnDEvent): Boolean = canAccept(event as Transferable, event.attachedObject)

    fun canAccept(transferable: Transferable): Boolean = canAccept(transferable, null)

    fun extractFiles(event: DnDEvent): List<VirtualFile> = extractFiles(event as Transferable, event.attachedObject)

    fun extractFiles(transferable: Transferable): List<VirtualFile> = extractFiles(transferable, null)

    private fun canAccept(transferable: Transferable, attachedObject: Any?): Boolean = runCatching {
        extractFiles(transferable, attachedObject).isNotEmpty()
    }.getOrDefault(false)

    private fun extractFiles(transferable: Transferable, attachedObject: Any?): List<VirtualFile> {
        val result = linkedSetOf<VirtualFile>()

        if (attachedObject != null) {
            FileCopyPasteUtil.getVirtualFileListFromAttachedObject(attachedObject).forEach { result.add(it) }
            collectVirtualFileObject(attachedObject, result)
        }
        if (result.isNotEmpty()) return result.toList()

        if (FileCopyPasteUtil.isFileListFlavorAvailable(transferable.transferDataFlavors)) {
            FileCopyPasteUtil.getFileList(transferable)?.forEach { file ->
                VfsUtil.findFileByIoFile(file, true)?.let { result.add(it) }
            }
        }

        collectJvmFlavor(transferable, virtualFileFlavor(transferable), result)
        collectJvmFlavor(transferable, arrayVirtualFileFlavor(transferable), result)

        runCatching {
            if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                @Suppress("UNCHECKED_CAST")
                val list = transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<File>
                list?.forEach { file ->
                    LocalFileSystem.getInstance().findFileByIoFile(file)?.let { result.add(it) }
                }
            }
        }

        return result.toList()
    }

    private fun collectVirtualFileObject(attachedObject: Any, result: MutableSet<VirtualFile>) {
        when (attachedObject) {
            is VirtualFile -> result.add(attachedObject)
            is Array<*> -> attachedObject.filterIsInstance<VirtualFile>().forEach { result.add(it) }
            is Collection<*> -> attachedObject.filterIsInstance<VirtualFile>().forEach { result.add(it) }
        }
    }

    private fun virtualFileFlavor(transferable: Transferable): DataFlavor? {
        val flavor = FileCopyPasteUtil.createJvmDataFlavor(VirtualFile::class.java) ?: return null
        return flavor.takeIf { transferable.isDataFlavorSupported(it) }
    }

    private fun arrayVirtualFileFlavor(transferable: Transferable): DataFlavor? {
        val flavor = FileCopyPasteUtil.createJvmDataFlavor(VirtualFile::class.java.arrayType()) ?: return null
        return flavor.takeIf { transferable.isDataFlavorSupported(it) }
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
                is Array<*> -> data.filterIsInstance<VirtualFile>().forEach { result.add(it) }
                is List<*> -> data.filterIsInstance<VirtualFile>().forEach { result.add(it) }
                else -> Unit
            }
        }
    }
}