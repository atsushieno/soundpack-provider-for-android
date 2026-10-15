package org.androidaudioplugin.soundpack.spike.pack

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import org.androidaudioplugin.soundpack.spike.Spike

/** S3: serves one uncompressed APK asset as an AssetFileDescriptor sub-range of the APK.
 *
 * - `content://<auth>/files` lists it with its size and SHA-256, computed from the asset
 *   stream, so the client can check the bytes it maps.
 * - `content://<auth>/files/pack/sample.bin` returns `assets.openFd()`. */
class PackProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        require(uri.pathSegments == listOf("files")) { "Unknown URI: $uri" }
        val assets = requireNotNull(context).assets
        val size = assets.openFd(Spike.PACK_ASSET).use { it.length }
        val sha256 = assets.open(Spike.PACK_ASSET).use { Spike.sha256Hex(it) }
        return MatrixCursor(arrayOf("path", "size", "sha256")).apply {
            addRow(arrayOf<Any>(Spike.PACK_ASSET, size, sha256))
        }
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
        require(mode == "r") { "Read-only" }
        require(uri.pathSegments == listOf("files") + Spike.PACK_ASSET.split('/')) { "Unknown URI: $uri" }
        return requireNotNull(context).assets.openFd(Spike.PACK_ASSET)
    }

    override fun getType(uri: Uri) = "application/octet-stream"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
