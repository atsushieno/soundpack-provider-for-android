package org.androidaudioplugin.soundpack.testkit

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.system.Os
import android.system.OsConstants
import org.androidaudioplugin.soundpack.PackPath
import org.androidaudioplugin.soundpack.SoundpackContract
import org.androidaudioplugin.soundpack.SoundpackKind
import org.json.JSONObject
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Checks that the provider at [authority] follows the soundpack contract (docs/PLAN.md
 * §3.2–§3.4, §7). Run it from a device test, ideally from another app so it goes through
 * Binder as real clients do:
 *
 * ```kotlin
 * ProviderConformance(context, "org.example.pack.soundpack.sfz").assertConforms()
 * ``` */
class ProviderConformance(private val context: Context, private val authority: String) {
    data class Result(val check: String, val passed: Boolean, val detail: String = "")

    private class PackRow(val id: String, val revision: String, val live: Boolean, val fileCount: Int?)
    private class FileRow(val path: String, val size: Long)

    private val results = mutableListOf<Result>()
    private val resolver get() = context.contentResolver

    fun run(): List<Result> {
        results.clear()
        val kind = checkInfo() ?: return results.toList()
        val packs = checkPacks()
        val firstFiles = packs.map { checkPack(kind, it) }
        packs.zip(firstFiles).firstOrNull { it.second != null }?.let { (pack, file) -> checkRejections(pack, file!!) }
        checkReadOnly()
        return results.toList()
    }

    /** Throws an AssertionError listing every failed check. */
    fun assertConforms() {
        val failed = run().filter { !it.passed }
        if (failed.isNotEmpty())
            throw AssertionError("$authority does not conform:\n" + failed.joinToString("\n") { "- ${it.check}: ${it.detail}" })
    }

    private fun record(check: String, passed: Boolean, detail: String = "") {
        results += Result(check, passed, detail)
    }

    private inline fun attempt(check: String, block: () -> Unit) = try {
        block()
    } catch (e: Exception) {
        record(check, false, "${e.javaClass.simpleName}: ${e.message}")
    }

    /** Passes only when [block] fails; a provider must refuse these requests. */
    private inline fun refused(check: String, block: () -> Unit) {
        val succeeded = try {
            block()
            true
        } catch (e: Exception) {
            false
        }
        record(check, !succeeded, if (succeeded) "the provider accepted it" else "")
    }

    private fun uri(segments: List<String>): Uri =
        Uri.Builder().scheme("content").authority(authority).apply { segments.forEach { appendPath(it) } }.build()

    private fun checkInfo(): SoundpackKind? {
        val info = try {
            resolver.call(uri(emptyList()), SoundpackContract.METHOD_INFO, null, null)
        } catch (e: Exception) {
            record("info", false, "${e.javaClass.simpleName}: ${e.message}")
            return null
        }
        val version = info?.getInt(SoundpackContract.KEY_PROTOCOL_VERSION, -1)
        record("info protocol version", version == SoundpackContract.PROTOCOL_VERSION, "got $version")
        val kind = info?.getString(SoundpackContract.KEY_KIND)?.let { SoundpackKind.builtIn(it) }
        record("info kind is a built-in kind", kind != null, "got ${info?.getString(SoundpackContract.KEY_KIND)}")
        if (kind != null)
            record("authority follows the ${kind.id} convention", kind.matchesAuthority(authority), authority)
        return kind
    }

