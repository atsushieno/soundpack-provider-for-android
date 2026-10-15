package org.androidaudioplugin.soundpack.spike.hub

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/** The single SAF tree linked in the hub (S4), and helpers to read its top level. */
object HubFolder {
    data class Child(val name: String, val documentUri: Uri, val size: Long, val isDirectory: Boolean)

    private fun prefs(context: Context) = context.getSharedPreferences("hub", Context.MODE_PRIVATE)

    fun tree(context: Context): Uri? = prefs(context).getString("tree", null)?.let(Uri::parse)

    fun setTree(context: Context, tree: Uri) = prefs(context).edit().putString("tree", tree.toString()).apply()

    fun children(context: Context, tree: Uri): List<Child> {
        val parent = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_MIME_TYPE)
        val cursor = context.contentResolver.query(parent, columns, null, null, null)
            ?: error("Cannot list $tree")
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    val name = it.getString(1) ?: continue
                    add(Child(name, DocumentsContract.buildDocumentUriUsingTree(tree, it.getString(0)),
                        if (it.isNull(2)) -1 else it.getLong(2),
                        it.getString(3) == DocumentsContract.Document.MIME_TYPE_DIR))
                }
            }
        }
    }
}
