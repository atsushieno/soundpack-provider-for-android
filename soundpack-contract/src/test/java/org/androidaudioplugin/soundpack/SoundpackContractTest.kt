package org.androidaudioplugin.soundpack

import org.androidaudioplugin.soundpack.SoundpackContract.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundpackContractTest {
    @Test
    fun routesRoundTrip() {
        assertEquals(Route.Packs, SoundpackContract.route(SoundpackContract.packsSegments()))
        assertEquals(Route.Entries("p"), SoundpackContract.route(SoundpackContract.entriesSegments("p")))
        assertEquals(Route.Files("p"), SoundpackContract.route(SoundpackContract.filesSegments("p")))
        assertEquals(Route.File("p", "3", "samples/a b.wav"),
            SoundpackContract.route(SoundpackContract.fileSegments("p", "3", "samples/a b.wav")))
    }

    @Test
    fun fileRoutesKeepRawPathsForTheProviderToNormalize() {
        assertEquals(Route.File("p", "1", "../x"), SoundpackContract.route(listOf("packs", "p", "rev", "1", "files", "..", "x")))
    }

    @Test
    fun rejectsUnknownRoutes() {
        assertNull(SoundpackContract.route(emptyList()))
        assertNull(SoundpackContract.route(listOf("pack")))
        assertNull(SoundpackContract.route(listOf("packs", "bad id", "files")))
        assertNull(SoundpackContract.route(listOf("packs", "p", "rev", "1", "files")))
        assertNull(SoundpackContract.route(listOf("packs", "p", "rev", "bad/rev", "files", "a")))
        assertNull(SoundpackContract.route(listOf("packs", "p", "other")))
    }

    @Test
    fun kinds() {
        assertEquals("org.androidaudioplugin.soundpack.sfz.V1", SoundpackKind.SFZ.action)
        assertEquals("com.example.app.soundpack.sf2", SoundpackKind.SF2.authorityFor("com.example.app"))
        assertEquals("com.example.app.soundpack.local.jsfx", SoundpackKind.JSFX.localAuthorityFor("com.example.app"))
        assertTrue(SoundpackKind.JSFX.matchesAuthority("com.example.app.soundpack.local.jsfx"))
        assertFalse(SoundpackKind.SFZ.matchesAuthority("com.example.app.soundpack.sf2"))

        assertTrue(SoundpackKind.SFZ.isEntryCandidate("Piano/Grand.SFZ"))
        assertFalse(SoundpackKind.SFZ.isEntryCandidate("samples/a.wav"))
        assertTrue(SoundpackKind.SF2.isEntryCandidate("GM.sf3"))
        assertTrue(SoundpackKind.JSFX.isEntryCandidate("Effects/delay"))
        assertTrue(SoundpackKind.JSFX.isEntryCandidate("Effects/delay.jsfx"))
        assertFalse(SoundpackKind.JSFX.isEntryCandidate("Effects/lib.jsfx-inc"))

        assertTrue(SoundpackKind.HOST_SAMPLE.isEntryCandidate("Loops/Drums.WAV"))

        assertEquals(SoundpackKind.SF2.readScope, SoundpackKind.ReadScope.ENTRIES_ONLY)
        assertTrue(SoundpackKind.isValidId("com.example.wavetable"))
        assertFalse(SoundpackKind.isValidId("SFZ"))
        assertFalse("taken by the types provider", SoundpackKind.isValidId("types"))
        assertEquals(SoundpackKind.SFZ, SoundpackKind.forId("sfz"))
        assertFalse(SoundpackKind.forId("inst").isBuiltIn)
    }

    @Test
    fun filePatterns() {
        assertTrue(FilePatterns.matches("*.inst", "Strings.INST"))
        assertFalse(FilePatterns.matches("*.inst", "Strings.ins"))
        assertTrue(FilePatterns.matches("piano-??.wav", "piano-01.wav"))
        assertFalse(FilePatterns.matches("piano-??.wav", "piano-1.wav"))
        assertTrue(FilePatterns.matches(FilePatterns.NO_EXTENSION, "delay"))
        assertFalse(FilePatterns.matches(FilePatterns.NO_EXTENSION, "delay.jsfx"))
        assertTrue("regex characters are literal", FilePatterns.matches("a+b.(x)", "A+B.(X)"))
        assertFalse(FilePatterns.isValid("dir/*.wav"))
        assertTrue(FilePatterns.matchesAny(listOf("*.inst", "*.mlt"), "Library/Multis/Big.mlt"))
        assertEquals(listOf("*.inst", "*.mlt"), FilePatterns.parse(" *.inst, *.mlt;*.inst "))
    }

    @Test
    fun typeRoutes() {
        val file = SoundpackContract.fileSegments("p", "1", "a/b.inst")
        assertEquals("inst" to Route.File("p", "1", "a/b.inst"),
            SoundpackContract.Types.route(SoundpackContract.Types.segments("inst", file)))
        assertNull(SoundpackContract.Types.route(SoundpackContract.Types.listSegments()))
        assertNull(SoundpackContract.Types.route(listOf("types", "Bad", "packs")))
        assertTrue(SoundpackContract.Types.matchesAuthority(SoundpackContract.Types.authorityFor("org.example")))
    }
}
