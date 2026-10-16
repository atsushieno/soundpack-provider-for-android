package org.androidaudioplugin.soundpack.sample.nativeclient

import android.content.Context

/** JNI entry points of native_client.cpp, which uses the C API only. */
object NativeClient {
    init {
        System.loadLibrary("native-client")
    }

    @JvmStatic external fun init(context: Context): Int
    @JvmStatic external fun lastError(): String
    @JvmStatic external fun formatIdentity(kind: String, packId: String, entryPath: String): String?

    /** Six fields per entry: identity, pack label, path, label, group, metadata. */
    @JvmStatic external fun listEntries(kind: String): Array<String>

    /** Opens [identity], prefetches its files, maps and opens the entry: entry path, file count,
     * mapped size, the first 12 bytes, descriptor offset, descriptor length. Null on failure. */
    @JvmStatic external fun describe(identity: String): Array<String>?

    /** The new folder's id, or null on failure. */
    @JvmStatic external fun addFolder(treeUri: String, kinds: String): String?

    /** Seven fields per folder: id, name, tree URI, kinds, available ("1"/"0"), file count, error. */
    @JvmStatic external fun folders(): Array<String>
    @JvmStatic external fun removeFolder(id: String): Int
    @JvmStatic external fun rescan(id: String?): Int
}
