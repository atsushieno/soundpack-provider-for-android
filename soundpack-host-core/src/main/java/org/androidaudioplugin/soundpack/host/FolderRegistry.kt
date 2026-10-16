package org.androidaudioplugin.soundpack.host

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.util.Log
import org.androidaudioplugin.soundpack.SoundpackContract
import org.androidaudioplugin.soundpack.SoundpackKind
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** The UI-free host API (docs/PLAN.md §3.9): the folders this app has linked, for the kinds
 * it serves through [FolderProvider]s. The hub and every embedding app use the same code; an
 * app with its own UI toolkit (ImGui, JUCE) builds its folder panel on this.
 *
 * Methods that scan are blocking: call them off the main thread. */
class FolderRegistry private constructor(context: Context) {
    private val context: Context = context.applicationContext
    private val index = FolderIndex.get(this.context)
    private val scanner = FolderScanner(this.context.contentResolver)
    private val imports = FolderImports(this.context)
    /** The resource types, as this app's user edited them. */
    val types = ResourceTypes.get(this.context)
    private val scanLock = Any()
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "soundpack-folder-scan").apply { isDaemon = true } }
    private val startupScanDone = AtomicBoolean(false)
    /** Folders already walked in this process; the startup rescan skips them. */
    private val scannedInProcess = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    private val walks = AtomicInteger()

    data class Folder(
        /** The folder's pack id: `saf-<hash>`, derived from the folder itself (§3.7). */
        val id: String,
        val treeUri: Uri,
        val displayName: String,
        val kinds: Set<SoundpackKind>,
        val revision: Long,
        val scannedAt: Long,
        /** Why the last scan failed (grant revoked, folder gone); null when it is available. */
        val scanError: String?,
        val fileCount: Int,
        val totalBytes: Long,
        /** Imported: served from a copy in this app's storage, which follows the folder on each
         * scan. Otherwise the folder is used in place, through SAF. */
        val imported: Boolean,
        /** Why the copy of an imported folder is incomplete; its missing files are served from
         * the folder meanwhile. */
        val importError: String?,
    ) {
        /** An imported folder stays available from its copy when the folder itself is gone. */
        val available: Boolean get() = scanError == null || imported
    }

    /** How many times a folder has been walked in this process; for diagnostics and tests. */
    val walkCount: Int get() = walks.get()

    /** The built-in kinds this app declares a [FolderProvider] for, with its authority. */
    private val served: List<Pair<SoundpackKind, String>> by lazy {
        declaredProviders().mapNotNull { (authority, type) ->
            val kind = SoundpackKind.BUILT_IN.firstOrNull { it.matchesAuthority(authority) } ?: return@mapNotNull null
            if (FolderProvider::class.java.isAssignableFrom(type)) kind to authority else null
        }
    }

    /** The authority of this app's [TypesFolderProvider], which serves the types users added. */
    private val typesAuthority: String? by lazy {
        declaredProviders().firstOrNull { (authority, type) ->
            SoundpackContract.Types.matchesAuthority(authority) && TypesFolderProvider::class.java.isAssignableFrom(type)
        }?.first
    }

    private fun declaredProviders(): List<Pair<String, Class<*>>> {
        @Suppress("DEPRECATION")
        val providers = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PROVIDERS).providers.orEmpty()
        return providers.mapNotNull { info ->
            val authority = info.authority?.substringBefore(';') ?: return@mapNotNull null
            runCatching { Class.forName(info.name, false, context.classLoader) }.getOrNull()?.let { authority to it }
        }
    }

    /** The kinds this app serves from linked folders, as its user sees them ([types]): one per
     * declared [FolderProvider], and with a [TypesFolderProvider], every type the user added. */
    fun servedKinds(): List<SoundpackKind> =
        served.map { types.kind(it.first.id) ?: it.first }.distinct() + if (typesAuthority != null) types.added() else emptyList()

    /** A kind this app serves, by id; null for any other. */
    fun kind(id: String): SoundpackKind? = servedKinds().firstOrNull { it.id == id }

    /** After a type changed: folders linked as it are scanned again, since its file types decide
     * the entries. Blocking; call it off the main thread. */
    fun typeChanged(id: String) {
        rescan(index.folders().filter { id in it.kinds }.map { it.id })
    }

    /** Removes an added type, and stops using every folder as it. */
    fun removeType(id: String) {
        index.folders().filter { id in it.kinds }.forEach { removeKind(it.id, types.kind(id) ?: SoundpackKind.forId(id)) }
        types.remove(id)
        notifyChanged()
    }

    fun folders(): List<Folder> = index.folders().map { it.toFolder() }

    fun folder(id: String): Folder? = index.folder(id)?.toFolder()

    /** How many entries [kind] has in the folder, as of its last scan. */
    fun entryCount(id: String, kind: SoundpackKind): Int = index.entryStats(id, kind.id).first

    /** Links [tree] for [kinds] and scans it, and with [import], copies what those kinds serve
     * into this app's storage. The user chooses the kinds first and then picks the folder (D6):
     * nothing is guessed from the folder's content. The app must hold a grant from the system
     * folder picker; it is made persistent here. Adding a folder again adds kinds to it, and
     * [import] then applies to the whole folder. */
    fun add(tree: Uri, kinds: Set<SoundpackKind>, import: Boolean = false): Folder {
        require(DocumentsContract.isTreeUri(tree)) { "Not a folder (tree) URI: $tree" }
        val served = servedKinds()
        require(kinds.isNotEmpty() && served.containsAll(kinds)) { "This app serves $served from folders; asked for $kinds" }
        context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val id = packIdFor(tree)
        val existing = index.folder(id)
        if (existing == null)
            index.insertFolder(id, tree.toString(), scanner.displayName(tree), kinds.map { it.id }, import)
        else {
            index.setKinds(id, existing.kinds.toSet() + kinds.map { it.id })
            if (existing.imported != import)
                setImportedRow(id, import)
        }
        scan(id)
        notifyChanged()
        return requireNotNull(folder(id))
    }

    /** Changes which kinds the folder is shared as, and rescans it. */
    fun setKinds(id: String, kinds: Set<SoundpackKind>): Folder {
        require(kinds.isNotEmpty() && servedKinds().containsAll(kinds)) { "Unsupported kinds $kinds" }
        requireNotNull(index.folder(id)) { "No folder $id" }
        index.setKinds(id, kinds.map { it.id })
        scan(id)
        notifyChanged()
        return requireNotNull(folder(id))
    }

    /** Stops using the folder as [kind]; the folder is removed when no kind is left. */
    fun removeKind(id: String, kind: SoundpackKind) {
        val row = index.folder(id) ?: return
        val kinds = row.kinds.map { SoundpackKind.forId(it) }.toSet() - kind
        if (kinds.isEmpty())
            remove(id)
        else
            setKinds(id, kinds)
    }

    /** Switches the folder between imported (copied into this app's storage) and in place.
     * Importing copies it now, which can take long for a large folder. */
    fun setImported(id: String, imported: Boolean): Folder {
        requireNotNull(index.folder(id)) { "No folder $id" }
        setImportedRow(id, imported)
        scan(id)
        notifyChanged()
        return requireNotNull(folder(id))
    }

    private fun setImportedRow(id: String, imported: Boolean) = synchronized(scanLock) {
        index.setImported(id, imported)
        if (!imported)
            imports.delete(id)
    }

    /** Unlinks the folder, deletes its imported copy and gives its grant back. Saved
     * identities pointing into it become unavailable (or resolve through the hub, if the hub
     * has the same folder). */
    fun remove(id: String) {
        // Wait for a running scan, so that it cannot write files into a removed folder.
        val row = synchronized(scanLock) {
            val row = index.folder(id) ?: return
            index.deleteFolder(id)
            imports.delete(id)
            row
        }
        runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(row.treeUri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        notifyChanged()
    }

    /** Rescans one folder, or every folder. Returns the folders whose content changed. */
    fun rescan(id: String? = null): List<Folder> = rescan(if (id != null) listOf(id) else index.folders().map { it.id })

    private fun rescan(ids: List<String>): List<Folder> {
        val changed = ids.filter { scan(it) }
        notifyChanged()
        return changed.mapNotNull { folder(it) }
    }

    fun rescanInBackground(id: String? = null) = executor.execute {
        try {
            rescan(id)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Rescan failed: ${e.message}")
        }
    }

    /** The first provider query in a process rescans every folder in the background ("when the
     * host starts", §3.9); queries meanwhile answer from the existing index. */
    internal fun ensureScannedOnce() {
        if (startupScanDone.compareAndSet(false, true))
            executor.execute {
                index.folders().map { it.id }.filter { it !in scannedInProcess }.forEach {
                    try {
                        if (scan(it)) notifyChanged()
                    } catch (e: Exception) {
                        Log.w(LOG_TAG, "Startup rescan failed: ${e.message}")
                    }
                }
            }
    }

    /** Blocks until scans queued in the background so far have finished; for tests and for
     * callers that want fresh results right after linking. */
    fun awaitBackgroundScans(timeoutMillis: Long = 60_000) {
        executor.submit { }.get(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    /** Calls [listener] on the main thread whenever folders or their content change, in any
     * process of this app. Close the result to stop. */
    fun observe(listener: () -> Unit): AutoCloseable {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = listener()
        }
        served.forEach { (_, authority) -> context.contentResolver.registerContentObserver(packsUri(authority), true, observer) }
        typesAuthority?.let { authority ->
            context.contentResolver.registerContentObserver(
                Uri.Builder().scheme("content").authority(authority).appendPath(SoundpackContract.Types.PATH).build(), true, observer)
        }
        val types = types.observe { Handler(Looper.getMainLooper()).post(listener) }
        return AutoCloseable {
            context.contentResolver.unregisterContentObserver(observer)
            types.close()
        }
    }

    /** An intent for [SoundpackFolderPickerActivity]: the system picker, then linking for
     * [kinds], which the user chose before (D6), imported or in place. Start it from one of
     * your own activities in the foreground. Never start it from a UI that another app is
     * showing (an AAP plugin UI in a host): Android blocks that, and the design does not ask
     * hosts to lift it (docs/PLAN.md §3.9, "Where setup happens"). */
    fun pickFolderIntent(kinds: Set<SoundpackKind>, import: Boolean = false, initialUri: Uri? = null): Intent =
        Intent(context, SoundpackFolderPickerActivity::class.java).apply {
            putExtra(EXTRA_KINDS, kinds.map(SoundpackKind::id).toTypedArray())
            putExtra(EXTRA_IMPORT, import)
            initialUri?.let { putExtra(EXTRA_INITIAL_URI, it) }
        }

    /** "Share with Soundpack Manager" (§3.9): hands this folder's grant to the hub, which asks
     * the user which kinds to share it as. Null when the hub is not installed, or when this app
     * is the hub. Start it with startActivity; the hub keeps its own grant from then on (S6),
     * and the folder keeps its pack id, so it appears once in catalogs. */
    fun shareWithHubIntent(id: String): Intent? {
        if (context.packageName == SoundpackContract.Hub.PACKAGE)
            return null
        val folder = folder(id) ?: return null
        val intent = Intent(SoundpackContract.Hub.ACTION_SHARE_FOLDER).apply {
            setPackage(SoundpackContract.Hub.PACKAGE)
            // In the ClipData rather than the data, so the intent matches the hub's filter
            // without a MIME type; grants cover ClipData URIs too. PREFIX is needed (S6).
            clipData = ClipData.newRawUri(folder.displayName, folder.treeUri)
            putExtra(SoundpackContract.Hub.EXTRA_KINDS, folder.kinds.map { it.id }.toTypedArray())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }
        @Suppress("DEPRECATION")
        return intent.takeIf { context.packageManager.resolveActivity(it, 0) != null }
    }

    /** Returns true when the folder's content or availability changed. */
    private fun scan(id: String): Boolean = synchronized(scanLock) {
        val row = index.folder(id) ?: return false
        val tree = Uri.parse(row.treeUri)
        val now = System.currentTimeMillis()
        try {
            val scan = scanner.scan(tree).also { walks.incrementAndGet() }
            scannedInProcess += id
            val kinds = row.kinds.mapNotNull { kind(it) }
            val previousFiles = index.files(id).associateBy { it.path }
            // The stored hash ends with the kinds that scan covered, with their file types.
            val previouslyScanned = index.contentHash(id)?.substringAfter('|', "")?.split(' ')?.toSet().orEmpty()
            val entries = kinds.flatMap { kind ->
                if (scanToken(kind) !in previouslyScanned)
                    return@flatMap scanner.entries(tree, kind, scan.files)
                val previous = index.entries(id, kind.id).associateBy { it.path }
                // Unchanged files keep their entry decision and metadata (SF2 presets are not reread).
                val (unchanged, changed) = scan.files.partition { file ->
                    previousFiles[file.path]?.let { it.size == file.size && it.lastModified == file.lastModified } == true
                }
                unchanged.mapNotNull { previous[it.path] } + scanner.entries(tree, kind, changed)
            }
            val hash = scan.contentHash + "|" + kinds.map { scanToken(it) }.sorted().joinToString(" ")
            val changed = index.replaceContent(id, scan.displayName, scan.files, entries, hash, now) || row.scanError != null
            if (row.imported && index.folder(id) != null) {
                val result = imports.sync(id, tree, servedFiles(kinds, scan.files, entries))
                index.setImportError(id, result.error)
                if (result.copied > 0)
                    Log.i(LOG_TAG, "Imported ${result.copied} files of ${scan.displayName}")
            }
            changed
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Cannot scan ${row.displayName}: ${e.message}")
            index.setScanError(id, e.message ?: e.javaClass.simpleName, now)
            row.scanError == null
        }
    }

    /** What the folder's providers serve, so what an import copies: every file when a kind
     * reads the whole tree (SFZ samples, JSFX imports), else only the entries. */
    private fun servedFiles(kinds: List<SoundpackKind>, files: List<FolderIndex.FileRow>, entries: List<FolderIndex.EntryRow>) =
        if (kinds.any { it.readScope == SoundpackKind.ReadScope.WHOLE_TREE }) files
        else entries.mapTo(HashSet()) { it.path }.let { paths -> files.filter { it.path in paths } }

    /** A kind's id with its file types and read scope: a scan with other ones decides entries
     * anew. */
    private fun scanToken(kind: SoundpackKind) =
        kind.id + ":" + Integer.toHexString((kind.filePatterns.joinToString("\n") + kind.readScope).hashCode())

    private fun notifyChanged() {
        served.forEach { (_, authority) -> context.contentResolver.notifyChange(packsUri(authority), null) }
        typesAuthority?.let { authority ->
            val root = Uri.Builder().scheme("content").authority(authority).appendPath(SoundpackContract.Types.PATH).build()
            context.contentResolver.notifyChange(root, null)
            types.added().forEach { kind ->
                context.contentResolver.notifyChange(root.buildUpon().appendPath(kind.id).appendPath(SoundpackContract.PATH_PACKS).build(), null)
            }
        }
    }

    private fun packsUri(authority: String) =
        Uri.Builder().scheme("content").authority(authority).appendPath(SoundpackContract.PATH_PACKS).build()

    private fun FolderIndex.FolderRow.toFolder() = Folder(id, Uri.parse(treeUri), displayName,
        kinds.map { kind(it) ?: SoundpackKind.forId(it) }.toSet(), revision, scannedAt, scanError, fileCount, totalBytes,
        imported, importError)

    companion object {
        const val EXTRA_KINDS = "org.androidaudioplugin.soundpack.host.extra.KINDS"
        const val EXTRA_INITIAL_URI = "org.androidaudioplugin.soundpack.host.extra.INITIAL_URI"
        /** Boolean: import (copy) the folder rather than use it in place. */
        const val EXTRA_IMPORT = "org.androidaudioplugin.soundpack.host.extra.IMPORT"
        /** In the picker's result: the registered folder's id. */
        const val EXTRA_FOLDER_ID = "org.androidaudioplugin.soundpack.host.extra.FOLDER_ID"
        /** In the picker's result: a Bundle of kind id -> entry count, so the caller can warn
         * when the folder has none for its kind ("No .sfz files found"). */
        const val EXTRA_ENTRY_COUNTS = "org.androidaudioplugin.soundpack.host.extra.ENTRY_COUNTS"
        const val EXTRA_ERROR = "org.androidaudioplugin.soundpack.host.extra.ERROR"

        private const val LOG_TAG = "SoundpackHost"

        @Volatile
        private var instance: FolderRegistry? = null

        fun get(context: Context): FolderRegistry =
            instance ?: synchronized(this) { instance ?: FolderRegistry(context).also { instance = it } }

        /** `saf-<hash of provider authority and tree document id>`: the same folder gets the
         * same id in every app and in the hub, so it can move between them (§3.7). */
        fun packIdFor(tree: Uri): String {
            val key = "${tree.authority}\n${DocumentsContract.getTreeDocumentId(tree)}"
            val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
            return "saf-" + digest.take(16).joinToString("") { "%02x".format(it) }
        }
    }
}
