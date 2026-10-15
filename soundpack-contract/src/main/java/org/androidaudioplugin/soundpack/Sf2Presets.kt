package org.androidaudioplugin.soundpack

/** Random access to a file's bytes, so readers can seek without loading whole files. */
interface RandomAccessSource {
    val size: Long

    /** Fills [buffer] from [position]; throws when the source ends first. */
    fun readFully(position: Long, buffer: ByteArray)
}

class ByteArraySource(private val bytes: ByteArray) : RandomAccessSource {
    override val size: Long get() = bytes.size.toLong()

    override fun readFully(position: Long, buffer: ByteArray) {
        if (position < 0 || position + buffer.size > bytes.size)
            throw Sf2FormatException("Read past the end of the data")
        bytes.copyInto(buffer, 0, position.toInt(), position.toInt() + buffer.size)
    }
}

class Sf2FormatException(message: String) : Exception(message)

/** Reads the preset list (PHDR) of a SoundFont 2 file without loading its samples, which is
 * what usf2plugin's browser needs to show presets (docs/PLAN.md §3.9, SF2 indexer). */
object Sf2Presets {
    data class Preset(val bank: Int, val program: Int, val name: String)

    private const val PHDR_RECORD = 38
    private const val MAX_PRESETS = 65536

    fun read(source: RandomAccessSource): List<Preset> {
        val header = ByteArray(12).also { source.readFully(0, it) }
        if (header.fourCc(0) != "RIFF" || header.fourCc(8) != "sfbk")
            throw Sf2FormatException("Not a SoundFont 2 file")
        val end = minOf(8 + header.u32(4), source.size)
        val pdta = findChunk(source, 12, end, "LIST", listType = "pdta")
            ?: throw Sf2FormatException("No pdta list")
        val phdr = findChunk(source, pdta.first + 12, pdta.first + 8 + pdta.second, "phdr")
            ?: throw Sf2FormatException("No phdr chunk")
        val (offset, size) = phdr
        if (size % PHDR_RECORD != 0L || size < PHDR_RECORD || offset + 8 + size > source.size)
            throw Sf2FormatException("Malformed or truncated phdr chunk")
        // The last record is the terminal "EOP" one.
        val count = (size / PHDR_RECORD - 1).toInt()
        if (count > MAX_PRESETS)
            throw Sf2FormatException("Too many presets")
        val records = ByteArray(count * PHDR_RECORD).also { source.readFully(offset + 8, it) }
        return (0 until count).map { i ->
            val base = i * PHDR_RECORD
            val nameEnd = (base until base + 20).firstOrNull { records[it] == 0.toByte() } ?: (base + 20)
            val name = (base until nameEnd).map { (records[it].toInt() and 0xFF).toChar() }.joinToString("").trim()
            Preset(bank = records.u16(base + 22), program = records.u16(base + 20), name = name)
        }.sortedWith(compareBy({ it.bank }, { it.program }))
    }

    /** `{"presets":[{"bank":0,"program":0,"name":"Piano"}]}`: the SF2 entry metadata. */
    fun toJson(presets: List<Preset>): String = presets.joinToString(",", "{\"presets\":[", "]}") {
        "{\"bank\":${it.bank},\"program\":${it.program},\"name\":${jsonString(it.name)}}"
    }

    /** Finds a chunk (or a LIST of [listType]) between [from] and [to]; returns its offset and
     * payload size. */
    private fun findChunk(source: RandomAccessSource, from: Long, to: Long, id: String, listType: String? = null): Pair<Long, Long>? {
        var position = from
        val header = ByteArray(8)
        while (position + 8 <= to) {
            source.readFully(position, header)
            val size = header.u32(4)
            if (header.fourCc(0) == id) {
                if (listType == null)
                    return position to size
                val type = ByteArray(4).also { source.readFully(position + 8, it) }
                if (type.fourCc(0) == listType)
                    return position to size
            }
            position += 8 + size + (size and 1)
        }
        return null
    }

    private fun ByteArray.fourCc(at: Int) = (at until at + 4).map { (this[it].toInt() and 0xFF).toChar() }.joinToString("")
    private fun ByteArray.u16(at: Int) = (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8)
    private fun ByteArray.u32(at: Int) = u16(at).toLong() or (u16(at + 2).toLong() shl 16)

    private fun jsonString(text: String) = buildString {
        append('"')
        for (c in text) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c < ' ' -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> append(c)
            }
        }
        append('"')
    }
}
