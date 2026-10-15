package org.androidaudioplugin.soundpack.spike.client

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import org.androidaudioplugin.soundpack.spike.Spike
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Reads from the hub the way the real client would, from whichever process calls it. */
object HubChecks {
    const val REQUEST_CONNECT = 100

    fun readHub(context: Context): String = buildString {
        val resolver = context.contentResolver
        append("process=${processName()}: ")
        append(attempt("query /packs") {
            resolver.query(Uri.parse("content://${Spike.HUB_AUTHORITY}/packs"), null, null, null, null)!!
                .use { "${it.count} row(s)" }
        })
        append("; ")
        append(attempt("open ${Spike.HUB_INTERNAL_FILE}") {
            val uri = Uri.parse("content://${Spike.HUB_AUTHORITY}/packs/${Spike.HUB_INTERNAL_PACK}/files/${Spike.HUB_INTERNAL_FILE}")
            resolver.openAssetFileDescriptor(uri, "r")!!.use { afd ->
                "'" + afd.createInputStream().bufferedReader().readText().trim() + "'"
            }
        })
    }

    fun persistedGrants(context: Context): String =
        context.contentResolver.persistedUriPermissions.joinToString("\n  ", prefix = "  ") {
            "${it.uri} read=${it.isReadPermission} persistedTime=${it.persistedTime}"
        }.ifBlank { "  (none)" }

    /** Starts the hub's consent screen for a result (S2b, and the real flow in S5). */
    @Suppress("DEPRECATION")
    fun startConnect(activity: Activity) =
        activity.startActivityForResult(Intent(Spike.ACTION_CONNECT).setPackage(Spike.HUB_PACKAGE), REQUEST_CONNECT)

    /** Persists the grant returned by the hub's consent screen. */
    fun takeGrant(context: Context, resultCode: Int, data: Intent?): String {
        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || uri == null)
            return "consent not given (resultCode=$resultCode)"
        return attempt("takePersistableUriPermission($uri)") {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            "result flags=0x${data.flags.toString(16)}"
        }
    }

    fun attempt(label: String, block: () -> String): String = try {
        "$label: OK ${block()}"
    } catch (e: Exception) {
        "$label: ${e.javaClass.simpleName}: ${e.message}"
    }

    private fun processName() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Application.getProcessName() else "?"
}

/** What the S5 embedded UI and trampoline did, kept across processes and app restarts. */
object S5Record {
    private fun prefs(context: Context) = context.getSharedPreferences("s5", Context.MODE_PRIVATE)

    fun add(context: Context, line: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())
        val previous = prefs(context).getString("lines", "")!!
        prefs(context).edit().putString("lines", "$previous$time $line\n").commit()
    }

    fun read(context: Context): String = prefs(context).getString("lines", "")!!.ifBlank { "(nothing recorded yet)\n" }

    fun clear(context: Context) = prefs(context).edit().clear().commit()
}
