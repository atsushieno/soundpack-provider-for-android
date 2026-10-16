package org.androidaudioplugin.soundpack.client

import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.AssetFileDescriptor
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import org.androidaudioplugin.soundpack.PackPath
import org.androidaudioplugin.soundpack.SoundpackContract
import org.androidaudioplugin.soundpack.SoundpackIdentity
import org.androidaudioplugin.soundpack.SoundpackKind
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/** Reads soundpacks of one [kind] from every provider this app can see (docs/PLAN.md §3):
 * this app's own local folder provider if it declares one, the hub, and pack APKs.
 *
 * Every call here is blocking IPC: call it on a background thread, never from an audio
 * callback. */
class SoundpackClient(context: Context, val kind: SoundpackKind) {
    private val context: Context = context.applicationContext
    internal val resolver: ContentResolver get() = context.contentResolver

    data class Endpoint(
        val authority: String,
        val packageName: String,
        val local: Boolean,
        /** For a types provider ([SoundpackContract.Types]): the kind its URIs are under. */
        val kindPath: String? = null,
    ) {
        internal fun uri(segments: List<String>): Uri =
            uri(authority, kindPath?.let { SoundpackContract.Types.segments(it, segments) } ?: segments)
    }

    data class Pack(
        val endpoint: Endpoint,
        val id: String,
        val label: String,
        val revision: String,
        val source: String,
        /** True for folders: the content can change without a revision bump. */
        val live: Boolean,
        val fileCount: Int?,
        val totalBytes: Long?,
        val summary: String?,
        val license: String?,
    )

    data class Entry(
        val pack: Pack,
        val path: String,
        val label: String,
        val groupPath: String,
        val scopes: List<String>,
        /** Kind-specific JSON, e.g. SF2 presets ([Sf2Metadata]). */
        val metadata: String?,
    )

    data class FileInfo(val path: String, val size: Long, val lastModified: Long)

    /** A provider that could not be read. */
    data class Failure(val endpoint: Endpoint, val error: Exception)

    /** [packs] has one pack per id: the local provider wins, then discovery order. */
    data class Catalog(val endpoints: List<Endpoint>, val packs: List<Pack>, val failures: List<Failure>)

    enum class RevisionPolicy {
        /** Exact for immutable packs, latest for live (folder) packs (§3.7). */
        DEFAULT,
        EXACT,
        LATEST,
    }

    fun endpoints(): List<Endpoint> {
        val packageManager = context.packageManager
        val localAuthority = kind.localAuthorityFor(context.packageName)
        val local = packageManager.resolveContentProvider(localAuthority, 0)
            ?.let { Endpoint(localAuthority, context.packageName, local = true) }
        val discovered = packageManager.queryIntentContentProviders(Intent(kind.action), 0)
            .mapNotNull { it.providerInfo }
            .flatMap { info ->
                info.authority.split(';').filter(kind::matchesAuthority).map { Endpoint(it, info.packageName, local = false) }
            }
        // Built-in kinds have providers of their own; any other kind may be one a user defined.
        val types = if (kind.isBuiltIn) emptyList() else typesProviders(context).map { (authority, packageName) ->
            Endpoint(authority, packageName, local = false, kindPath = kind.id)
        }
        return (listOfNotNull(local) + discovered + types).distinctBy { it.authority }
    }

    fun catalog(): Catalog {
        val endpoints = endpoints()
        val packs = mutableListOf<Pack>()
        val failures = mutableListOf<Failure>()
        for (endpoint in endpoints) {
            try {
                packs += packsOf(endpoint)
            } catch (e: Exception) {
                failures += Failure(endpoint, e)
            }
        }
        return Catalog(endpoints, packs.distinctBy { it.id }, failures)
    }

