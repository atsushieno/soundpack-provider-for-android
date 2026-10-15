package org.androidaudioplugin.soundpack

import org.junit.Assert.assertEquals
import org.junit.Test

class SfzReferencesTest {
    private fun scan(entry: String, vararg files: Pair<String, String>): List<String> {
        val map = files.toMap()
        return SfzReferences.scan(entry) { map[it] }
    }

    @Test
    fun samplesWithSpacesBackslashesAndFollowingOpcodes() {
        val sfz = """
            <region> sample=Samples\kick_OH_FF_1.flac lorand=0 hirand=0.08
            <region> sample=Samples\snare  hit 1.wav  key=38
            <region>sample=Samples/last.wav<region> sample=Samples/next.wav
        """.trimIndent()
        assertEquals(
            listOf("kit/Samples/kick_OH_FF_1.flac", "kit/Samples/snare  hit 1.wav", "kit/Samples/last.wav", "kit/Samples/next.wav"),
            scan("kit/ALL.sfz", "kit/ALL.sfz" to sfz))
    }

    @Test
    fun defaultPathIsPrefixedAndResetByControl() {
        val sfz = """
            <control> default_path=Samples\Piano\
            <region> sample=C4.flac
            <control>
            <region> sample=other.flac
            <control> default_path=../shared/
            <region> sample=noise.flac
        """.trimIndent()
        assertEquals(listOf("p/Samples/Piano/C4.flac", "p/other.flac", "shared/noise.flac"),
            scan("p/piano.sfz", "p/piano.sfz" to sfz))
    }

    @Test
    fun definesTakeOneWordAndExpandTheShortestKnownName() {
        val sfz = """
            #define ${'$'}DIR Samples ignored words
            #define ${'$'}V soft
            <region> sample=${'$'}DIR/${'$'}V.wav
            <region> sample=${'$'}DIR/${'$'}VEL.wav
        """.trimIndent()
        // "$VEL" expands "$V" and keeps "EL", as sfizz does.
        assertEquals(listOf("Samples/soft.wav", "Samples/softEL.wav"), scan("a.sfz", "a.sfz" to sfz))
    }

    @Test
    fun includesAreRelativeToTheEntryFolderEvenWhenNested() {
        val main = "#include \"inc\\groups.sfz\"\n<region> sample=s/main.wav"
        val groups = "#include \"inc/regions.sfz\"\n<group> volume=-3"
        val regions = "<region> sample=s/r.wav"
        assertEquals(listOf("x/inc/groups.sfz", "x/inc/regions.sfz", "x/s/r.wav", "x/s/main.wav"),
            scan("x/main.sfz", "x/main.sfz" to main, "x/inc/groups.sfz" to groups, "x/inc/regions.sfz" to regions))
    }

    @Test
    fun commentsGeneratedSamplesAndEscapesAreSkipped() {
        val sfz = """
            // <region> sample=commented.wav
            /* <region> sample=block.wav
               <region> sample=block2.wav */
            <region> sample=*sine
            <region> sample=../../outside.wav
            <region> sample=kept.wav // trailing comment
            <region> sample=
        """.trimIndent()
        assertEquals(listOf("d/kept.wav"), scan("d/i.sfz", "d/i.sfz" to sfz))
    }

    @Test
    fun includeCyclesAndMissingIncludesEnd() {
        val a = "#include \"b.sfz\"\n#include \"missing.sfz\"\n<region> sample=a.wav"
        val b = "#include \"a.sfz\"\n<region> sample=b.wav"
        assertEquals(listOf("b.sfz", "b.wav", "a.wav"), scan("a.sfz", "a.sfz" to a, "b.sfz" to b))
    }

    @Test
    fun repeatedSamplesAppearOnceInFirstOrder() {
        val sfz = "<region> sample=x.wav\n<region> sample=y.wav\n<region> sample=x.wav"
        assertEquals(listOf("x.wav", "y.wav"), scan("i.sfz", "i.sfz" to sfz))
    }
}
