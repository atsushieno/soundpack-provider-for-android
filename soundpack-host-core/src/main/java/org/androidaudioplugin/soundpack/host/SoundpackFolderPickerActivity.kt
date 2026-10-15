package org.androidaudioplugin.soundpack.host

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import org.androidaudioplugin.soundpack.SoundpackKind
import kotlin.concurrent.thread

/** Runs the system folder picker, then links the chosen folder in [FolderRegistry]. No UI of
 * its own and no Compose, so ImGui, JUCE and service-hosted plugin UIs can use it (§3.9).
 * Start it with [FolderRegistry.pickFolderIntent].
 *
 * Result: RESULT_OK with the tree URI as data, [FolderRegistry.EXTRA_FOLDER_ID] and
 * [FolderRegistry.EXTRA_ENTRY_COUNTS]; or RESULT_CANCELED, with [FolderRegistry.EXTRA_ERROR]
 * when linking failed. */
class SoundpackFolderPickerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null)
            return
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        initialUri()?.let { pick.putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
        @Suppress("DEPRECATION")
        startActivityForResult(pick, REQUEST_PICK)
    }

    private fun initialUri(): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            intent.getParcelableExtra(FolderRegistry.EXTRA_INITIAL_URI, Uri::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(FolderRegistry.EXTRA_INITIAL_URI)

    @Deprecated("Uses the platform API so that it needs no AndroidX dependency")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val tree = data?.data
        if (requestCode != REQUEST_PICK || resultCode != RESULT_OK || tree == null) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        val registry = FolderRegistry.get(this)
        val requested = intent.getStringArrayExtra(FolderRegistry.EXTRA_KINDS)?.mapNotNull { SoundpackKind.builtIn(it) }?.toSet()
        // Scanning can take a while for a large folder: not on the main thread.
        thread(name = "soundpack-folder-link") {
            val result = try {
                // The picker's grant is temporary until add() persists it; keep this process alive meanwhile.
                val kinds = requested ?: registry.detectKinds(tree).filterValues { it > 0 }.keys.ifEmpty { registry.servedKinds().toSet() }
                val folder = registry.add(tree, kinds)
                Intent().setData(tree)
                    .putExtra(FolderRegistry.EXTRA_FOLDER_ID, folder.id)
                    .putExtra(FolderRegistry.EXTRA_ENTRY_COUNTS, Bundle().apply {
                        folder.kinds.forEach { putInt(it.id, registry.entryCount(folder.id, it)) }
                    })
            } catch (e: Exception) {
                Intent().putExtra(FolderRegistry.EXTRA_ERROR, e.message ?: e.javaClass.simpleName)
            }
            runOnUiThread {
                setResult(if (result.hasExtra(FolderRegistry.EXTRA_FOLDER_ID)) RESULT_OK else RESULT_CANCELED, result)
                finish()
            }
        }
    }

    private companion object {
        const val REQUEST_PICK = 1
    }
}
