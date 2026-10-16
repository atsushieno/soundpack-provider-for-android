package org.androidaudioplugin.soundpack.ndk

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import org.androidaudioplugin.soundpack.SoundpackIdentity
import org.androidaudioplugin.soundpack.SoundpackKind
import org.androidaudioplugin.soundpack.client.PackSession
import org.androidaudioplugin.soundpack.client.SoundpackClient
import org.androidaudioplugin.soundpack.host.FolderRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** The Kotlin side of `soundpack.h` and `soundpack_host.h`, called from C through JNI.
 *
 * Strings cross as UTF-8 byte arrays, several of them as NUL-terminated fields one after
 * another ([pack]), so that names outside the BMP survive (JNI's own strings are "modified
 * UTF-8"). Exceptions are turned into `soundpack_last_error()` on the C side. */
internal object SoundpackNative {
    private lateinit var context: Context
    private val clients = ConcurrentHashMap<SoundpackKind, SoundpackClient>()

    /** Fields per entry in [listEntries]. */
    private const val ENTRY_FIELDS = 6
    /** Fields per folder in [hostFolders]. */
    private const val FOLDER_FIELDS = 8

    private fun pack(values: List<String>): ByteArray = ByteArrayOutputStream().apply {
        for (value in values) {
            write(value.toByteArray(Charsets.UTF_8))
            write(0)
        }
    }.toByteArray()

    private fun string(bytes: ByteArray) = bytes.toString(Charsets.UTF_8)

    private fun kind(id: String) = SoundpackKind.forId(id.also { require(SoundpackKind.isValidId(it)) { "Not a soundpack kind: $it" } })

    /** Kinds this app links folders as: built-in ones it declares, or types its user added. */
    private fun kinds(list: String) = list.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        .map { requireNotNull(FolderRegistry.get(context).kind(it)) { "This app does not link folders as $it" } }.toSet()

    private fun client(kind: SoundpackKind) = clients.getOrPut(kind) { SoundpackClient(context, kind) }

    @JvmStatic
    fun init(context: Context) {
        this.context = context.applicationContext
    }

    @JvmStatic
    fun entryFields() = ENTRY_FIELDS

    @JvmStatic
    fun folderFields() = FOLDER_FIELDS

    /** Every entry of [kindId]: identity, pack label, entry path, label, group path, metadata
     * ("" when none). Providers that cannot be read are skipped. */
    @JvmStatic
    fun listEntries(kindId: ByteArray): ByteArray {
        val client = client(kind(string(kindId)))
        val fields = mutableListOf<String>()
        for (pack in client.catalog().packs) {
            val entries = runCatching { client.entries(pack) }.getOrDefault(emptyList())
            for (entry in entries)
                fields += listOf(client.identityOf(entry).format(), pack.label, entry.path, entry.label, entry.groupPath, entry.metadata.orEmpty())
        }
        return pack(fields)
    }

    @JvmStatic
    fun formatIdentity(kindId: ByteArray, packId: ByteArray, entryPath: ByteArray): ByteArray =
        SoundpackIdentity(kind(string(kindId)).id, string(packId), string(entryPath), revision = null, via = null)
            .format().toByteArray(Charsets.UTF_8)

    @JvmStatic
    fun open(identity: ByteArray): Any {
        val text = string(identity)
        val parsed = requireNotNull(SoundpackIdentity.parse(text)) { "Not a soundpack identity: $text" }
        return client(kind(parsed.kind)).open(parsed)
    }

    /** The entry path, then every file path of the session's pack. */
    @JvmStatic
    fun sessionInfo(session: Any): ByteArray {
        session as PackSession
        return pack(listOf(session.entryPath) + session.files.map { it.path })
    }

    /** A descriptor the caller owns: fd, start offset, length. */
    @JvmStatic
    fun openFd(session: Any, path: ByteArray): LongArray {
        (session as PackSession).openFile(string(path)).use { afd ->
            val fd = ParcelFileDescriptor.dup(afd.fileDescriptor).detachFd()
            return longArrayOf(fd.toLong(), afd.startOffset, afd.declaredLength)
        }
    }

    @JvmStatic
    fun prefetch(session: Any, paths: ByteArray) {
        val list = if (paths.isEmpty()) emptyList() else string(paths).trimEnd('\u0000').split('\u0000')
        (session as PackSession).prefetch(list)
    }

    @JvmStatic
    fun close(session: Any) = (session as PackSession).close()

    /** written, deleted, unchanged. */
    @JvmStatic
    fun materialize(kindId: ByteArray, packId: ByteArray, destination: ByteArray): IntArray {
        val client = client(kind(string(kindId)))
        val id = string(packId)
        val pack = requireNotNull(client.catalog().packs.firstOrNull { it.id == id }) { "No ${string(kindId)} pack $id" }
        val result = client.materialize(pack, File(string(destination)))
        return intArrayOf(result.written, result.deleted, result.unchanged)
    }

    /** Every linked folder: id, display name, tree URI, kinds ("sf2,sfz"), "1" when available
     * else "0", file count, the last scan error ("" when none), and "1" when imported else "0". */
    @JvmStatic
    fun hostFolders(): ByteArray = pack(FolderRegistry.get(context).folders().flatMap { folder ->
        listOf(folder.id, folder.displayName, folder.treeUri.toString(), folder.kinds.joinToString(",") { it.id },
            if (folder.available) "1" else "0", folder.fileCount.toString(), folder.scanError.orEmpty(),
            if (folder.imported) "1" else "0")
    })

    /** Links (and scans, and with [import] copies) a folder the app holds a grant on; returns its id. */
    @JvmStatic
    fun hostAddFolder(treeUri: ByteArray, kindList: ByteArray, import: Boolean): ByteArray =
        FolderRegistry.get(context).add(Uri.parse(string(treeUri)), kinds(string(kindList)), import).id.toByteArray(Charsets.UTF_8)

    @JvmStatic
    fun hostSetImported(id: ByteArray, imported: Boolean) {
        FolderRegistry.get(context).setImported(string(id), imported)
    }

    @JvmStatic
    fun hostRemoveFolder(id: ByteArray) = FolderRegistry.get(context).remove(string(id))

    @JvmStatic
    fun hostSetKinds(id: ByteArray, kindList: ByteArray) {
        FolderRegistry.get(context).setKinds(string(id), kinds(string(kindList)))
    }

    /** An empty [id] rescans every folder. */
    @JvmStatic
    fun hostRescan(id: ByteArray) {
        FolderRegistry.get(context).rescan(string(id).ifEmpty { null })
    }
}
