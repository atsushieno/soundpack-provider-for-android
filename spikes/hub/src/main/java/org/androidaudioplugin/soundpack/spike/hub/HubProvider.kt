package org.androidaudioplugin.soundpack.spike.hub

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.util.Log
import org.androidaudioplugin.soundpack.spike.Spike
import java.io.File
import java.io.FileNotFoundException

/** The consent-gated hub provider.
 *
 * S2:
 * - `/packs` lists what the hub serves;
 * - `/packs/spike-hub-internal/files/hello.txt` is a file in the hub's own storage.
 *
 * S4:
 * - `/folder/files` lists the top level of the linked SAF tree;
 * - `/folder/files/<name>` re-exports one of its documents, passing through the descriptor
 *   ExternalStorageProvider returns. */
class HubProvider : ContentProvider() {
    /** Stands in for the real hub's index: name -> document, filled by the `/folder/files`
     * listing, so that opens measure the open path rather than a SAF directory query. */
    @Volatile
    private var index: Map<String, HubFolder.Child> = emptyMap()

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        Log.i(Spike.TAG, "hub query $uri from ${callingPackage}")
        val context = requireNotNull(context)
        return when (uri.pathSegments) {
            listOf("packs") -> MatrixCursor(arrayOf("pack_id", "label")).apply {
                addRow(arrayOf(Spike.HUB_INTERNAL_PACK, "Hub-internal file"))
                HubFolder.tree(context)?.let { addRow(arrayOf("folder", "Linked folder: $it")) }
            }
            listOf("folder", "files") -> MatrixCursor(arrayOf("name", "size", "is_directory")).apply {
                val tree = HubFolder.tree(context) ?: return@apply
                val children = asHub { HubFolder.children(context, tree) }
                index = children.associateBy { it.name }
                children.forEach {
                    addRow(arrayOf<Any>(it.name, it.size, if (it.isDirectory) 1 else 0))
                }
            }
            else -> throw IllegalArgumentException("Unknown URI: $uri")
        }
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
        require(mode == "r") { "Read-only" }
        Log.i(Spike.TAG, "hub open $uri from ${callingPackage}")
        val context = requireNotNull(context)
        val segments = uri.pathSegments
        return when {
            segments == listOf("packs", Spike.HUB_INTERNAL_PACK, "files", Spike.HUB_INTERNAL_FILE) -> {
                val file = File(context.filesDir, Spike.HUB_INTERNAL_FILE)
                if (!file.exists())
                    file.writeText("Hello from the Soundpack spike hub.\n")
                AssetFileDescriptor(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), 0, file.length())
            }
            segments.size == 3 && segments[0] == "folder" && segments[1] == "files" -> {
                val tree = HubFolder.tree(context) ?: throw FileNotFoundException("No folder linked in the hub")
                asHub {
                    val child = (index[segments[2]] ?: HubFolder.children(context, tree).firstOrNull { it.name == segments[2] })
                        ?.takeIf { !it.isDirectory }
                        ?: throw FileNotFoundException(segments[2])
                    // Pass-through: whatever ExternalStorageProvider gives us goes to the client.
                    context.contentResolver.openAssetFileDescriptor(child.documentUri, "r")
                        ?: throw FileNotFoundException(segments[2])
                }
            }
            else -> throw FileNotFoundException("Unknown URI: $uri")
        }
    }

    /** Calls out to other providers as the hub, not with the client's calling identity. */
    private inline fun <T> asHub(block: () -> T): T {
        val token = Binder.clearCallingIdentity()
        try {
            return block()
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    override fun getType(uri: Uri) = "application/octet-stream"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
