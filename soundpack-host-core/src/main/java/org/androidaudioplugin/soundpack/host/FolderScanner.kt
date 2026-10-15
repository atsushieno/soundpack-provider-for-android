package org.androidaudioplugin.soundpack.host

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.Log
import org.androidaudioplugin.soundpack.PackPath
import org.androidaudioplugin.soundpack.Sf2Presets
import org.androidaudioplugin.soundpack.SoundpackKind
import org.androidaudioplugin.soundpack.sdk.AssetFileSource
import java.io.IOException
import java.security.MessageDigest

/** Walks a linked SAF tree. Document IDs are opaque handles, never paths: the pack-relative
 * path is built from display names, as sfizz's SfzFolders did. */
internal class FolderScanner(private val resolver: ContentResolver) {
    class Scan(val displayName: String, val files: List<FolderIndex.FileRow>) {
        /** Changes whenever a file is added, removed, resized or touched. */
        val contentHash: String by lazy {
            val digest = MessageDigest.getInstance("SHA-256")
            files.forEach { digest.update("${it.path}\t${it.size}\t${it.lastModified}\n".toByteArray()) }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }

    fun scan(tree: Uri): Scan {
        val files = mutableListOf<FolderIndex.FileRow>()
        val visited = HashSet<String>()
        fun walk(documentId: String, prefix: String, depth: Int) {
            if (depth > PackPath.MAX_DEPTH || !visited.add(documentId))
                throw IOException("Folder is too deep or contains a cycle")
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
            val cursor = resolver.query(children, COLUMNS, null, null, null)
                ?: throw IOException("Cannot read the folder. Link it again.")
            cursor.use {
                while (it.moveToNext()) {
                    val name = it.getString(1) ?: continue
                    // Hidden files (AppleDouble, .DS_Store, .git) are never part of a pack.
                    if (name.startsWith('.') || !PackPath.isValidName(name))
                        continue
                    val path = if (prefix.isEmpty()) name else "$prefix/$name"
                    if (path.length > PackPath.MAX_LENGTH)
                        continue
                    if (it.getString(2) == Document.MIME_TYPE_DIR)
                        walk(it.getString(0), path, depth + 1)
                    else {
                        if (files.size >= MAX_FILES)
                            throw IOException("Folder has more than $MAX_FILES files")
                        files += FolderIndex.FileRow(path, it.getString(0),
                            if (it.isNull(3)) 0 else it.getLong(3), if (it.isNull(4)) 0 else it.getLong(4))
                    }
                }
            }
        }
        walk(DocumentsContract.getTreeDocumentId(tree), "", 0)
        return Scan(displayName(tree), files.sortedBy { it.path })
    }

    fun displayName(tree: Uri): String {
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return runCatching {
            resolver.query(root, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: DocumentsContract.getTreeDocumentId(tree).substringAfterLast(':').substringAfterLast('/')
    }

    /** The entries [kind] finds among [files], with kind-specific metadata. */
    fun entries(tree: Uri, kind: SoundpackKind, files: List<FolderIndex.FileRow>): List<FolderIndex.EntryRow> =
        files.filter { isEntry(tree, kind, it) }.map { file ->
            FolderIndex.EntryRow(kind.id, file.path, PackPath.stem(file.path), PackPath.parent(file.path),
                if (kind == SoundpackKind.SF2) sf2Metadata(tree, file) else null)
        }

    /** Name-based for most kinds. Extensionless JSFX candidates are confirmed by a `desc:` line
     * near the top, so that README and LICENSE files do not turn into effects; the client's
     * own JSFX parser still has the final word (§3.9). */
    fun isEntry(tree: Uri, kind: SoundpackKind, file: FolderIndex.FileRow): Boolean {
        if (!kind.isEntryCandidate(file.path))
            return false
        if (kind != SoundpackKind.JSFX || PackPath.extension(file.path).isNotEmpty())
            return true
        return sniff(tree, file)?.lineSequence()?.any { it.trimStart().startsWith("desc:") } == true
    }

    private fun sniff(tree: Uri, file: FolderIndex.FileRow): String? = try {
        resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, file.documentId))?.use { input ->
            val buffer = ByteArray(SNIFF_BYTES)
            val count = input.readNBytesCompat(buffer)
            String(buffer, 0, count, Charsets.ISO_8859_1)
        }
    } catch (e: Exception) {
        null
    }

    private fun sf2Metadata(tree: Uri, file: FolderIndex.FileRow): String? = try {
        resolver.openAssetFileDescriptor(DocumentsContract.buildDocumentUriUsingTree(tree, file.documentId), "r")?.let { afd ->
            AssetFileSource(afd).use { Sf2Presets.toJson(Sf2Presets.read(it)) }
        }
    } catch (e: Exception) {
        Log.w(LOG_TAG, "No presets for ${file.path}: ${e.message}")
        null
    }

    private fun java.io.InputStream.readNBytesCompat(buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val count = read(buffer, total, buffer.size - total)
            if (count < 0) break
            total += count
        }
        return total
    }

    companion object {
        const val MAX_FILES = 65536
        private const val SNIFF_BYTES = 4096
        private const val LOG_TAG = "SoundpackHost"
        private val COLUMNS = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)
    }
}
