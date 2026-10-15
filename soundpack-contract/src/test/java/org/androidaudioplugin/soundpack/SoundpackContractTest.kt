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

        assertEquals(SoundpackKind.SF2.readScope, SoundpackKind.ReadScope.ENTRIES_ONLY)
        assertTrue(SoundpackKind.isValidId("com.example.wavetable"))
        assertFalse(SoundpackKind.isValidId("SFZ"))
    }
}