    /** Emits a fresh [Catalog] now, and again whenever a provider reports a change or a package
     * is added, replaced or removed. */
    fun catalogFlow(): Flow<Catalog> = callbackFlow {
        val refresh = Channel<Unit>(Channel.CONFLATED)
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) { refresh.trySend(Unit) }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { refresh.trySend(Unit) }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else
            context.registerReceiver(receiver, filter)
        val worker = launch(Dispatchers.IO) {
            var observed = emptySet<Endpoint>()
            refresh.send(Unit)
            for (signal in refresh) {
                val catalog = catalog()
                val endpoints = catalog.endpoints.toSet()
                if (endpoints != observed) {
                    resolver.unregisterContentObserver(observer)
                    endpoints.forEach {
                        runCatching { resolver.registerContentObserver(it.uri(SoundpackContract.packsSegments()), true, observer) }
                    }
                    observed = endpoints
                }
                send(catalog)
            }
        }
        awaitClose {
            worker.cancel()
            context.unregisterReceiver(receiver)
            resolver.unregisterContentObserver(observer)
        }
    }

    fun entries(pack: Pack): List<Entry> =
        query(pack.endpoint, SoundpackContract.entriesSegments(pack.id)) { cursor ->
            Entry(
                pack = pack,
                path = cursor.string(SoundpackContract.Entries.ENTRY_PATH),
                label = cursor.string(SoundpackContract.Entries.LABEL),
                groupPath = cursor.string(SoundpackContract.Entries.GROUP_PATH),
                scopes = cursor.stringOrNull(SoundpackContract.Entries.SCOPES)?.split(' ')?.filter { it.isNotEmpty() } ?: emptyList(),
                metadata = cursor.stringOrNull(SoundpackContract.Entries.METADATA),
            )
        }

    fun files(pack: Pack): List<FileInfo> =
        query(pack.endpoint, SoundpackContract.filesSegments(pack.id)) { cursor ->
            FileInfo(
                path = cursor.string(SoundpackContract.Files.PATH),
                size = cursor.getLong(cursor.getColumnIndexOrThrow(SoundpackContract.Files.SIZE)),
                lastModified = cursor.getLong(cursor.getColumnIndexOrThrow(SoundpackContract.Files.LAST_MODIFIED)),
            )
        }

    /** The identity to save for [entry]. Live packs carry no revision: they resolve to the
     * latest content (§3.7). */
    fun identityOf(entry: Entry) = SoundpackIdentity(
        kind = kind.id,
        packId = entry.pack.id,
        entryPath = entry.path,
        revision = entry.pack.revision.takeUnless { entry.pack.live },
        via = entry.pack.endpoint.authority,
    )

    /** Resolves a saved identity, preferring the provider it was chosen from. Throws
     * [SoundpackUnavailableException] when no provider serves that pack, entry and (per
     * [policy]) revision; callers should then show the content as unavailable. */
    fun open(identity: SoundpackIdentity, policy: RevisionPolicy = RevisionPolicy.DEFAULT): PackSession {
        require(identity.kind == kind.id) { "${identity.kind} identity given to a ${kind.id} client" }
        val endpoints = endpoints().sortedBy {
            when {
                it.authority == identity.via -> 0
                it.local -> 1
                else -> 2
            }
        }
        var reason: Exception? = null
        for (endpoint in endpoints) {
            try {
                val pack = packsOf(endpoint).firstOrNull { it.id == identity.packId } ?: continue
                val exact = when (policy) {
                    RevisionPolicy.EXACT -> true
                    RevisionPolicy.LATEST -> false
                    RevisionPolicy.DEFAULT -> !pack.live
                }
                if (exact && identity.revision != null && identity.revision != pack.revision) {
                    reason = IOException("${endpoint.authority} has revision ${pack.revision}, not ${identity.revision}")
                    continue
                }
                if (entries(pack).none { it.path == identity.entryPath }) {
                    reason = FileNotFoundException("${endpoint.authority} has no entry ${identity.entryPath}")
                    continue
                }
                return PackSession(this, pack, identity.entryPath)
            } catch (e: Exception) {
                reason = e
            }
        }
        throw SoundpackUnavailableException(identity, reason)
    }

    /** Opens [path] of [pack] read-only: a regular file with a known length, possibly a range
     * of a larger file ([AssetFileDescriptor.getStartOffset]). Use openAssetFileDescriptor
     * semantics; ContentResolver.openFileDescriptor refuses ranges. */
    fun openFile(pack: Pack, path: String): AssetFileDescriptor {
        val normalized = PackPath.normalize(path) ?: throw FileNotFoundException("Invalid path: $path")
        return resolver.openAssetFileDescriptor(pack.endpoint.uri(SoundpackContract.fileSegments(pack.id, pack.revision, normalized)), "r")
            ?: throw FileNotFoundException(path)
    }

    /** The hub's endpoint for this kind, if the hub is installed and visible. */
    fun hubEndpoint(): Endpoint? = endpoints().firstOrNull { it.packageName == SoundpackContract.Hub.PACKAGE }

    /** Mirrors [pack] into [destination] for engines that need real paths (JSFX); see
     * [Materializer]. */
    fun materialize(pack: Pack, destination: File): Materializer.Result = Materializer(this).materialize(pack, destination)

    private fun packsOf(endpoint: Endpoint): List<Pack> =
        query(endpoint, SoundpackContract.packsSegments()) { cursor ->
            Pack(
                endpoint = endpoint,
                id = cursor.string(SoundpackContract.Packs.PACK_ID),
                label = cursor.string(SoundpackContract.Packs.LABEL),
                revision = cursor.string(SoundpackContract.Packs.REVISION),
                source = cursor.string(SoundpackContract.Packs.SOURCE),
                live = cursor.getInt(cursor.getColumnIndexOrThrow(SoundpackContract.Packs.LIVE)) != 0,
                fileCount = cursor.intOrNull(SoundpackContract.Packs.FILE_COUNT),
                totalBytes = cursor.longOrNull(SoundpackContract.Packs.TOTAL_BYTES),
                summary = cursor.stringOrNull(SoundpackContract.Packs.SUMMARY),
                license = cursor.stringOrNull(SoundpackContract.Packs.LICENSE),
            )
        }.filter { SoundpackContract.isValidPackId(it.id) && SoundpackContract.isValidRevision(it.revision) }

    private fun <T> query(endpoint: Endpoint, segments: List<String>, row: (Cursor) -> T): List<T> {
        val cursor = resolver.query(endpoint.uri(segments), null, null, null, null)
            ?: throw IOException("${endpoint.authority} returned no cursor")
        return cursor.use { buildList { while (it.moveToNext()) add(row(it)) } }
    }

    private fun Cursor.string(column: String): String = getString(getColumnIndexOrThrow(column))
    private fun Cursor.stringOrNull(column: String): String? = getColumnIndexOrThrow(column).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.intOrNull(column: String): Int? = getColumnIndexOrThrow(column).let { if (isNull(it)) null else getInt(it) }
    private fun Cursor.longOrNull(column: String): Long? = getColumnIndexOrThrow(column).let { if (isNull(it)) null else getLong(it) }

    internal companion object {
        fun uri(authority: String, segments: List<String>): Uri =
            Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(authority)
                .apply { segments.forEach { appendPath(it) } }.build()

        /** Every visible types provider: authority and package. */
        fun typesProviders(context: Context): List<Pair<String, String>> =
            context.packageManager.queryIntentContentProviders(Intent(SoundpackContract.Types.ACTION), 0)
                .mapNotNull { it.providerInfo }
                .flatMap { info -> info.authority.split(';').filter(SoundpackContract.Types::matchesAuthority).map { it to info.packageName } }
    }
}

