package org.androidaudioplugin.soundpack.sdk

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.system.Os
import android.system.OsConstants
import org.androidaudioplugin.soundpack.PackPath
import org.androidaudioplugin.soundpack.SoundpackContract
import org.androidaudioplugin.soundpack.SoundpackContract.Route
import org.androidaudioplugin.soundpack.SoundpackKind
import java.io.FileNotFoundException

/** The read-only provider side of the contract (docs/PLAN.md §3.3–§3.4), for one [kind].
 *
 * Subclasses list packs, entries and files, and open files. This class implements the URI
 * layout and enforces the rules every provider must follow:
 * - paths are normalized, and dot-files are neither listed nor served;
 * - a file request must name the pack's current revision;
 * - [SoundpackKind.ReadScope.ENTRIES_ONLY] kinds serve only their entry files;
 * - every descriptor handed out is a regular file with a known length;
 * - nothing is writable. */
abstract class SoundpackProvider(val kind: SoundpackKind) : ContentProvider() {
    class PackRecord(
        val id: String,
        val label: String,
        val revision: String,
        val source: String,
        val live: Boolean,
        val summary: String? = null,
        val license: String? = null,
        val fileCount: Int? = null,
        val totalBytes: Long? = null,
    )

    class EntryRecord(
        val path: String,
        val label: String,
        val groupPath: String,
        val scopes: List<String> = emptyList(),
        val metadata: String? = null,
    )

    class FileRecord(val path: String, val size: Long, val lastModified: Long)

    protected abstract fun packs(): List<PackRecord>
    protected abstract fun entries(pack: PackRecord): List<EntryRecord>
    /** Every file of the pack, with normalized paths. The read scope and the dot-file rule are
     * applied here, not by the subclass. */
    protected abstract fun files(pack: PackRecord): List<FileRecord>
    /** Opens [path] (normalized, not hidden, in scope) read-only. Throw FileNotFoundException
     * when it does not exist. */
    protected abstract fun openPackFile(pack: PackRecord, path: String): AssetFileDescriptor

    private lateinit var authority: String

    override fun attachInfo(context: Context, info: ProviderInfo) {
        authority = info.authority.substringBefore(';')
        require(kind.matchesAuthority(authority)) {
            "Authority $authority does not follow the ${kind.id} convention (docs/PLAN.md §3.2)"
        }
        super.attachInfo(context, info)
    }

    override fun onCreate() = true

    /** Tells observing clients that the pack list or a pack's content changed. */
    protected fun notifyPacksChanged() {
        val uri = Uri.Builder().scheme("content").authority(authority).appendPath(SoundpackContract.PATH_PACKS).build()
        context?.contentResolver?.notifyChange(uri, null)
    }

    private fun pack(packId: String) = packs().firstOrNull { it.id == packId }

    private fun visibleFiles(pack: PackRecord): List<FileRecord> {
        val files = files(pack).filter { !PackPath.isHidden(it.path) }
        if (kind.readScope == SoundpackKind.ReadScope.WHOLE_TREE)
            return files
        val entries = entries(pack).map { it.path }.toHashSet()
        return files.filter { it.path in entries }
    }

    final override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                             selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val cursor = when (val route = SoundpackContract.route(uri.pathSegments)) {
            Route.Packs -> MatrixCursor(SoundpackContract.Packs.COLUMNS).apply {
                packs().forEach {
                    addRow(arrayOf<Any?>(it.id, it.label, it.revision, it.source, if (it.live) 1 else 0,
                        it.fileCount, it.totalBytes, it.summary, it.license))
                }
            }
            is Route.Entries -> {
                val pack = pack(route.packId) ?: throw IllegalArgumentException("No pack ${route.packId}")
                MatrixCursor(SoundpackContract.Entries.COLUMNS).apply {
                    entries(pack).filter { !PackPath.isHidden(it.path) }.forEach {
                        addRow(arrayOf<Any?>(it.path, it.label, it.groupPath, it.scopes.joinToString(" "), it.metadata))
                    }
                }
            }
            is Route.Files -> {
                val pack = pack(route.packId) ?: throw IllegalArgumentException("No pack ${route.packId}")
                MatrixCursor(SoundpackContract.Files.COLUMNS).apply {
                    visibleFiles(pack).forEach { addRow(arrayOf<Any>(it.path, it.size, it.lastModified)) }
                }
            }
            else -> throw IllegalArgumentException("Unknown URI: $uri")
        }
        cursor.setNotificationUri(requireNotNull(context).contentResolver, uri)
        return cursor
    }

    final override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
        if (mode != "r")
            throw FileNotFoundException("Soundpack providers are read-only")
        val route = SoundpackContract.route(uri.pathSegments) as? Route.File
            ?: throw FileNotFoundException("Not a file URI: $uri")
        val pack = pack(route.packId) ?: throw FileNotFoundException("No pack ${route.packId}")
        if (pack.revision != route.revision)
            throw FileNotFoundException("Pack ${pack.id} is at revision ${pack.revision}, not ${route.revision}")
        val path = PackPath.normalize(route.path)
            ?: throw FileNotFoundException("Invalid path: ${route.path}")
        if (PackPath.isHidden(path))
            throw FileNotFoundException(path)
        if (kind.readScope == SoundpackKind.ReadScope.ENTRIES_ONLY && entries(pack).none { it.path == path })
            throw FileNotFoundException("$path is not an entry; ${kind.id} packs serve only their entries")
        return regularWithLength(openPackFile(pack, path), path)
    }

    /** Clients may map what they get, so it must be a regular file of known length. A folder
     * provider whose source gives pipes has to copy them first (§3.4). */
    private fun regularWithLength(afd: AssetFileDescriptor, path: String): AssetFileDescriptor {
        val stat = try {
            Os.fstat(afd.fileDescriptor)
        } catch (e: Exception) {
            afd.close()
            throw FileNotFoundException("Cannot stat $path: ${e.message}")
        }
        if (!OsConstants.S_ISREG(stat.st_mode)) {
            afd.close()
            throw FileNotFoundException("$path is not a regular file")
        }
        if (afd.declaredLength >= 0)
            return afd
        // ExternalStorageProvider reports UNKNOWN_LENGTH (S4).
        return AssetFileDescriptor(afd.parcelFileDescriptor, afd.startOffset, stat.st_size - afd.startOffset)
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? = when (method) {
        SoundpackContract.METHOD_INFO -> Bundle().apply {
            putInt(SoundpackContract.KEY_PROTOCOL_VERSION, SoundpackContract.PROTOCOL_VERSION)
            putString(SoundpackContract.KEY_KIND, kind.id)
        }
        else -> super.call(method, arg, extras)
    }

    override fun getType(uri: Uri): String? =
        if (SoundpackContract.route(uri.pathSegments) is Route.File) "application/octet-stream" else null

    final override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Soundpack providers are read-only")

    final override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Soundpack providers are read-only")

    final override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Soundpack providers are read-only")
}
