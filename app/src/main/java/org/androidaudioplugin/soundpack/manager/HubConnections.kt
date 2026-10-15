package org.androidaudioplugin.soundpack.manager

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.androidaudioplugin.soundpack.SoundpackContract
import org.androidaudioplugin.soundpack.SoundpackKind

/** The apps the user connected, per kind. Android does not tell a provider who holds its
 * persisted grants, so the hub keeps its own record, written when it grants and removed when
 * it revokes (docs/PLAN.md §3.6). */
internal object HubConnections {
    data class Connection(val packageName: String, val kind: SoundpackKind, val label: String, val connectedAt: Long)

    private fun prefs(context: Context) = context.getSharedPreferences("connections", Context.MODE_PRIVATE)

    fun all(context: Context): List<Connection> = prefs(context).all.mapNotNull { (key, value) ->
        val packageName = key.substringBefore('|')
        val kind = SoundpackKind.builtIn(key.substringAfter('|')) ?: return@mapNotNull null
        val fields = (value as? String)?.split('\t') ?: return@mapNotNull null
        Connection(packageName, kind, fields.getOrElse(1) { packageName }, fields[0].toLongOrNull() ?: 0)
    }.sortedWith(compareBy({ it.label.lowercase() }, { it.kind.id }))

    fun add(context: Context, packageName: String, kind: SoundpackKind, label: String) {
        prefs(context).edit().putString("$packageName|${kind.id}", "${System.currentTimeMillis()}\t$label").apply()
    }

    /** The URI a consent grant for [kind] covers. */
    fun rootUri(context: Context, kind: SoundpackKind): Uri =
        Uri.parse(SoundpackContract.Hub.rootUriString(kind.authorityFor(context.packageName)))

    /** Takes the grant back at once: the app's next read fails, and it has to ask again. */
    fun revoke(context: Context, connection: Connection) {
        context.revokeUriPermission(connection.packageName, rootUri(context, connection.kind), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        prefs(context).edit().remove("${connection.packageName}|${connection.kind.id}").apply()
    }
}
