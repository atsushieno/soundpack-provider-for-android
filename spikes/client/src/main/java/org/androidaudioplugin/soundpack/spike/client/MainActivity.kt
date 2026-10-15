package org.androidaudioplugin.soundpack.spike.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ProviderInfo
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.SystemClock
import android.provider.DocumentsContract
import android.system.Os
import android.system.OsConstants
import org.androidaudioplugin.soundpack.spike.Spike
import org.androidaudioplugin.soundpack.spike.SpikeActivity
import java.nio.channels.FileChannel

/** Runs the Phase 0 spikes S1–S6 (docs/spikes.md). Each button logs a line starting with its
 * spike id; lines that conclude a check contain "verdict". */
class MainActivity : SpikeActivity() {
    private val prefs by lazy { getSharedPreferences("client", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        button("S1: discover providers by action (run first)") { runS1() }
        button("S2a: read the hub before connecting (expect SecurityException)") {
            background("S2a") { log("S2a: " + HubChecks.readHub(this)) }
        }
        button("S2b: connect to the hub (consent, persist the grant)") { HubChecks.startConnect(this) }
        button("S2c: read the hub from this process") { background("S2c") { log("S2c: " + HubChecks.readHub(this)) } }
        button("S2d: read the hub from the :plugin process") { runS2d() }
        button("S2e: list persisted grants and read (after reboot, upgrade, revoke)") {
            background("S2e") {
                log("S2e: persisted grants:\n" + HubChecks.persistedGrants(this))
                log("S2e: " + HubChecks.readHub(this))
            }
        }
        button("S3: open the pack's APK asset, check range, mmap, verify") { runS3() }
        button("S4: open the hub's linked-folder files, check S_ISREG, time it") { runS4() }
        button("S4b: baseline: open this app's own linked folder (S6a) directly") { runS4Baseline() }
        button("S5: show what the embedded UI recorded") { log("S5 record:\n" + S5Record.read(this)) }
        button("S5: clear the record") { S5Record.clear(this); log("S5: record cleared") }
        button("S6a: link a folder in this app") {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .putExtra(DocumentsContract.EXTRA_INITIAL_URI, Spike.initialFolder(Spike.S6_FOLDER)), REQUEST_S6_LINK)
        }
        button("S6b: share that folder with the hub (delegate the grant)") { shareWithHub(checkOnly = false) }
        button("S6c: release this app's grant on that folder") { runS6c() }
        button("S6d: ask the hub to re-check its access") { shareWithHub(checkOnly = true) }
    }

    // S1 ------------------------------------------------------------------------------------

    private fun providersFor(action: String): List<ProviderInfo> =
        packageManager.queryIntentContentProviders(Intent(action), 0).map { it.providerInfo }

    private fun runS1() = background("S1") {
        val found = providersFor(Spike.ACTION_SFZ)
        log("S1: ${Spike.ACTION_SFZ} -> " + found.joinToString { "${it.packageName} (${it.authority})" }.ifEmpty { "(none)" })
        val control = providersFor(Spike.ACTION_HIDDEN)
        log("S1: control ${Spike.ACTION_HIDDEN} -> " + control.joinToString { it.packageName }.ifEmpty { "(none)" })
        val controlPackageVisible = runCatching { packageManager.getPackageInfo(Spike.INVISIBLE_PACKAGE, 0) }.isSuccess
        log("S1: control package visible through getPackageInfo: $controlPackageVisible")
        val packFound = found.any { it.packageName == Spike.PACK_PACKAGE }
        log("S1: verdict: " + when {
            packFound && control.isEmpty() && !controlPackageVisible ->
                "PASS: the pack was found only through <queries><intent>, and the control stays hidden " +
                    "(make sure the Spike Invisible APK is installed, or the control proves nothing)"
            packFound -> "INCONCLUSIVE: the control is visible too, so something else grants visibility"
            else -> "FAIL: <queries><intent> does not make the pack's provider visible"
        })
    }

    // S2 ------------------------------------------------------------------------------------

