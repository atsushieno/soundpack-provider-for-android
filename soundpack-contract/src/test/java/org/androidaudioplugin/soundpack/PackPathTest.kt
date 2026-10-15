package org.androidaudioplugin.soundpack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PackPathTest {
    @Test
    fun normalizesWithinTheRoot() {
        assertEquals("instruments/main.sfz", PackPath.normalize("instruments/main.sfz"))
        assertEquals("samples/a.wav", PackPath.normalize("instruments/../samples/a.wav"))
        assertEquals("a/b", PackPath.normalize("a//./b"))
        assertEquals("a", PackPath.normalize("a/b/.."))
    }

    @Test
    fun rejectsEscapesAndBadInput() {
        assertNull(PackPath.normalize(""))
        assertNull(PackPath.normalize("/etc/passwd"))
        assertNull(PackPath.normalize("../a.wav"))
        assertNull(PackPath.normalize("a/../../b"))
        assertNull(PackPath.normalize("a/.."))
        assertNull(PackPath.normalize("."))
        assertNull(PackPath.normalize("samples\\a.wav"))
        assertNull(PackPath.normalize("a\u0000b"))
        assertNull(PackPath.normalize("x".repeat(PackPath.MAX_LENGTH + 1)))
        assertNull(PackPath.normalize((1..PackPath.MAX_DEPTH + 1).joinToString("/") { "d" }))
    }

    @Test
    fun hiddenFiles() {
        assertTrue(PackPath.isHidden("._main.sfz"))
        assertTrue(PackPath.isHidden("samples/.DS_Store"))
        assertTrue(PackPath.isHidden(".git/config"))
        assertFalse(PackPath.isHidden("samples/a.b.wav"))
    }

    @Test
    fun nameParts() {
        assertEquals("sfz", PackPath.extension("instruments/Main.SFZ"))
        assertEquals("jsfx-inc", PackPath.extension("lib/db.jsfx-inc"))
        assertEquals("", PackPath.extension("Effects/delay"))
        assertEquals("", PackPath.extension(".hidden"))
        assertEquals("Main", PackPath.stem("instruments/Main.SFZ"))
        assertEquals("instruments", PackPath.parent("instruments/Main.SFZ"))
        assertEquals("", PackPath.parent("Main.SFZ"))
    }

    @Test
    fun globs() {
        assertTrue(PackGlob("Strings/*.sfz").matches("Strings/Violin.sfz"))
        assertFalse(PackGlob("Strings/*.sfz").matches("Strings/libs/Violin.sfz"))
        assertTrue(PackGlob("**/*.sf2").matches("a/b/c.sf2"))
        assertTrue(PackGlob("**/*.sf2").matches("c.sf2"))
        assertTrue(PackGlob("kit?.sfz").matches("kit1.sfz"))
        assertFalse(PackGlob("kit?.sfz").matches("kit10.sfz"))
        assertFalse(PackGlob("a.sfz").matches("aXsfz"))
    }
}
