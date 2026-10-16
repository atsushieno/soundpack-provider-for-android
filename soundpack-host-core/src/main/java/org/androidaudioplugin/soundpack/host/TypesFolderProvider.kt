package org.androidaudioplugin.soundpack.host

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import org.androidaudioplugin.soundpack.SoundpackContract
import org.androidaudioplugin.soundpack.SoundpackKind
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap

/** Serves the folders linked as the resource types the user added ([ResourceTypes.added]),
 * all through one authority, `${applicationId}.soundpack.types`, since a type defined at run
 * time cannot declare a provider of its own ([SoundpackContract.Types]). `types` lists them;
 * `types/<id>/…` is answered by a [FolderProvider] for that type, so the same rules apply as
 * for the built-in kinds. The Soundpack Manager exports it with [SoundpackContract.Types.ACTION]. */
class TypesFolderProvider : ContentProvider() {
    private val registry by lazy { FolderRegistry.get(requireNotNull(context)) }

    /** One per type, recreated when what it serves changes. */
    private val delegates = ConcurrentHashMap<String, Pair<SoundpackKind.ReadScope, FolderProvider>>()

    override fun attachInfo(context: Context, info: ProviderInfo) {
        require(SoundpackContract.Types.matchesAuthority(info.authority.substringBefore(';'))) {
            "Authority ${info.authority} does not follow the <package>.soundpack.types convention"
        }
        super.attachInfo(context, info)
    }

    override fun onCreate() = true

    private fun delegate(kindId: String): FolderProvider? {
        val kind = registry.types.added().firstOrNull { it.id == kindId } ?: return null
        delegates[kindId]?.takeIf { it.first == kind.readScope }?.let { return it.second }
        val context = requireNotNull(context)
        val provider = object : FolderProvider(kind) {}
        provider.attachInfo(context, ProviderInfo().apply {
            authority = kind.authorityFor(context.packageName)
            packageName = context.packageName
            exported = false
        })
        delegates[kindId] = kind.readScope to provider
        return provider
    }

    /** [uri] as the delegate for its type sees it: without `types/<id>`. It keeps this
     * provider's authority, which the delegate's cursors register their notifications with;
     * the delegate's own authority belongs to no declared provider, and Android refuses it. */
    private fun inner(uri: Uri): Uri =
        uri.buildUpon().path(null).apply { uri.pathSegments.drop(2).forEach { appendPath(it) } }.build()

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val resolver = requireNotNull(context).contentResolver
        if (uri.pathSegments == SoundpackContract.Types.listSegments())
            return MatrixCursor(SoundpackContract.Types.COLUMNS).apply {
                registry.types.added().forEach {
                    addRow(arrayOf(it.id, it.label, it.description, it.filePatterns.joinToString(" "),
                        SoundpackContract.Types.readScopeValue(it.readScope)))
                }
                setNotificationUri(resolver, uri)
            }
        val (kindId, route) = SoundpackContract.Types.route(uri.pathSegments) ?: throw IllegalArgumentException("Unknown URI: $uri")
        val delegate = delegate(kindId)
        val cursor = when {
            delegate != null -> delegate.query(inner(uri), projection, selection, selectionArgs, sortOrder)
            // A type nobody linked a folder as here, or one this app does not have: no packs.
            route == SoundpackContract.Route.Packs -> MatrixCursor(SoundpackContract.Packs.COLUMNS)
            else -> throw IllegalArgumentException("No type $kindId")
        }
        cursor.setNotificationUri(resolver, uri)
        return cursor
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
        val (kindId, _) = SoundpackContract.Types.route(uri.pathSegments) ?: throw FileNotFoundException("Not a file URI: $uri")
        val delegate = delegate(kindId) ?: throw FileNotFoundException("No type $kindId")
        return delegate.openAssetFile(inner(uri), mode)
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? = when (method) {
        SoundpackContract.METHOD_INFO -> Bundle().apply { putInt(SoundpackContract.KEY_PROTOCOL_VERSION, SoundpackContract.PROTOCOL_VERSION) }
        SoundpackContract.METHOD_RESCAN -> Bundle().also { registry.rescanInBackground(arg) }
        else -> super.call(method, arg, extras)
    }

    override fun getType(uri: Uri): String? =
        if (SoundpackContract.Types.route(uri.pathSegments)?.second is SoundpackContract.Route.File) "application/octet-stream" else null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Soundpack providers are read-only")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Soundpack providers are read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Soundpack providers are read-only")
}
