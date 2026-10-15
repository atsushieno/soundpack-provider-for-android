package org.androidaudioplugin.soundpack.spike.hub

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import org.androidaudioplugin.soundpack.spike.Spike
import org.androidaudioplugin.soundpack.spike.SpikeActivity

/** The hub's own controls: link the S4 folder, revoke S2 grants, inspect S6 grants. */
class MainActivity : SpikeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        log("Spike Hub ${packageManager.getPackageInfo(packageName, 0).versionName}. Linked folder: ${HubFolder.tree(this)}")

        button("S4: link a folder (OPEN_DOCUMENT_TREE)") {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .putExtra(DocumentsContract.EXTRA_INITIAL_URI, Spike.initialFolder(Spike.S4_FOLDER)), REQUEST_LINK)
        }
        button("S2: revoke all grants on the hub root") {
            revokeUriPermission(Spike.hubRoot, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            log("S2: revokeUriPermission(${Spike.hubRoot}) done. Now run S2e in the client.")
        }
        button("S2: revoke the client's grant only") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                revokeUriPermission(Spike.CLIENT_PACKAGE, Spike.hubRoot, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                log("S2: revokeUriPermission(${Spike.CLIENT_PACKAGE}, ${Spike.hubRoot}) done. Now run S2e in the client.")
            }
        }
        button("S6: list the hub's persisted grants") {
            background("S6") {
                val grants = contentResolver.persistedUriPermissions
                log("S6: hub holds ${grants.size} persisted grant(s)")
                grants.forEach { grant ->
                    val listing = runCatching { HubFolder.children(this, grant.uri).size }
                        .fold({ "lists OK, $it entries" }, { "list FAILED ${it.message}" })
                    log("  ${grant.uri} read=${grant.isReadPermission} time=${grant.persistedTime}: $listing")
                }
            }
        }
    }

    @Deprecated("Spike code uses the platform API directly")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val tree = data?.data
        if (requestCode != REQUEST_LINK || resultCode != RESULT_OK || tree == null) {
            log("S4: no folder picked")
            return
        }
        contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        HubFolder.setTree(this, tree)
        background("S4") {
            val children = HubFolder.children(this, tree)
            log("S4: linked $tree: ${children.size} entries, ${children.count { !it.isDirectory }} files at the top level")
        }
    }

    private companion object {
        const val REQUEST_LINK = 1
    }
}
