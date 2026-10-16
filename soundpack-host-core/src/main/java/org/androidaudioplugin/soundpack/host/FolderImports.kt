package org.androidaudioplugin.soundpack.host

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors

/** Imported folders (docs/PLAN.md §3.9): a copy of the files a folder's kinds serve, in this
 * app's storage. Its files then open as plain files, as an APK's assets do, instead of through
 * SAF on every open. The copy follows the folder on each scan, and keeps serving when the
 * folder is gone. */
internal class FolderImports(private val context: Context) {
    class Result(val copied: Int, val error: String?)

    fun directory(folderId: String) = File(context.noBackupFilesDir, "soundpack-imports/$folderId")

    /** The copy of [file], if it is complete. */
    fun copyOf(folderId: String, file: FolderIndex.FileRow): File? =
        File(directory(folderId), file.path).takeIf { it.isFile && it.length() == file.size }

    /** Makes the copy match [files]: deletes what is no longer served, then copies what is
     * missing or changed, [THREADS] at a time. A file that cannot be copied is served from the
     * folder until a later scan copies it. */
    fun sync(folderId: String, tree: Uri, files: List<FolderIndex.FileRow>): Result {
        val root = directory(folderId).apply { mkdirs() }
        val wanted = files.associateBy { it.path }
        // Dot-files are never pack files (the scanner skips them), so they are leftover partial copies.
        root.walkBottomUp().filter { it != root }.forEach { file ->
            val path = file.relativeTo(root).invariantSeparatorsPath
            if (file.isFile && (path !in wanted || file.name.startsWith('.')))
                file.delete()
            else if (file.isDirectory && file.list().isNullOrEmpty())
                file.delete()
        }
        val missing = files.filter { file -> File(root, file.path).let { !(it.isFile && it.length() == file.size && it.lastModified() == file.lastModified) } }
        if (missing.isEmpty())
            return Result(0, null)
        val needed = missing.sumOf { it.size }
        if (needed > root.usableSpace)
            return Result(0, "Not enough space to import: needs ${megabytes(needed)} MB, ${megabytes(root.usableSpace)} MB free")
        val pool = Executors.newFixedThreadPool(THREADS) { Thread(it, "soundpack-import").apply { isDaemon = true } }
        val failures = try {
            missing.map { file -> pool.submit<String?> { runCatching { copy(tree, file, File(root, file.path)) }.exceptionOrNull()?.let { "${file.path}: ${it.message}" } } }
                .mapNotNull { it.get() }
        } finally {
            pool.shutdownNow()
        }
        return Result(missing.size - failures.size,
            failures.firstOrNull()?.let { if (failures.size == 1) "Cannot copy $it" else "Cannot copy ${failures.size} files, e.g. $it" })
    }

    fun delete(folderId: String) {
        directory(folderId).deleteRecursively()
    }

    private fun copy(tree: Uri, file: FolderIndex.FileRow, target: File) {
        val parent = requireNotNull(target.parentFile).apply { mkdirs() }
        val partial = File(parent, ".${target.name}.${UUID.randomUUID()}")
        try {
            val document = DocumentsContract.buildDocumentUriUsingTree(tree, file.documentId)
            (context.contentResolver.openInputStream(document) ?: throw IOException("cannot open it")).use { input ->
                partial.outputStream().use { input.copyTo(it, BUFFER_BYTES) }
            }
            if (partial.length() != file.size)
                throw IOException("it changed while it was copied")
            partial.setLastModified(file.lastModified)
            if (!partial.renameTo(target))
                throw IOException("cannot store the copy")
        } finally {
            partial.delete()
        }
    }

    private fun megabytes(bytes: Long) = bytes / (1024 * 1024)

    private companion object {
        const val THREADS = 4
        const val BUFFER_BYTES = 256 * 1024
    }
}