    private fun checkPacks(): List<PackRow> {
        val rows = mutableListOf<PackRow>()
        attempt("query packs") {
            resolver.query(uri(SoundpackContract.packsSegments()), null, null, null, null)!!.use { cursor ->
                val missing = SoundpackContract.Packs.COLUMNS.filter { it !in cursor.columnNames }
                record("packs columns", missing.isEmpty(), "missing $missing")
                if (missing.isNotEmpty())
                    return rows
                while (cursor.moveToNext()) {
                    val id = cursor.getString(cursor.getColumnIndexOrThrow(SoundpackContract.Packs.PACK_ID))
                    val revision = cursor.getString(cursor.getColumnIndexOrThrow(SoundpackContract.Packs.REVISION))
                    val source = cursor.getString(cursor.getColumnIndexOrThrow(SoundpackContract.Packs.SOURCE))
                    val live = cursor.getInt(cursor.getColumnIndexOrThrow(SoundpackContract.Packs.LIVE))
                    val label = cursor.getString(cursor.getColumnIndexOrThrow(SoundpackContract.Packs.LABEL))
                    val countColumn = cursor.getColumnIndexOrThrow(SoundpackContract.Packs.FILE_COUNT)
                    record("pack $id: id and revision are valid",
                        SoundpackContract.isValidPackId(id) && SoundpackContract.isValidRevision(revision), "revision $revision")
                    record("pack $id: source, live and label",
                        source in setOf(SoundpackContract.SOURCE_APK, SoundpackContract.SOURCE_FOLDER) && live in 0..1 && !label.isNullOrEmpty(),
                        "source=$source live=$live label=$label")
                    rows += PackRow(id, revision, live == 1, if (cursor.isNull(countColumn)) null else cursor.getInt(countColumn))
                }
            }
        }
        record("at least one pack", rows.isNotEmpty())
        record("pack ids are unique", rows.map { it.id }.toSet().size == rows.size)
        return rows
    }

    /** Returns one file of the pack for the rejection checks, if it has any. */
    private fun checkPack(kind: SoundpackKind, pack: PackRow): FileRow? {
        val name = "pack ${pack.id}"
        val entries = mutableListOf<String>()
        attempt("$name: query entries") {
            resolver.query(uri(SoundpackContract.entriesSegments(pack.id)), null, null, null, null)!!.use { cursor ->
                val missing = SoundpackContract.Entries.COLUMNS.filter { it !in cursor.columnNames }
                record("$name: entries columns", missing.isEmpty(), "missing $missing")
                while (missing.isEmpty() && cursor.moveToNext()) {
                    val path = cursor.getString(cursor.getColumnIndexOrThrow(SoundpackContract.Entries.ENTRY_PATH))
                    entries += path
                    if (kind == SoundpackKind.SF2) {
                        val metadata = cursor.getString(cursor.getColumnIndexOrThrow(SoundpackContract.Entries.METADATA))
                        record("$name: SF2 entry $path has presets", metadata != null && runCatching { JSONObject(metadata).getJSONArray("presets") }.isSuccess, "metadata=$metadata")
                    }
                }
            }
        }
        record("$name: has entries", entries.isNotEmpty())
        record("$name: entry paths are normalized and visible", entries.all { PackPath.normalize(it) == it && !PackPath.isHidden(it) },
            entries.filter { PackPath.normalize(it) != it || PackPath.isHidden(it) }.toString())
        record("$name: entry paths are unique", entries.toSet().size == entries.size)

        val files = mutableListOf<FileRow>()
        attempt("$name: query files") {
            resolver.query(uri(SoundpackContract.filesSegments(pack.id)), null, null, null, null)!!.use { cursor ->
                while (cursor.moveToNext())
                    files += FileRow(cursor.getString(cursor.getColumnIndexOrThrow(SoundpackContract.Files.PATH)),
                        cursor.getLong(cursor.getColumnIndexOrThrow(SoundpackContract.Files.SIZE)))
            }
        }
        val paths = files.map { it.path }
        record("$name: file paths are normalized and visible", paths.all { PackPath.normalize(it) == it && !PackPath.isHidden(it) },
            paths.filter { PackPath.normalize(it) != it || PackPath.isHidden(it) }.toString())
        record("$name: file paths are unique", paths.toSet().size == paths.size)
        record("$name: file sizes are known", files.all { it.size >= 0 })
        if (kind.readScope == SoundpackKind.ReadScope.ENTRIES_ONLY)
            record("$name: an entries-only kind lists exactly its entries", paths.toSet() == entries.toSet(), "files=$paths entries=$entries")
        else
            record("$name: every entry is a file", paths.containsAll(entries), (entries - paths.toSet()).toString())
        pack.fileCount?.let { record("$name: file_count matches the listing", it == files.size, "file_count=$it, listed ${files.size}") }

        val toOpen = (files.filter { it.path in entries } + files.filter { it.path !in entries }.take(MAX_OPENED)).distinctBy { it.path }
        toOpen.forEach { checkOpen(pack, it) }
        files.firstOrNull()?.let { checkConcurrentOpens(pack, it) }
        return files.firstOrNull()
    }

