package org.androidaudioplugin.soundpack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SoundpackIdentityTest {
    @Test
    fun roundTrips() {
        val identity = SoundpackIdentity("sfz", "org.studiorack.vpo3", "Strings/1st Violins KS.sfz",
            revision = "3", via = "org.androidaudioplugin.sfz.vpo3.soundpack.sfz")
        val text = identity.format()
        assertEquals("commons-soundpack://sfz/org.studiorack.vpo3/Strings/1st%20Violins%20KS.sfz" +
            "?rev=3&via=org.androidaudioplugin.sfz.vpo3.soundpack.sfz", text)
        assertEquals(identity, SoundpackIdentity.parse(text))
    }

    @Test
    fun encodesNonAscii() {
        val identity = SoundpackIdentity("sf2", "saf-0123abcd", "ピアノ/Grand?#%.sf2")
        val text = identity.format()
        assertEquals(identity, SoundpackIdentity.parse(text))
        assertEquals(-1, text.substringAfter("://").indexOfAny(charArrayOf(' ', '#')))
    }

    @Test
    fun optionalPartsAndUnknownParameters() {
        assertEquals(SoundpackIdentity("jsfx", "p", "Effects/delay"),
            SoundpackIdentity.parse("commons-soundpack://jsfx/p/Effects/delay"))
        assertEquals(SoundpackIdentity("jsfx", "p", "a", revision = "2"),
            SoundpackIdentity.parse("COMMONS-SOUNDPACK://jsfx/p/a?future=1&rev=2"))
    }

    @Test
    fun rejectsMalformedIdentities() {
        listOf(
            "aap-sfz://org.example/Class/id?revision=1",
            "commons-soundpack://sfz/p",
            "commons-soundpack://SFZ/p/a.sfz",
            "commons-soundpack://sfz/p/../a.sfz",
            "commons-soundpack://sfz/p/a.sfz#frag",
            "commons-soundpack://sfz/bad id/a.sfz",
            "commons-soundpack://sfz/p/a%2",
            "commons-soundpack://sfz/p/a%FF.sfz",
            "commons-soundpack://sfz/p/a.sfz?rev=bad/rev",
        ).forEach { assertNull(it, SoundpackIdentity.parse(it)) }
    }
}