/** One pack resolved from a saved identity. Stateless: nothing to close. */
class PackSession internal constructor(
    private val client: SoundpackClient,
    val pack: SoundpackClient.Pack,
    val entryPath: String,
) : Closeable {
    /** The pack's file index, fetched on first use. */
    val files: List<SoundpackClient.FileInfo> by lazy { client.files(pack) }

    @Volatile
    private var prefetcher: Prefetcher<AssetFileDescriptor>? = null

    fun openEntry(): AssetFileDescriptor = openFile(entryPath)

    /** Opens [path], from the [prefetch] when it is there. */
    fun openFile(path: String): AssetFileDescriptor =
        PackPath.normalize(path)?.let { prefetcher?.take(it) } ?: client.openFile(pack, path)

    /** Starts opening [paths], in this order and [parallelism] at a time, for an engine that
     * then asks for them one at a time through [openFile]. Each open of a folder file goes
     * through SAF, about 20 ms, so a large instrument loads several times faster. At most
     * [window] opened files wait to be taken; what is not taken within [idleMillis] of the
     * last [openFile] is closed. Replaces an earlier prefetch. */
    fun prefetch(paths: List<String>, parallelism: Int = 8, window: Int = 64, idleMillis: Long = 10_000) {
        val wanted = paths.mapNotNull { PackPath.normalize(it) }
        val previous = prefetcher
        prefetcher = if (wanted.isEmpty()) null else Prefetcher(wanted, parallelism, window, idleMillis) { client.openFile(pack, it) }
        previous?.close()
    }

    /** Closes what was prefetched and not taken. The session stays usable. */
    override fun close() {
        prefetcher?.close()
        prefetcher = null
    }
}

class SoundpackUnavailableException(val identity: SoundpackIdentity, cause: Exception?) :
    IOException("Soundpack content unavailable: $identity" + (cause?.let { " (${it.message})" } ?: ""), cause)
