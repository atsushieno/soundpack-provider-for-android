package org.androidaudioplugin.soundpack.spike.hub

import android.content.Intent
import android.os.Bundle
import org.androidaudioplugin.soundpack.spike.Spike
import org.androidaudioplugin.soundpack.spike.SpikeActivity

/** S6: another app hands over its SAF tree grant (READ | PERSISTABLE in the intent flags).
 * Unless [Spike.EXTRA_CHECK_ONLY] is set, try to persist it; then report whether the hub
 * holds a persisted grant and can list the tree. */
class ShareFolderActivity : SpikeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tree = intent.data
        val checkOnly = intent.getBooleanExtra(Spike.EXTRA_CHECK_ONLY, false)
        log("S6: tree=$tree checkOnly=$checkOnly from $callingPackage")
        if (tree == null) {
            finishWith("S6: no tree URI in the intent")
            return
        }
        background("S6") {
            val message = buildString {
                if (!checkOnly) {
                    try {
                        contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        append("takePersistableUriPermission: OK")
                    } catch (e: SecurityException) {
                        append("takePersistableUriPermission: FAILED ${e.message}")
                    }
                    append("; ")
                }
                val persisted = contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }
                append("hub holds persisted grant: $persisted; ")
                append(runCatching { HubFolder.children(this@ShareFolderActivity, tree).size }
                    .fold({ "hub lists tree: OK, $it entries" }, { "hub lists tree: FAILED ${it.javaClass.simpleName}: ${it.message}" }))
            }
            runOnUiThread { finishWith("S6: $message") }
        }
    }

    private fun finishWith(message: String) {
        log(message)
        setResult(RESULT_OK, Intent().putExtra(Spike.EXTRA_MESSAGE, message))
        finish()
    }
}
