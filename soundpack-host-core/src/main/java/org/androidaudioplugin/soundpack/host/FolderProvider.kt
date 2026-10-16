package org.androidaudioplugin.soundpack.host

import android.content.Context
import android.content.pm.ProviderInfo
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.DocumentsContract
import android.system.Os
import android.system.OsConstants
import org.androidaudioplugin.soundpack.SoundpackContract
import org.androidaudioplugin.soundpack.SoundpackKind
import org.androidaudioplugin.soundpack.sdk.SoundpackProvider
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Serves the folders linked in [FolderRegistry] for one kind, from the index (§3.9).
 *
 * Embedding apps declare a `Local<Kind>FolderProvider` with the
 * `${applicationId}.soundpack.local.<kind>` authority, **not exported**: the folders are this
 * app's, granted by its user to this app alone. The hub exports the same code, so that the
 * folders shared there reach every soundpack app (§3.6). */
abstract class FolderProvider(kind: SoundpackKind) : SoundpackProvider(kind) {
    private val registry by lazy { FolderRegistry.get(requireNotNull(context)) }
    private val index by lazy { FolderIndex.get(requireNotNull(context)) }
    private val imports by lazy { FolderImports(requireNotNull(context)) }

    /** What file opens need from one folder, kept in memory. A local provider runs in the
     * client's process, where sfizz opens 8 files at a time, and any SQLite query on every open
     * made those opens several times slower than one at a time (Phase 7). A write from this
     * process is seen at once ([FolderIndex.writes]); one from another process, such as a
     * rescan in the app's other process, within [STAMP_CHECK_MILLIS] ([FolderIndex.stamp]). */
    private class Served(val stamp: Long, val row: FolderIndex.FolderRow, val files: Map<String, FolderIndex.FileRow>, val entries: Set<String>,
                         @Volatile var writes: Long, @Volatile var checkedAt: Long)
    private val served = ConcurrentHashMap<String, Served>()

    private fun served(packId: String): Served? {
        val writes = index.writes
        val now = SystemClock.elapsedRealtime()
        served[packId]?.takeIf { it.writes == writes && now - it.checkedAt < STAMP_CHECK_MILLIS }?.let { return it }
        val stamp = index.stamp(packId) ?: return null.also { served.remove(packId) }
        served[packId]?.takeIf { it.stamp == stamp }?.let {
            it.writes = writes
            it.checkedAt = now
            return it
        }
        val row = index.folder(packId) ?: return null
        return Served(stamp, row, index.files(packId).associateBy { it.path }, index.entries(packId, kind.id).mapTo(HashSet()) { it.path },
            writes, now).also { served[packId] = it }
    }

    override fun attachInfo(context: Context, info: ProviderInfo) {
        val local = info.authority.split(';').any { it.endsWith(".soundpack.local.${kind.id}") }
        check(!(local && info.exported)) { "${javaClass.name} serves this app's private folders: it must not be exported" }
        super.attachInfo(context, info)
    }

    override fun packs(): List<PackRecord> {
        registry.ensureScannedOnce()
        return index.folders().mapNotNull { packOf(it) }
    }

    /** Every file request names its pack: answer from that one folder, not from all of them,
     * and without the counts, which only the pack list shows. */
    override fun pack(packId: String): PackRecord? = served(packId)
        ?.takeIf { servable(it.row) && it.entries.isNotEmpty() }
        ?.let { PackRecord(it.row.id, it.row.displayName, it.row.revision.toString(), SoundpackContract.SOURCE_FOLDER, live = true) }

    override fun hasEntry(pack: PackRecord, path: String) = served(pack.id)?.entries?.contains(path) == true

    /** An imported folder serves from its copy when the folder itself cannot be read. */
    private fun servable(row: FolderIndex.FolderRow) = kind.id in row.kinds && (row.scanError == null || row.imported)

