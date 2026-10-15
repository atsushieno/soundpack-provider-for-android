package org.androidaudioplugin.soundpack

/** A saved reference to one entry of one pack (docs/PLAN.md §3.7):
 *
 * ```
 * commons-soundpack://<kind>/<packId>/<entryPath>?rev=<revision>&via=<authority>
 * ```
 *
 * It names content, not an Android component, so it stays meaningful in a project moved to
 * another device or platform. [revision] pins an immutable pack's revision; [via] is the
 * provider authority it was chosen from, a hint only. */
data class SoundpackIdentity(
    val kind: String,
    val packId: String,
    val entryPath: String,
    val revision: String? = null,
    val via: String? = null,
) {
    init {
        require(SoundpackKind.isValidId(kind)) { "Invalid kind: $kind" }
        require(SoundpackContract.isValidPackId(packId)) { "Invalid pack id: $packId" }
        require(PackPath.normalize(entryPath) == entryPath) { "Entry path is not normalized: $entryPath" }
        require(revision == null || SoundpackContract.isValidRevision(revision)) { "Invalid revision: $revision" }
    }

    fun format(): String = buildString {
        append(SoundpackContract.IDENTITY_SCHEME).append("://").append(kind).append('/').append(packId)
        entryPath.split('/').forEach { append('/').append(Percent.encode(it)) }
        val query = listOfNotNull(revision?.let { "rev=" + Percent.encode(it) }, via?.let { "via=" + Percent.encode(it) })
        if (query.isNotEmpty())
            append('?').append(query.joinToString("&"))
    }

    override fun toString() = format()

    companion object {
        private const val PREFIX = SoundpackContract.IDENTITY_SCHEME + "://"

        /** Returns null for anything that is not a well-formed identity. Unknown query
         * parameters are ignored, so later versions can add some. */
        fun parse(text: String): SoundpackIdentity? = try {
            require(text.length <= PackPath.MAX_LENGTH * 2 && text.startsWith(PREFIX, ignoreCase = true) && '#' !in text)
            val rest = text.substring(PREFIX.length)
            val path = rest.substringBefore('?')
            val query = if ('?' in rest) rest.substringAfter('?') else ""
            val segments = path.split('/')
            require(segments.size >= 3)
            val parameters = query.split('&').filter { it.isNotEmpty() }.associate {
                Percent.decode(it.substringBefore('=')) to Percent.decode(it.substringAfter('=', ""))
            }
            SoundpackIdentity(
                kind = segments[0],
                packId = Percent.decode(segments[1]),
                entryPath = segments.drop(2).joinToString("/") { Percent.decode(it) },
                revision = parameters["rev"],
                via = parameters["via"],
            )
        } catch (e: Exception) {
            // Saved projects are untrusted input: a bad identity is "unavailable", never a crash.
            null
        }
    }
}

/** RFC 3986 percent-encoding of one path segment or query value: everything but the
 * unreserved characters is encoded, as UTF-8. */
internal object Percent {
    private const val HEX = "0123456789ABCDEF"

    private fun isUnreserved(b: Int) =
        b in 'A'.code..'Z'.code || b in 'a'.code..'z'.code || b in '0'.code..'9'.code ||
            b == '-'.code || b == '.'.code || b == '_'.code || b == '~'.code

    fun encode(text: String): String = buildString {
        for (byte in text.encodeToByteArray()) {
            val b = byte.toInt() and 0xFF
            if (isUnreserved(b))
                append(b.toChar())
            else
                append('%').append(HEX[b shr 4]).append(HEX[b and 0xF])
        }
    }

    /** Throws on a malformed escape or invalid UTF-8. */
    fun decode(text: String): String {
        if ('%' !in text)
            return text
        val bytes = ArrayList<Byte>(text.length)
        var i = 0
        while (i < text.length) {
            val escape = text.indexOf('%', i).let { if (it < 0) text.length else it }
            bytes.addAll(text.substring(i, escape).encodeToByteArray().asList())
            if (escape == text.length)
                break
            require(escape + 2 < text.length) { "Truncated escape in $text" }
            val high = HEX.indexOf(text[escape + 1].uppercaseChar())
            val low = HEX.indexOf(text[escape + 2].uppercaseChar())
            require(high >= 0 && low >= 0) { "Bad escape in $text" }
            bytes.add(((high shl 4) or low).toByte())
            i = escape + 3
        }
        return bytes.toByteArray().decodeToString(throwOnInvalidSequence = true)
    }
}
