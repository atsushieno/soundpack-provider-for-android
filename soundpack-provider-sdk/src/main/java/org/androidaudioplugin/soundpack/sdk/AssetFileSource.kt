package org.androidaudioplugin.soundpack.sdk

import android.content.res.AssetFileDescriptor
import android.system.Os
import org.androidaudioplugin.soundpack.RandomAccessSource
import java.io.EOFException
import java.io.FileInputStream
import java.nio.ByteBuffer

/** Positional reads within an AssetFileDescriptor's range (which may be part of an APK). */
class AssetFileSource(private val afd: AssetFileDescriptor) : RandomAccessSource, AutoCloseable {
    private val channel = FileInputStream(afd.fileDescriptor).channel

    /** SAF documents report an unknown length (−1); then the file's own size counts. */
    override val size: Long =
        if (afd.declaredLength >= 0) afd.declaredLength else Os.fstat(afd.fileDescriptor).st_size - afd.startOffset

    override fun readFully(position: Long, buffer: ByteArray) {
        if (position < 0 || position + buffer.size > size)
            throw EOFException("Read past the end of the range")
        val target = ByteBuffer.wrap(buffer)
        while (target.hasRemaining()) {
            if (channel.read(target, afd.startOffset + position + target.position()) < 0)
                throw EOFException("Unexpected end of file")
        }
    }

    /** Closes the descriptor; the channel shares it and is not closed separately. */
    override fun close() = afd.close()
}