    private fun packOf(row: FolderIndex.FolderRow): PackRecord? {
        if (!servable(row))
            return null
        val (entries, entryBytes) = index.entryStats(row.id, kind.id)
        // A folder with nothing of this kind is not a pack of this kind (the folder UI still
        // lists it, with a warning).
        if (entries == 0)
            return null
        val entriesOnly = kind.readScope == SoundpackKind.ReadScope.ENTRIES_ONLY
        return PackRecord(
            id = row.id, label = row.displayName, revision = row.revision.toString(),
            source = SoundpackContract.SOURCE_FOLDER, live = true,
            fileCount = if (entriesOnly) entries else row.fileCount,
            totalBytes = if (entriesOnly) entryBytes else row.totalBytes,
        )
    }

    override fun entries(pack: PackRecord): List<EntryRecord> =
        index.entries(pack.id, kind.id).map { EntryRecord(it.path, it.label, it.groupPath, emptyList(), it.metadata) }

    override fun files(pack: PackRecord): List<FileRecord> =
        index.files(pack.id).map { FileRecord(it.path, it.size, it.lastModified) }

    override fun openPackFile(pack: PackRecord, path: String): AssetFileDescriptor {
        val folder = served(pack.id) ?: throw FileNotFoundException("No folder ${pack.id}")
        val file = folder.files[path] ?: throw FileNotFoundException(path)
        // An imported file opens like an APK asset: a plain file of this app, no SAF.
        if (folder.row.imported)
            imports.copyOf(folder.row.id, file)?.let { copy ->
                return AssetFileDescriptor(ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY), 0, copy.length())
            }
        val document = DocumentsContract.buildDocumentUriUsingTree(Uri.parse(folder.row.treeUri), file.documentId)
        // Open the document as this app, not with the calling client's identity.
        val token = Binder.clearCallingIdentity()
        try {
            val afd = requireNotNull(context).contentResolver.openAssetFileDescriptor(document, "r")
                ?: throw FileNotFoundException(path)
            return if (isRegularFile(afd)) afd else copyToCache(afd, path)
        } catch (e: SecurityException) {
            throw FileNotFoundException("$path: ${e.message}")
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? = when (method) {
        SoundpackContract.METHOD_RESCAN -> Bundle().also { registry.rescanInBackground(arg) }
        else -> super.call(method, arg, extras)
    }

    private fun isRegularFile(afd: AssetFileDescriptor) =
        runCatching { OsConstants.S_ISREG(Os.fstat(afd.fileDescriptor).st_mode) }.getOrDefault(false)

    /** A cloud provider may hand out a pipe, which clients cannot map. Copy just this file and
     * serve the copy (§3.4). The copy is unlinked at once; the descriptor keeps it alive. */
    private fun copyToCache(afd: AssetFileDescriptor, path: String): AssetFileDescriptor {
        val directory = File(requireNotNull(context).cacheDir, "soundpack-streams").apply { mkdirs() }
        val copy = File.createTempFile("stream-", ".tmp", directory)
        try {
            afd.createInputStream().use { input ->
                copy.outputStream().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_STREAM_COPY)
                            throw IOException("$path is larger than ${MAX_STREAM_COPY shr 30} GiB")
                        output.write(buffer, 0, count)
                    }
                }
            }
            return AssetFileDescriptor(ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY), 0, copy.length())
        } finally {
            afd.close()
            copy.delete()
        }
    }

    private companion object {
        /** How long a folder's cached file list is used before its stamp is read again. */
        const val STAMP_CHECK_MILLIS = 250L
        const val MAX_STREAM_COPY = 8L shl 30
    }
}

/** The Soundpack Manager's providers: `${applicationId}.soundpack.<kind>`, exported with the
 * kind's discovery action, and readable by every app like a pack app's provider (§3.6). */
class SfzFolderProvider : FolderProvider(SoundpackKind.SFZ)

class Sf2FolderProvider : FolderProvider(SoundpackKind.SF2)

class JsfxFolderProvider : FolderProvider(SoundpackKind.JSFX)

/** Declare with `android:authorities="${applicationId}.soundpack.local.sfz"` and
 * `android:exported="false"`. */
class LocalSfzFolderProvider : FolderProvider(SoundpackKind.SFZ)

class LocalSf2FolderProvider : FolderProvider(SoundpackKind.SF2)

class LocalJsfxFolderProvider : FolderProvider(SoundpackKind.JSFX)
