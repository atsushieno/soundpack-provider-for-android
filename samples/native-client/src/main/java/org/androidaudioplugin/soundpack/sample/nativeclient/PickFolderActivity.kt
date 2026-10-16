package org.androidaudioplugin.soundpack.sample.nativeclient

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract

/** Runs the system folder picker and keeps a persistable read grant on the result, as an app's
 * own picker does (JUCE's FileChooser does the same). Returns the tree URI as its data. */
class PickFolderActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null)
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                intent.getStringExtra(DocumentsContract.EXTRA_INITIAL_URI)?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(it)) }
            }, REQUEST_PICK)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val tree = data?.data
        if (requestCode == REQUEST_PICK && resultCode == RESULT_OK && tree != null) {
            contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setResult(RESULT_OK, Intent().setData(tree))
        } else {
            setResult(RESULT_CANCELED)
        }
        finish()
    }

    private companion object {
        const val REQUEST_PICK = 1
    }
}
