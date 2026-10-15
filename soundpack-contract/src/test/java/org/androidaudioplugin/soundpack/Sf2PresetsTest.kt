package org.androidaudioplugin.soundpack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream

class Sf2PresetsTest {
    /** A minimal RIFF sfbk with an INFO list and a pdta list holding only phdr: enough for the
     * reader, which looks at nothing else. */
    private fun sf2(vararg presets: Sf2Presets.Preset, padInfo: Boolean = true): ByteArray {
        fun chunk(id: String, payload: ByteArray) = ByteArrayOutputStream().apply {
            write(id.toByteArray(Charsets.US_ASCII))
            write(le32(payload.size))
            write(payload)
            if (payload.size % 2 == 1) write(0)
        }.toByteArray()
        fun list(type: String, vararg chunks: ByteArray) =
            chunk("LIST", type.toByteArray(Charsets.US_ASCII) + chunks.fold(ByteArray(0)) { acc, c -> acc + c })
        val records = ByteArrayOutputStream()
        (presets.toList() + Sf2Presets.Preset(0, 0, "EOP")).forEachIndexed { i, preset ->
            records.write(preset.name.toByteArray(Charsets.US_ASCII).copyOf(20))
            records.write(le16(preset.program))
            records.write(le16(preset.bank))
            records.write(le16(i))
            records.write(ByteArray(12))
        }
        val info = list("INFO", chunk("ifil", le16(2) + le16(1)), chunk("INAM", "Odd\u0000".toByteArray().let { if (padInfo) it.copyOf(3) else it }))
        val body = "sfbk".toByteArray() + info + list("pdta", chunk("phdr", records.toByteArray()))
        return "RIFF".toByteArray() + le32(body.size) + body
    }

    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
    private fun le32(v: Int) = le16(v and 0xFFFF) + le16(v ushr 16)

    @Test
    fun readsPresetsSortedByBankAndProgram() {
        val presets = Sf2Presets.read(ByteArraySource(sf2(
            Sf2Presets.Preset(128, 0, "Standard Kit"),
            Sf2Presets.Preset(0, 1, "Bright Piano"),
            Sf2Presets.Preset(0, 0, "Grand Piano"),
        )))
        assertEquals(listOf(
            Sf2Presets.Preset(0, 0, "Grand Piano"),
            Sf2Presets.Preset(0, 1, "Bright Piano"),
            Sf2Presets.Preset(128, 0, "Standard Kit"),
        ), presets)
    }

    @Test
    fun handlesOddChunkPaddingAndFullLengthNames() {
        val name = "ABCDEFGHIJKLMNOPQRST"
        assertEquals(listOf(Sf2Presets.Preset(0, 5, name)),
            Sf2Presets.read(ByteArraySource(sf2(Sf2Presets.Preset(0, 5, name), padInfo = true))))
    }

    @Test
    fun rejectsOtherFiles() {
        assertThrows(Sf2FormatException::class.java) { Sf2Presets.read(ByteArraySource("RIFF\u0004\u0000\u0000\u0000WAVE".toByteArray())) }
        val truncated = sf2(Sf2Presets.Preset(0, 0, "Piano")).let { it.copyOf(it.size - 10) }
        assertThrows(Sf2FormatException::class.java) { Sf2Presets.read(ByteArraySource(truncated)) }
    }

    @Test
    fun json() {
        assertEquals("""{"presets":[{"bank":0,"program":1,"name":"Say \"hi\"\\"}]}""",
            Sf2Presets.toJson(listOf(Sf2Presets.Preset(0, 1, "Say \"hi\"\\"))))
    }
}
