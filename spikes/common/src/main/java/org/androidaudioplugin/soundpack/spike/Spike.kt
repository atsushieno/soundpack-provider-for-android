package org.androidaudioplugin.soundpack.spike

import android.net.Uri
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Identifiers shared by the spike APKs. Spike-only names, so they never collide with the
 * real contract once it exists. */
object Spike {
    const val TAG = "SoundpackSpike"

    const val CLIENT_PACKAGE = "org.androidaudioplugin.soundpack.spike.client"
    const val HUB_PACKAGE = "org.androidaudioplugin.soundpack.spike.hub"
    const val PACK_PACKAGE = "org.androidaudioplugin.soundpack.spike.pack"
    const val INVISIBLE_PACKAGE = "org.androidaudioplugin.soundpack.spike.invisible"

    /** Provider discovery action under test (S1). The client declares it in `<queries>`. */
    const val ACTION_SFZ = "org.androidaudioplugin.soundpack.spike.sfz.V1"
    /** Negative control for S1. The client does *not* declare it in `<queries>`. */
    const val ACTION_HIDDEN = "org.androidaudioplugin.soundpack.spike.hidden.V1"
    /** The hub's consent activity (S2). */
    const val ACTION_CONNECT = "org.androidaudioplugin.soundpack.spike.CONNECT"

    const val HUB_SHARE_FOLDER_ACTIVITY = "$HUB_PACKAGE.ShareFolderActivity"
    const val EXTRA_CHECK_ONLY = "checkOnly"
    const val EXTRA_MESSAGE = "message"

    const val HUB_AUTHORITY = "$HUB_PACKAGE.soundpack.sfz"
    const val PACK_AUTHORITY = "$PACK_PACKAGE.soundpack.sfz"

    const val PACK_ASSET = "pack/sample.bin"
    const val HUB_INTERNAL_PACK = "spike-hub-internal"
    const val HUB_INTERNAL_FILE = "hello.txt"

    /** Where the folder pickers open, so runs can be scripted (docs/spikes.md). */
    fun initialFolder(path: String): Uri =
        android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$path")
    const val S4_FOLDER = "Music/SpikeTest"
    const val S6_FOLDER = "Music/SpikeShare"

    /** The URI the hub grants with READ | PERSISTABLE | PREFIX. */
    val hubRoot: Uri get() = Uri.parse("content://$HUB_AUTHORITY/")

    // S5: the view-service protocol, a reduced copy of AAP's AudioPluginViewService messages.
    const val VIEW_SERVICE_CLASS = "$CLIENT_PACKAGE.SpikeViewService"
    const val MSG_CREATE_VIEW = 1
    const val MSG_VIEW_CREATED = 2
    const val KEY_HOST_TOKEN = "hostToken"
    const val KEY_INPUT_TRANSFER_TOKEN = "inputTransferToken"
    const val KEY_DISPLAY_ID = "displayId"
    const val KEY_WIDTH = "width"
    const val KEY_HEIGHT = "height"
    const val KEY_SURFACE_PACKAGE = "surfacePackage"

    fun sha256Hex(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().toHex()
    }

    fun sha256Hex(buffer: ByteBuffer): String =
        MessageDigest.getInstance("SHA-256").apply { update(buffer) }.digest().toHex()

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
