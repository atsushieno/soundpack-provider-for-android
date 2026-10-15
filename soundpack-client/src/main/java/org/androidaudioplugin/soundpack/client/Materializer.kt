package org.androidaudioplugin.soundpack.client

import org.androidaudioplugin.soundpack.PackPath
import java.io.File
import java.io.IOException

/** Mirrors a pack into a directory, for engines that open files by path (ysfx for JSFX,
 * docs/PLAN.md §3.5).
 *
 * Incremental: a manifest in the destination records each file's size, modification time and
 * the pack revision, so unchanged files are not copied again, and files that left the pack are
 * deleted. Files the manifest does not know are never touched. Each file is written to a
 * temporary name and renamed into place. */
class Materializer internal constructor(private val client: SoundpackClient) {
    data class Result(val written: Int, val deleted: Int, val unchanged: Int)

    fun materialize(pack: SoundpackClient.Pack, destination: File): Result {
        if (!destination.isDirectory && !destination.mkdirs())
            throw IOException("Cannot create $destination")
        val root = destination.canonicalFile
        val manifestFile = File(root, MANIFEST)
        val previous = readManifest(manifestFile)
        val current = mutableMapOf<String, String>()
        var written = 0
        var unchanged = 0

        for (file in client.files(pack)) {
            val path = PackPath.normalize(file.path)?.takeIf { !PackPath.isHidden(it) } ?: continue
            val target = File(root, path)
            if (!target.canonicalPath.startsWith(root.path + File.separator))
                continue
            val key = "${file.size}\t${file.lastModified}\t${pack.revision}"
            current[path] = key
            if (previous[path] == key && target.isFile && target.length() == file.size) {
                unchanged++
                continue
            }
            copy(pack, path, target)
            written++
        }

        var deleted = 0
        for (path in previous.keys - current.keys) {
            val target = File(root, path)
            if (target.canonicalPath.startsWith(root.path + File.separator) && target.delete()) {
                deleted++
                pruneEmptyParents(target.parentFile, root)
            }
        }

        val temporary = File(root, "$MANIFEST.tmp")
        temporary.writeText(current.entries.joinToString("") { "${it.key}\t${it.value}\n" })
        if (!temporary.renameTo(manifestFile))
            throw IOException("Cannot write $manifestFile")
        return Result(written, deleted, unchanged)
    }

    private fun copy(pack: SoundpackClient.Pack, path: String, target: File) {
        val parent = target.parentFile!!
        if (!parent.isDirectory && !parent.mkdirs())
            throw IOException("Cannot create $parent")
        val temporary = File(parent, ".${target.name}.soundpack-tmp")
        client.openFile(pack, path).use { afd ->
            // AssetFileDescriptor's stream honours the start offset and length.
            afd.createInputStream().use { input -> temporary.outputStream().use { input.copyTo(it) } }
        }
        if (!temporary.renameTo(target)) {
            temporary.delete()
            throw IOException("Cannot move $temporary to $target")
        }
    }

    private fun pruneEmptyParents(directory: File?, root: File) {
        var current = directory
        while (current != null && current != root && current.list()?.isEmpty() == true) {
            current.delete()
            current = current.parentFile
        }
    }

    /** path -> "size\tlastModified\trevision" */
    private fun readManifest(file: File): Map<String, String> {
        if (!file.isFile)
            return emptyMap()
        return file.readLines().mapNotNull { line ->
            val fields = line.split('\t')
            if (fields.size == 4) fields[0] to fields.drop(1).joinToString("\t") else null
        }.toMap()
    }

    private companion object {
        const val MANIFEST = ".soundpack-manifest"
    }
}