    private fun fileUri(pack: PackRow, revision: String, path: String) = uri(SoundpackContract.fileSegments(pack.id, revision, path))

    private fun checkOpen(pack: PackRow, file: FileRow) {
        val check = "pack ${pack.id}: open ${file.path}"
        attempt(check) {
            resolver.openAssetFileDescriptor(fileUri(pack, pack.revision, file.path), "r")!!.use { afd ->
                val stat = Os.fstat(afd.fileDescriptor)
                val problems = listOfNotNull(
                    "not a regular file".takeUnless { OsConstants.S_ISREG(stat.st_mode) },
                    "declaredLength ${afd.declaredLength} != listed size ${file.size}".takeUnless { afd.declaredLength == file.size },
                    "negative startOffset".takeUnless { afd.startOffset >= 0 },
                    "range ends past the file (${afd.startOffset}+${afd.declaredLength} > ${stat.st_size})"
                        .takeUnless { afd.startOffset + afd.declaredLength <= stat.st_size },
                )
                if (problems.isEmpty() && afd.declaredLength > 0) {
                    // The last byte of the range must be readable at its absolute position.
                    val read = FileInputStream(afd.fileDescriptor).channel.read(ByteBuffer.allocate(1), afd.startOffset + afd.declaredLength - 1)
                    record(check, read == 1, "last byte not readable")
                } else
                    record(check, problems.isEmpty(), problems.joinToString("; "))
            }
        }
    }

    private fun checkConcurrentOpens(pack: PackRow, file: FileRow) {
        val executor = Executors.newFixedThreadPool(8)
        try {
            val futures = (1..32).map {
                executor.submit<Unit> { resolver.openAssetFileDescriptor(fileUri(pack, pack.revision, file.path), "r")!!.close() }
            }
            val failures = futures.mapNotNull { runCatching { it.get(30, TimeUnit.SECONDS) }.exceptionOrNull() }
            record("pack ${pack.id}: 32 concurrent opens", failures.isEmpty(), failures.firstOrNull()?.toString() ?: "")
        } finally {
            executor.shutdownNow()
        }
    }

    private fun checkRejections(pack: PackRow, file: FileRow) {
        val name = "pack ${pack.id}"
        // Live (folder) packs change in place, so only immutable packs pin a revision.
        if (!pack.live)
            refused("$name: refuses a stale revision") {
                resolver.openAssetFileDescriptor(fileUri(pack, "conformance-not-a-revision", file.path), "r")!!.close()
            }
        // Lexical ".." is fine while it stays inside the root...
        val withinRoot = "${file.path.substringBefore('/')}/../${file.path}"
        attempt("$name: accepts '$withinRoot' (stays inside the root)") {
            resolver.openAssetFileDescriptor(fileUri(pack, pack.revision, withinRoot), "r")!!.close()
            record("$name: accepts '$withinRoot' (stays inside the root)", true)
        }
        // ...and refused once it climbs above it: one ".." more than the path is deep.
        val escaping = file.path + "/" + "../".repeat(file.path.count { it == '/' } + 2) + file.path
        listOf("../${file.path}", escaping, "..\\${file.path}", ".hidden/${file.path}", ".DS_Store").forEach { path ->
            refused("$name: refuses path '$path'") {
                resolver.openAssetFileDescriptor(fileUri(pack, pack.revision, path), "r")!!.close()
            }
        }
        listOf("w", "rw", "wt").forEach { mode ->
            refused("$name: refuses mode '$mode'") {
                resolver.openAssetFileDescriptor(fileUri(pack, pack.revision, file.path), mode)!!.close()
            }
        }
        refused("refuses an unknown URI") {
            resolver.query(uri(listOf("not-a-route")), null, null, null, null)!!.close()
        }
    }

    private fun checkReadOnly() {
        val packs = uri(SoundpackContract.packsSegments())
        refused("refuses insert") { resolver.insert(packs, ContentValues().apply { put("pack_id", "x") }) }
        refused("refuses update") { resolver.update(packs, ContentValues().apply { put("pack_id", "x") }, null, null) }
        refused("refuses delete") { resolver.delete(packs, null, null) }
    }

    private companion object {
        /** Non-entry files opened per pack, on top of every entry. */
        const val MAX_OPENED = 64
    }
}
