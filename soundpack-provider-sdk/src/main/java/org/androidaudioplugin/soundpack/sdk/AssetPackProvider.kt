package org.androidaudioplugin.soundpack.sdk

import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.res.AssetFileDescriptor
import android.util.Log
import org.androidaudioplugin.soundpack.PackPath
import org.androidaudioplugin.soundpack.Sf2Presets
import org.androidaudioplugin.soundpack.SoundpackContract
import org.androidaudioplugin.soundpack.SoundpackKind
import java.io.FileNotFoundException

/** Serves [AssetPack]s from this APK's assets, as read-only descriptor ranges of the APK.
 * Nothing is extracted or copied (docs/PLAN.md §3.4).
 *
 * Packs are read from the `@xml/` resource named by the provider's
 * [SoundpackContract.META_DATA_PACKS] `<meta-data>`, unless a subclass overrides
 * [assetPacks]. Assets must be stored uncompressed, which
 * `androidResources { noCompress += listOf(...) }` does for the listed extensions.
 *
 * Use [SfzAssetPackProvider], [Sf2AssetPackProvider] or [JsfxAssetPackProvider]. */
open class AssetPackProvider(kind: SoundpackKind) : SoundpackProvider(kind) {
    private class Listing(val pack: AssetPack, val record: PackRecord, val files: List<FileRecord>, val entries: List<EntryRecord>) {
        val paths: Set<String> = files.mapTo(HashSet()) { it.path }
    }

    @Suppress("DEPRECATION")
    protected open fun assetPacks(): List<AssetPack> {
        val context = requireNotNull(context)
        val info = context.packageManager.getProviderInfo(ComponentName(context, javaClass), PackageManager.GET_META_DATA)
        val resource = info.metaData?.getInt(SoundpackContract.META_DATA_PACKS) ?: 0
        require(resource != 0) { "${javaClass.name} needs <meta-data android:name=\"${SoundpackContract.META_DATA_PACKS}\" android:resource=\"@xml/…\">" }
        return context.resources.getXml(resource).use { AssetPack.parse(it) }
    }

    /** Built on first use and kept: an installed APK's assets never change. */
    private val listings: List<Listing> by lazy {
        val context = requireNotNull(context)
        val updated = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        assetPacks().map { pack -> list(pack, updated) }.also { listings ->
            val duplicate = listings.groupBy { it.pack.id }.filterValues { it.size > 1 }.keys
            require(duplicate.isEmpty()) { "Duplicate pack ids: $duplicate" }
        }
    }

    private fun list(pack: AssetPack, lastModified: Long): Listing {
        val assets = requireNotNull(context).assets
        val files = mutableListOf<FileRecord>()
        fun walk(relative: String, depth: Int) {
            check(depth <= PackPath.MAX_DEPTH && files.size < MAX_FILES) { "Pack ${pack.id} is too deep or too large" }
            val children = assets.list(assetPath(pack, relative)).orEmpty()
            if (children.isEmpty() && relative.isNotEmpty()) {
                if (PackPath.isHidden(relative))
                    return
                val size = try {
                    assets.openFd(assetPath(pack, relative)).use { it.length }
                } catch (e: FileNotFoundException) {
                    throw IllegalStateException("Asset ${assetPath(pack, relative)} is compressed or missing; " +
                        "add its extension to androidResources.noCompress", e)
                }
                files += FileRecord(relative, size, lastModified)
            } else children.sorted().forEach {
                if (PackPath.isValidName(it))
                    walk(if (relative.isEmpty()) it else "$relative/$it", depth + 1)
            }
        }
        walk("", 0)
        val paths = files.map { it.path }.toHashSet()

        val explicit = pack.entries.map { entry ->
            check(entry.path in paths) { "Entry ${entry.path} of pack ${pack.id} is not in its assets" }
            entryRecord(pack, entry.path, entry.label, entry.group, entry.scopes)
        }
        val matched = pack.entryPatterns.flatMap { pattern ->
            files.filter { pattern.glob.matches(it.path) }.map { entryRecord(pack, it.path, null, pattern.group, pattern.scopes) }
        }
        val defaults = if (pack.entries.isEmpty() && pack.entryPatterns.isEmpty())
            files.filter { kind.isEntryCandidate(it.path) }.map { entryRecord(pack, it.path, null, null, emptyList()) }
        else emptyList()
        val entries = (explicit + matched + defaults).distinctBy { it.path }

        val record = PackRecord(
            id = pack.id, label = pack.label, revision = pack.revision, source = SoundpackContract.SOURCE_APK,
            live = false, summary = pack.summary, license = pack.license,
            fileCount = files.size, totalBytes = files.sumOf { it.size },
        )
        return Listing(pack, record, files, entries)
    }

    private fun entryRecord(pack: AssetPack, path: String, label: String?, group: String?, scopes: List<String>) =
        EntryRecord(path, label ?: PackPath.stem(path), group ?: PackPath.parent(path), scopes, metadata(pack, path))

    /** Kind-specific entry metadata; SF2 presets for now (§3.9). */
    private fun metadata(pack: AssetPack, path: String): String? {
        if (kind != SoundpackKind.SF2)
            return null
        return try {
            AssetFileSource(requireNotNull(context).assets.openFd(assetPath(pack, path))).use {
                Sf2Presets.toJson(Sf2Presets.read(it))
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Cannot read presets of ${pack.id}/$path: ${e.message}")
            null
        }
    }

    private fun listing(pack: PackRecord) = listings.first { it.record.id == pack.id }

    private fun assetPath(pack: AssetPack, relative: String) =
        if (relative.isEmpty()) pack.assetRoot else "${pack.assetRoot}/$relative"

    override fun packs(): List<PackRecord> = listings.map { it.record }

    override fun entries(pack: PackRecord): List<EntryRecord> = listing(pack).entries

    override fun files(pack: PackRecord): List<FileRecord> = listing(pack).files

    override fun openPackFile(pack: PackRecord, path: String): AssetFileDescriptor {
        val listing = listing(pack)
        if (path !in listing.paths)
            throw FileNotFoundException(path)
        return requireNotNull(context).assets.openFd(assetPath(listing.pack, path))
    }

    private companion object {
        const val LOG_TAG = "SoundpackProvider"
        const val MAX_FILES = 65536
    }
}

class SfzAssetPackProvider : AssetPackProvider(SoundpackKind.SFZ)

class Sf2AssetPackProvider : AssetPackProvider(SoundpackKind.SF2)

class JsfxAssetPackProvider : AssetPackProvider(SoundpackKind.JSFX)
