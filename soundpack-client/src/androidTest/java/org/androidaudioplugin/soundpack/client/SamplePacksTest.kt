package org.androidaudioplugin.soundpack.client

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.androidaudioplugin.soundpack.PackPath
import org.androidaudioplugin.soundpack.Sf2Presets
import org.androidaudioplugin.soundpack.SoundpackIdentity
import org.androidaudioplugin.soundpack.SoundpackKind
import org.androidaudioplugin.soundpack.testkit.ProviderConformance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.channels.FileChannel

/** Phase 1 gate (docs/PLAN.md §6), run from this test APK against the sample pack APKs, so
 * every call crosses processes as a real client's would. Install the samples first:
 *
 * ```
 * ./gradlew :samples:sample-sfz-pack:installDebug :samples:sample-sf2-pack:installDebug :samples:sample-jsfx-pack:installDebug
 * ``` */
@RunWith(AndroidJUnit4::class)
class SamplePacksTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun pack(kind: SoundpackKind, packId: String): SoundpackClient.Pack {
        val client = SoundpackClient(context, kind)
        val pack = client.catalog().packs.firstOrNull { it.id == packId }
        assumeTrue("$packId is not installed; install the sample packs first", pack != null)
        return pack!!
    }

    @Test
    fun samplePacksConform() {
        for ((kind, application) in listOf(SoundpackKind.SFZ to "sfz", SoundpackKind.SF2 to "sf2", SoundpackKind.JSFX to "jsfx")) {
            val authority = kind.authorityFor("org.androidaudioplugin.soundpack.sample.$application")
            val endpoints = SoundpackClient(context, kind).endpoints()
            assumeTrue("$authority is not installed", endpoints.any { it.authority == authority })
            ProviderConformance(context, authority).assertConforms()
        }
    }

    @Test
    fun sfzIncludeAndSampleOpenAcrossApks() {
        val client = SoundpackClient(context, SoundpackKind.SFZ)
        val pack = pack(SoundpackKind.SFZ, SFZ_PACK)
        val entry = client.entries(pack).single()
        assertEquals("instruments/main.sfz", entry.path)

        // What sfizz does: resolve the include against the entry's directory, then the sample
        // against default_path, all as pack-relative names.
        val session = client.open(SoundpackIdentity.parse(client.identityOf(entry).format())!!)
        val main = session.openEntry().use { it.createInputStream().bufferedReader().readText() }
        val include = Regex("#include \"([^\"]+)\"").find(main)!!.groupValues[1]
        val region = session.openFile("${PackPath.parent(entry.path)}/$include").use { it.createInputStream().bufferedReader().readText() }
        val defaultPath = Regex("default_path=(\\S+)").find(region)!!.groupValues[1]
        val sample = Regex("sample=(\\S+)").find(region)!!.groupValues[1]
        val samplePath = PackPath.normalize("${PackPath.parent(entry.path)}/$defaultPath$sample")
        assertEquals("samples/Sine.wav", samplePath)

        session.openFile(samplePath!!).use { afd ->
            assertTrue("an APK asset range starts inside the APK", afd.startOffset > 0)
            val mapped = afd.createInputStream().use { it.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength) }
            val header = ByteArray(12).also { mapped.get(it) }
            assertEquals("RIFF", String(header, 0, 4, Charsets.US_ASCII))
            assertEquals("WAVE", String(header, 8, 4, Charsets.US_ASCII))
            assertEquals(session.files.single { it.path == samplePath }.size, afd.declaredLength)
        }
    }

    @Test
    fun sf2PresetsComeFromMetadata() {
        val client = SoundpackClient(context, SoundpackKind.SF2)
        val entries = client.entries(pack(SoundpackKind.SF2, SF2_PACK)).associateBy { it.path }
        assertEquals(setOf("Sines.sf2", "Drums/Clicks.sf2"), entries.keys)
        assertEquals("Drums", entries.getValue("Drums/Clicks.sf2").groupPath)
        assertEquals(listOf(Sf2Presets.Preset(0, 0, "Sine Lead"), Sf2Presets.Preset(0, 1, "Sine Pad")),
            Sf2Metadata.presets(entries.getValue("Sines.sf2")))
        assertEquals(listOf(Sf2Presets.Preset(128, 0, "Click Kit")), Sf2Metadata.presets(entries.getValue("Drums/Clicks.sf2")))
    }

    @Test
    fun jsfxMaterializesIncrementally() {
        val client = SoundpackClient(context, SoundpackKind.JSFX)
        val pack = pack(SoundpackKind.JSFX, JSFX_PACK)
        assertEquals(setOf("Effects/Sample Gain.jsfx", "Effects/Sample Tremolo.jsfx"), client.entries(pack).map { it.path }.toSet())

        val destination = File(context.cacheDir, "materialize-test").apply { deleteRecursively() }
        assertEquals(Materializer.Result(written = 3, deleted = 0, unchanged = 0), client.materialize(pack, destination))
        assertTrue(File(destination, "Effects/sample-dsp.jsfx-inc").readText().contains("db_to_gain"))
        assertEquals(Materializer.Result(written = 0, deleted = 0, unchanged = 3), client.materialize(pack, destination))

        File(destination, "Effects/Sample Gain.jsfx").delete()
        val userFile = File(destination, "Effects/mine.jsfx").apply { writeText("desc:mine") }
        assertEquals(Materializer.Result(written = 1, deleted = 0, unchanged = 2), client.materialize(pack, destination))
        assertTrue("files the manifest does not know are left alone", userFile.exists())
    }

    @Test
    fun revisionPolicies() {
        val client = SoundpackClient(context, SoundpackKind.SFZ)
        val entry = client.entries(pack(SoundpackKind.SFZ, SFZ_PACK)).single()
        val stale = client.identityOf(entry).copy(revision = "0")
        assertTrue(runCatching { client.open(stale) }.exceptionOrNull() is SoundpackUnavailableException)
        assertNotNull(client.open(stale, SoundpackClient.RevisionPolicy.LATEST))
        val missing = client.identityOf(entry).copy(entryPath = "instruments/missing.sfz")
        assertTrue(runCatching { client.open(missing) }.exceptionOrNull() is SoundpackUnavailableException)
    }

    @Test
    fun catalogFlowEmitsInstalledPacks() = runBlocking {
        pack(SoundpackKind.SF2, SF2_PACK)
        val catalog = withTimeout(10_000) {
            SoundpackClient(context, SoundpackKind.SF2).catalogFlow().first { catalog -> catalog.packs.any { it.id == SF2_PACK } }
        }
        // Every provider answers, the hub's included when it is installed.
        assertTrue(catalog.failures.toString(), catalog.failures.isEmpty())
    }

    private companion object {
        const val SFZ_PACK = "org.androidaudioplugin.soundpack.sample.sine"
        const val SF2_PACK = "org.androidaudioplugin.soundpack.sample.sines"
        const val JSFX_PACK = "org.androidaudioplugin.soundpack.sample.effects"
    }
}
