package org.androidaudioplugin.soundpack.host

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** The index of linked folders: what each folder holds, as of its last scan. Providers answer
 * from here, so a folder is walked once per scan, not on every request (docs/PLAN.md §3.9).
 * The platform's SQLite only, so embedding apps pay no extra dependency. */
internal class FolderIndex private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "soundpack-folders.db", null, VERSION) {

    class FolderRow(
        val id: String,
        val treeUri: String,
        val displayName: String,
        val kinds: List<String>,
        val revision: Long,
        val scannedAt: Long,
        val scanError: String?,
        val fileCount: Int,
        val totalBytes: Long,
    )

    class FileRow(val path: String, val documentId: String, val size: Long, val lastModified: Long)

    class EntryRow(val kind: String, val path: String, val label: String, val groupPath: String, val metadata: String?)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE folders (
            id TEXT PRIMARY KEY,
            tree_uri TEXT NOT NULL UNIQUE,
            display_name TEXT NOT NULL,
            kinds TEXT NOT NULL,
            revision INTEGER NOT NULL DEFAULT 0,
            content_hash TEXT,
            scanned_at INTEGER NOT NULL DEFAULT 0,
            scan_error TEXT,
            file_count INTEGER NOT NULL DEFAULT 0,
            total_bytes INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("""CREATE TABLE files (
            folder_id TEXT NOT NULL,
            path TEXT NOT NULL,
            document_id TEXT NOT NULL,
            size INTEGER NOT NULL,
            last_modified INTEGER NOT NULL,
            PRIMARY KEY (folder_id, path))""")
        db.execSQL("""CREATE TABLE entries (
            folder_id TEXT NOT NULL,
            kind TEXT NOT NULL,
            path TEXT NOT NULL,
            label TEXT NOT NULL,
            group_path TEXT NOT NULL,
            metadata TEXT,
            PRIMARY KEY (folder_id, kind, path))""")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 only. Later versions migrate here; the index can always be rebuilt by a
        // rescan, but the folder list (and its grants) must survive.
    }

