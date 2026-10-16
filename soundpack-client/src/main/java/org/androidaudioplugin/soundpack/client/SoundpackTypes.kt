package org.androidaudioplugin.soundpack.client

import android.content.Context
import org.androidaudioplugin.soundpack.FilePatterns
import org.androidaudioplugin.soundpack.SoundpackContract
import org.androidaudioplugin.soundpack.SoundpackKind

/** The kinds of content an app can find: the built-in ones, and the resource types users
 * defined in the Soundpack Manager, as it presents them now. An app for a kind that is not
 * built in finds it by its file types, e.g. a sampler app by `*.inst`, and then reads it
 * with `SoundpackClient(context, kind)` like any other kind.
 *
 * Blocking IPC: call it on a background thread. */
object SoundpackTypes {
    fun list(context: Context): List<SoundpackKind> {
        val defined = SoundpackClient.typesProviders(context).flatMap { (authority, _) ->
            runCatching { read(context, authority) }.getOrDefault(emptyList())
        }
        // The Manager may present a built-in kind differently, as its user edited it.
        return (defined + SoundpackKind.BUILT_IN).distinctBy { it.id }
    }

    /** The kinds whose file types include [pattern], such as `*.inst`. */
    fun withFileType(context: Context, pattern: String): List<SoundpackKind> =
        list(context).filter { kind -> kind.filePatterns.any { it.equals(pattern, ignoreCase = true) } }

    private fun read(context: Context, authority: String): List<SoundpackKind> =
        context.contentResolver.query(SoundpackClient.uri(authority, SoundpackContract.Types.listSegments()), null, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    fun text(column: String) = cursor.getColumnIndexOrThrow(column).let { if (cursor.isNull(it)) "" else cursor.getString(it) }
                    val id = text(SoundpackContract.Types.KIND_ID)
                    if (!SoundpackKind.isValidId(id))
                        continue
                    add(SoundpackKind(id, SoundpackContract.Types.readScopeOf(text(SoundpackContract.Types.READ_SCOPE)),
                        text(SoundpackContract.Types.LABEL).ifEmpty { id },
                        FilePatterns.parse(text(SoundpackContract.Types.FILE_PATTERNS)).filter(FilePatterns::isValid),
                        text(SoundpackContract.Types.DESCRIPTION)))
                }
            }
        }.orEmpty()
}
