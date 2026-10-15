package org.androidaudioplugin.soundpack

/** Pack-relative paths: "/"-separated, relative to the pack root, and never escaping it.
 * Resolution is purely lexical; nothing here touches a file system. */
object PackPath {
    const val MAX_LENGTH = 4096
    const val MAX_DEPTH = 64

    /** Resolves "." and ".." and drops empty segments. Returns null when [path] is empty,
     * absolute, too long or too deep, contains a NUL or a backslash, or climbs above the root
     * (`instruments/../samples/a.wav` is fine; `../a.wav` is not). */
    fun normalize(path: String): String? {
        if (path.isEmpty() || path.length > MAX_LENGTH || path.startsWith('/') || '\u0000' in path || '\\' in path)
            return null
        val segments = mutableListOf<String>()
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> continue
                ".." -> if (segments.isEmpty()) return null else segments.removeAt(segments.lastIndex)
                else -> segments.add(segment)
            }
        }
        if (segments.isEmpty() || segments.size > MAX_DEPTH)
            return null
        return segments.joinToString("/")
    }

    /** Dot-files (AppleDouble `._x`, `.DS_Store`, `.git/…`) are never listed or served. */
    fun isHidden(path: String) = path.split('/').any { it.startsWith('.') }

    /** Whether [name] can be one segment of a path. */
    fun isValidName(name: String) =
        name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\\' !in name && '\u0000' !in name

    fun fileName(path: String) = path.substringAfterLast('/')

    /** "" for a file at the root. */
    fun parent(path: String) = if ('/' in path) path.substringBeforeLast('/') else ""

    /** Lower-cased text after the last dot of the file name; "" when there is none. */
    fun extension(path: String): String {
        val name = fileName(path)
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) "" else name.substring(dot + 1).lowercase()
    }

    fun stem(path: String): String {
        val name = fileName(path)
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) name else name.substring(0, dot)
    }
}

/** A pack-relative glob, as used in pack declarations: `*` and `?` stay within one segment,
 * `**` crosses segments. Matching is case-sensitive. */
class PackGlob(val pattern: String) {
    private val regex: Regex = Regex(buildString {
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                pattern.startsWith("**/", i) -> { append("(?:.*/)?"); i += 3; continue }
                pattern.startsWith("**", i) -> { append(".*"); i += 2; continue }
                c == '*' -> append("[^/]*")
                c == '?' -> append("[^/]")
                else -> append(Regex.escape(c.toString()))
            }
            i++
        }
    })

    fun matches(path: String) = regex.matches(path)

    override fun toString() = pattern
}