    fun folders(): List<FolderRow> = readableDatabase.query("folders", null, null, null, null, null, "display_name, id")
        .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toFolder()) } }

    fun folder(id: String): FolderRow? = readableDatabase.query("folders", null, "id = ?", arrayOf(id), null, null, null)
        .use { if (it.moveToFirst()) it.toFolder() else null }

    fun insertFolder(id: String, treeUri: String, displayName: String, kinds: Collection<String>) {
        writableDatabase.insertWithOnConflict("folders", null, ContentValues().apply {
            put("id", id)
            put("tree_uri", treeUri)
            put("display_name", displayName)
            put("kinds", kinds.sorted().joinToString(" "))
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun setKinds(id: String, kinds: Collection<String>) {
        writableDatabase.update("folders", ContentValues().apply { put("kinds", kinds.sorted().joinToString(" ")) }, "id = ?", arrayOf(id))
    }

    fun deleteFolder(id: String) = writableDatabase.transaction {
        delete("entries", "folder_id = ?", arrayOf(id))
        delete("files", "folder_id = ?", arrayOf(id))
        delete("folders", "id = ?", arrayOf(id))
    }

    fun contentHash(id: String): String? =
        readableDatabase.rawQuery("SELECT content_hash FROM folders WHERE id = ?", arrayOf(id))
            .use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }

    /** Replaces a folder's files and entries with a new scan, in one transaction, so readers
     * never see half a scan. The revision moves only when the content changed. */
    fun replaceContent(id: String, displayName: String, files: List<FileRow>, entries: List<EntryRow>, contentHash: String, scannedAt: Long) =
        writableDatabase.transaction {
            // Removed while it was being scanned: write nothing.
            if (folder(id) == null)
                return@transaction false
            val changed = contentHash(id) != contentHash
            delete("entries", "folder_id = ?", arrayOf(id))
            delete("files", "folder_id = ?", arrayOf(id))
            val fileStatement = compileStatement("INSERT INTO files (folder_id, path, document_id, size, last_modified) VALUES (?, ?, ?, ?, ?)")
            for (file in files) {
                fileStatement.clearBindings()
                fileStatement.bindString(1, id)
                fileStatement.bindString(2, file.path)
                fileStatement.bindString(3, file.documentId)
                fileStatement.bindLong(4, file.size)
                fileStatement.bindLong(5, file.lastModified)
                fileStatement.executeInsert()
            }
            val entryStatement = compileStatement("INSERT INTO entries (folder_id, kind, path, label, group_path, metadata) VALUES (?, ?, ?, ?, ?, ?)")
            for (entry in entries) {
                entryStatement.clearBindings()
                entryStatement.bindString(1, id)
                entryStatement.bindString(2, entry.kind)
                entryStatement.bindString(3, entry.path)
                entryStatement.bindString(4, entry.label)
                entryStatement.bindString(5, entry.groupPath)
                if (entry.metadata == null) entryStatement.bindNull(6) else entryStatement.bindString(6, entry.metadata)
                entryStatement.executeInsert()
            }
            execSQL("UPDATE folders SET display_name = ?, content_hash = ?, scanned_at = ?, scan_error = NULL, " +
                "file_count = ?, total_bytes = ?, revision = revision + ? WHERE id = ?",
                arrayOf<Any>(displayName, contentHash, scannedAt, files.size, files.sumOf { it.size }, if (changed) 1 else 0, id))
            changed
        }

    fun setScanError(id: String, error: String, scannedAt: Long) {
        writableDatabase.update("folders", ContentValues().apply {
            put("scan_error", error)
            put("scanned_at", scannedAt)
        }, "id = ?", arrayOf(id))
    }

    fun files(folderId: String): List<FileRow> =
        readableDatabase.query("files", null, "folder_id = ?", arrayOf(folderId), null, null, "path").use { cursor ->
            buildList { while (cursor.moveToNext()) add(FileRow(cursor.getString(1), cursor.getString(2), cursor.getLong(3), cursor.getLong(4))) }
        }

    fun file(folderId: String, path: String): FileRow? =
        readableDatabase.query("files", null, "folder_id = ? AND path = ?", arrayOf(folderId, path), null, null, null).use {
            if (it.moveToFirst()) FileRow(it.getString(1), it.getString(2), it.getLong(3), it.getLong(4)) else null
        }

    fun entries(folderId: String, kind: String): List<EntryRow> =
        readableDatabase.query("entries", null, "folder_id = ? AND kind = ?", arrayOf(folderId, kind), null, null, "path").use { cursor ->
            buildList {
                while (cursor.moveToNext())
                    add(EntryRow(cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getString(4),
                        if (cursor.isNull(5)) null else cursor.getString(5)))
            }
        }

    /** Total size of the files a provider of [kind] serves from [folderId]: all files, or only
     * the entries for an entries-only kind. */
    fun entryStats(folderId: String, kind: String): Pair<Int, Long> =
        readableDatabase.rawQuery("SELECT COUNT(*), COALESCE(SUM(f.size), 0) FROM entries e JOIN files f " +
            "ON f.folder_id = e.folder_id AND f.path = e.path WHERE e.folder_id = ? AND e.kind = ?", arrayOf(folderId, kind))
            .use { it.moveToFirst(); it.getInt(0) to it.getLong(1) }

    private fun Cursor.toFolder() = FolderRow(
        id = getString(getColumnIndexOrThrow("id")),
        treeUri = getString(getColumnIndexOrThrow("tree_uri")),
        displayName = getString(getColumnIndexOrThrow("display_name")),
        kinds = getString(getColumnIndexOrThrow("kinds")).split(' ').filter { it.isNotEmpty() },
        revision = getLong(getColumnIndexOrThrow("revision")),
        scannedAt = getLong(getColumnIndexOrThrow("scanned_at")),
        scanError = getColumnIndexOrThrow("scan_error").let { if (isNull(it)) null else getString(it) },
        fileCount = getInt(getColumnIndexOrThrow("file_count")),
        totalBytes = getLong(getColumnIndexOrThrow("total_bytes")),
    )

    private inline fun <T> SQLiteDatabase.transaction(block: SQLiteDatabase.() -> T): T {
        beginTransaction()
        try {
            return block().also { setTransactionSuccessful() }
        } finally {
            endTransaction()
        }
    }

    companion object {
        private const val VERSION = 1

        @Volatile
        private var instance: FolderIndex? = null

        fun get(context: Context): FolderIndex =
            instance ?: synchronized(this) { instance ?: FolderIndex(context).also { instance = it } }
    }
}