    private fun runS2d() {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                val connection = this
                val replies = Messenger(Handler(Looper.getMainLooper()) { reply ->
                    log("S2d: " + reply.data.getString(PluginProcessService.KEY_RESULT))
                    unbindService(connection)
                    true
                })
                Messenger(binder).send(Message.obtain(null, PluginProcessService.REQUEST).apply { replyTo = replies })
            }

            override fun onServiceDisconnected(name: ComponentName) {}
        }
        bindService(Intent(this, PluginProcessService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    // S3 ------------------------------------------------------------------------------------

    private fun runS3() = background("S3") {
        val listing = contentResolver.query(Uri.parse("content://${Spike.PACK_AUTHORITY}/files"), null, null, null, null)!!
        val (path, size, expectedSha) = listing.use {
            it.moveToFirst()
            Triple(it.getString(0), it.getLong(1), it.getString(2))
        }
        val uri = Uri.parse("content://${Spike.PACK_AUTHORITY}/files/$path")
        contentResolver.openAssetFileDescriptor(uri, "r")!!.use { afd ->
            val stat = Os.fstat(afd.fileDescriptor)
            val regular = OsConstants.S_ISREG(stat.st_mode)
            log("S3: startOffset=${afd.startOffset} declaredLength=${afd.declaredLength} (asset size $size), " +
                "S_ISREG=$regular, st_size=${stat.st_size}")
            // FileChannel.map() is mmap(2); it also handles the unaligned offset.
            val sha = afd.createInputStream().use { stream ->
                Spike.sha256Hex(stream.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength))
            }
            val matches = sha == expectedSha
            log("S3: mmap of the range: sha256 ${if (matches) "matches" else "DIFFERS"}")
            log("S3: verdict: " + if (afd.startOffset > 0 && afd.declaredLength == size && regular && matches)
                "PASS: the APK sub-range crossed processes intact and maps correctly"
            else "FAIL: see the values above")
        }
    }

    // S4 ------------------------------------------------------------------------------------

    private fun hubFolderFile(name: String): Uri =
        Uri.parse("content://${Spike.HUB_AUTHORITY}/folder/files").buildUpon().appendPath(name).build()

    private fun runS4() = background("S4") {
        val listStart = SystemClock.elapsedRealtimeNanos()
        val files = contentResolver.query(Uri.parse("content://${Spike.HUB_AUTHORITY}/folder/files"), null, null, null, null)!!
            .use { cursor ->
                buildList { while (cursor.moveToNext()) if (cursor.getInt(2) == 0) add(cursor.getString(0)) }
            }
        log("S4: listing (includes starting the hub's process if it was killed): " +
            "${"%.1f".format((SystemClock.elapsedRealtimeNanos() - listStart) / 1e6)} ms")
        if (files.isEmpty()) {
            log("S4: no files. Link a folder with files at its top level in Spike Hub, and connect (S2b) first.")
            return@background
        }
        log("S4: for a cold measurement, run `adb shell am kill ${Spike.HUB_PACKAGE}` just before this.")
        var allRegular = true
        files.take(5).forEachIndexed { index, name ->
            val start = SystemClock.elapsedRealtimeNanos()
            contentResolver.openAssetFileDescriptor(hubFolderFile(name), "r")!!.use { afd ->
                val millis = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
                val stat = Os.fstat(afd.fileDescriptor)
                val regular = OsConstants.S_ISREG(stat.st_mode)
                allRegular = allRegular && regular
                val length = if (afd.declaredLength >= 0) afd.declaredLength else stat.st_size - afd.startOffset
                val mapped = if (regular && length > 0) afd.createInputStream().use { stream ->
                    stream.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, minOf(length, 1L shl 20))
                    "mmap OK"
                } else "not mapped"
                log("S4: ${if (index == 0) "first open" else "open"} '$name': ${"%.1f".format(millis)} ms, S_ISREG=$regular, " +
                    "startOffset=${afd.startOffset}, declaredLength=${afd.declaredLength}, st_size=${stat.st_size}, $mapped")
            }
        }
        val warm = (1..20).map {
            val start = SystemClock.elapsedRealtimeNanos()
            contentResolver.openAssetFileDescriptor(hubFolderFile(files.first()), "r")!!.close()
            (SystemClock.elapsedRealtimeNanos() - start) / 1e6
        }
        log("S4: 20 warm opens of '${files.first()}': avg ${"%.2f".format(warm.average())} ms, max ${"%.2f".format(warm.max())} ms")
        log("S4: verdict: " + if (allRegular) "PASS: regular-file descriptors pass through the hub"
        else "FAIL: at least one descriptor is not a regular file (the real hub must copy those to its cache)")
    }

    /** One hop (client -> ExternalStorageProvider), to compare with S4's two hops through the hub. */
    private fun runS4Baseline() = background("S4b") {
        val tree = clientTree() ?: return@background log("S4b: link a folder with S6a first")
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val document = contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)!!.use { cursor ->
            generateSequence { if (cursor.moveToNext()) cursor.getString(0) to cursor.getString(1) else null }
                .firstOrNull { it.second != DocumentsContract.Document.MIME_TYPE_DIR }?.first
        } ?: return@background log("S4b: no file at the top level of $tree")
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, document)
        val times = (1..20).map {
            val start = SystemClock.elapsedRealtimeNanos()
            contentResolver.openAssetFileDescriptor(uri, "r")!!.close()
            (SystemClock.elapsedRealtimeNanos() - start) / 1e6
        }
        log("S4b: 20 direct opens of $document: avg ${"%.2f".format(times.average())} ms, max ${"%.2f".format(times.max())} ms")
    }

    // S6 ------------------------------------------------------------------------------------

    private fun clientTree(): Uri? = prefs.getString("tree", null)?.let(Uri::parse)

    private fun shareWithHub(checkOnly: Boolean) {
        val tree = clientTree() ?: return log("S6: link a folder with S6a first")
        val intent = Intent().setClassName(Spike.HUB_PACKAGE, Spike.HUB_SHARE_FOLDER_ACTIVITY).setData(tree)
        if (checkOnly)
            intent.putExtra(Spike.EXTRA_CHECK_ONLY, true)
        else
            // PREFIX is required: without it the hub persists a grant on the tree URI alone, and
            // ExternalStorageProvider refuses its children (observed on Android 16).
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        @Suppress("DEPRECATION")
        startActivityForResult(intent, if (checkOnly) REQUEST_S6_CHECK else REQUEST_S6_SHARE)
    }

    private fun runS6c() {
        val tree = clientTree() ?: return log("S6: link a folder with S6a first")
        log("S6c: " + HubChecks.attempt("releasePersistableUriPermission($tree)") {
            contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ""
        })
        log("S6c: now run S6d. The verdict for S6 is whether the hub still holds and can use the grant.")
    }

    // results -------------------------------------------------------------------------------

    @Deprecated("Spike code uses the platform API directly")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            HubChecks.REQUEST_CONNECT -> {
                log("S2b: " + HubChecks.takeGrant(this, resultCode, data))
                background("S2b") { log("S2b: " + HubChecks.readHub(this)) }
            }
            REQUEST_S6_LINK -> {
                val tree = data?.data ?: return log("S6a: no folder picked")
                contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                prefs.edit().putString("tree", tree.toString()).apply()
                log("S6a: linked $tree in the client")
            }
            REQUEST_S6_SHARE, REQUEST_S6_CHECK -> {
                val step = if (requestCode == REQUEST_S6_SHARE) "S6b" else "S6d"
                log("$step: " + (data?.getStringExtra(Spike.EXTRA_MESSAGE) ?: "no answer (resultCode=$resultCode)"))
            }
        }
    }

    private companion object {
        const val REQUEST_S6_LINK = 1
        const val REQUEST_S6_SHARE = 2
        const val REQUEST_S6_CHECK = 3
    }
}
