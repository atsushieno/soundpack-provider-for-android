package org.androidaudioplugin.soundpack.sample.embedded

import android.app.Activity
import android.content.Context
import android.provider.DocumentsContract
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.androidaudioplugin.soundpack.Sf2Presets
import org.androidaudioplugin.soundpack.SoundpackIdentity
import org.androidaudioplugin.soundpack.SoundpackKind
import org.androidaudioplugin.soundpack.client.Sf2Metadata
import org.androidaudioplugin.soundpack.client.SoundpackClient
import org.androidaudioplugin.soundpack.host.FolderRegistry
import org.androidaudioplugin.soundpack.host.SoundpackFolderPickerActivity
import org.androidaudioplugin.soundpack.testkit.ProviderConformance
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.regex.Pattern
import kotlin.random.Random

/** The Phase 2 gate (docs/PLAN.md §6): with no hub installed, this app links folders through
 * the system picker, lists them, serves their files as regular descriptors, passes the
 * conformance suite, and scans a large folder once rather than on every open. Folders are
 * linked for the kinds chosen first (D6), and an imported folder is served from its copy.
 *
 * It drives the system folder picker with UiAutomator, in English. */
@RunWith(AndroidJUnit4::class)
class EmbeddedFoldersTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val registry = FolderRegistry.get(context)

    @Before
    fun setUp() {
        registry.folders().forEach { registry.remove(it.id) }
        TestSupport.shell("rm -rf $SMALL")
    }

    /** The large fixture is kept between runs: creating 20,000 files through shared storage
     * takes minutes. Remove it with `adb shell rm -rf /sdcard/Music/SoundpackLargeTest`. */
    @After
    fun tearDown() {
        registry.awaitBackgroundScans()
        registry.folders().forEach { registry.remove(it.id) }
        TestSupport.shell("rm -rf $SMALL")
    }

    @Test
    fun linksServesAndListsAFolder() {
        TestSupport.writeSmallFolder(SMALL_NAME)
        val result = TestSupport.pickFolder("Music/$SMALL_NAME", kinds = setOf(SoundpackKind.SFZ, SoundpackKind.SF2))
        assertEquals("picker result: ${result.resultData?.getStringExtra(FolderRegistry.EXTRA_ERROR)}", Activity.RESULT_OK, result.resultCode)
        val folderId = result.resultData!!.getStringExtra(FolderRegistry.EXTRA_FOLDER_ID)!!
        val folder = registry.folder(folderId)!!
        assertEquals(FolderRegistry.packIdFor(folder.treeUri), folderId)
        assertEquals(setOf(SoundpackKind.SFZ, SoundpackKind.SF2), folder.kinds)
        assertFalse("used in place unless imported", folder.imported)
        assertEquals(1, result.resultData!!.getBundleExtra(FolderRegistry.EXTRA_ENTRY_COUNTS)!!.getInt("sfz"))

        // SFZ: entries, hidden files, and regular-file descriptors with known lengths.
        val sfz = SoundpackClient(context, SoundpackKind.SFZ)
        val pack = sfz.catalog().packs.single { it.id == folderId }
        assertTrue("served by this app's local provider", pack.endpoint.local)
        assertTrue(pack.live)
        assertEquals(listOf("Piano/piano.sfz"), sfz.entries(pack).map { it.path })
        val files = sfz.files(pack).map { it.path }.toSet()
        assertFalse("dot-files are not part of the pack", files.any { it.contains("/.") || it.startsWith(".") })
        assertTrue("Piano/samples/a.wav" in files)
        sfz.openFile(pack, "Piano/samples/a.wav").use { afd ->
            assertTrue(OsConstants.S_ISREG(Os.fstat(afd.fileDescriptor).st_mode))
            assertEquals(TestSupport.SAMPLE_BYTES.toLong(), afd.declaredLength)
        }

        // The same contract as any other provider.
        ProviderConformance(context, SoundpackKind.SFZ.localAuthorityFor(context.packageName)).assertConforms()
        ProviderConformance(context, SoundpackKind.SF2.localAuthorityFor(context.packageName)).assertConforms()

        // SF2: presets indexed from the folder, under their subfolder.
        val sf2 = SoundpackClient(context, SoundpackKind.SF2)
        val sf2Entries = sf2.entries(sf2.catalog().packs.single { it.id == folderId }).associateBy { it.path }
        assertEquals(setOf("Kits/Sines.sf2", "Kits/Drums/Clicks.sf2"), sf2Entries.keys)
        assertEquals(listOf(Sf2Presets.Preset(0, 0, "Sine Lead"), Sf2Presets.Preset(0, 1, "Sine Pad")),
            Sf2Metadata.presets(sf2Entries.getValue("Kits/Sines.sf2")))

        // A live pack: no revision in its identity, so it keeps resolving after the folder changes.
        val identity = sfz.identityOf(sfz.entries(pack).single())
        assertEquals(null, identity.revision)
        TestSupport.shell("echo extra > $SMALL/Piano/samples/b.txt")
        val revision = pack.revision
        registry.rescan(folderId)
        val updated = sfz.catalog().packs.single { it.id == folderId }
        assertTrue("revision moves when content changes", updated.revision.toLong() > revision.toLong())
        sfz.open(SoundpackIdentity.parse(identity.format())!!).openEntry().close()
        sfz.openFile(pack, "Piano/samples/a.wav").close() // an old session's revision still works for a live pack

        // The picker lists it.
        ActivityScenario.launch(MainActivity::class.java).use {
            assertNotNull("the picker shows the folder", device.wait(Until.findObject(By.textContains(SMALL_NAME)), 15_000))
            // Packs start expanded, groups collapsed.
            device.wait(Until.findObject(By.text("▶ Piano")), 5_000)!!.click()
            assertNotNull("and, inside its group, the entry", device.wait(Until.findObject(By.text("• piano")), 5_000))
        }
    }

    /** Linked for SFZ only and imported: the SoundFonts in it are not guessed into an SF2 pack,
     * files open from the copy in this app's storage, and the copy outlives the folder. */
    @Test
    fun importedFolderServesItsCopy() {
        TestSupport.writeSmallFolder(SMALL_NAME)
        val result = TestSupport.pickFolder("Music/$SMALL_NAME", kinds = setOf(SoundpackKind.SFZ), import = true)
        assertEquals("picker result: ${result.resultData?.getStringExtra(FolderRegistry.EXTRA_ERROR)}", Activity.RESULT_OK, result.resultCode)
        val folderId = result.resultData!!.getStringExtra(FolderRegistry.EXTRA_FOLDER_ID)!!
        val folder = registry.folder(folderId)!!
        assertEquals(setOf(SoundpackKind.SFZ), folder.kinds)
        assertTrue(folder.imported)
        assertEquals(null, folder.importError)
        val sf2 = SoundpackClient(context, SoundpackKind.SF2)
        assertFalse("nothing is guessed from the content", sf2.catalog().packs.any { it.id == folderId })

        val copy = File(context.noBackupFilesDir, "soundpack-imports/$folderId")
        val sfz = SoundpackClient(context, SoundpackKind.SFZ)
        fun readSample(): ByteArray {
            val pack = sfz.catalog().packs.single { it.id == folderId }
            return sfz.openFile(pack, "Piano/samples/a.wav").use { afd ->
                val opened = File(Os.readlink("/proc/self/fd/${afd.parcelFileDescriptor.fd}"))
                assertEquals("served from the copy", File(copy, "Piano/samples/a.wav").canonicalPath, opened.canonicalPath)
                afd.createInputStream().use { it.readBytes() }
            }
        }
        assertTrue(readSample().contentEquals(Random(1).nextBytes(TestSupport.SAMPLE_BYTES)))
        ProviderConformance(context, SoundpackKind.SFZ.localAuthorityFor(context.packageName)).assertConforms()

        // The folder is deleted: the copy keeps the pack available.
        TestSupport.shell("rm -rf $SMALL")
        registry.rescan(folderId)
        val gone = registry.folder(folderId)!!
        assertNotNull("the scan fails", gone.scanError)
        assertTrue("but the copy is used", gone.available)
        assertTrue(readSample().contentEquals(Random(1).nextBytes(TestSupport.SAMPLE_BYTES)))

        // In place again: the copy is deleted, and with the folder gone, so is the pack.
        assertFalse(registry.setImported(folderId, false).available)
        assertFalse(copy.exists())
        assertFalse(sfz.catalog().packs.any { it.id == folderId })
    }

    @Test
    fun largeFolderIsScannedOnceNotPerOpen() {
        val directories = 20
        val perDirectory = 1000
        for (d in 1..directories)
            // This app cannot list shared storage itself (scoped storage); the shell can.
            if (TestSupport.shell("ls $LARGE/d$d 2>/dev/null | wc -l").trim() != perDirectory.toString())
                TestSupport.shell("mkdir -p $LARGE/d$d", "seq -f '$LARGE/d$d/f%g.dat' 1 $perDirectory | xargs touch")
        TestSupport.shell("echo '<region> sample=*sine' > $LARGE/Big.sfz")

        val started = System.nanoTime()
        val result = TestSupport.pickFolder("Music/$LARGE_NAME", kinds = setOf(SoundpackKind.SFZ))
        val linkSeconds = (System.nanoTime() - started) / 1e9
        assertEquals(Activity.RESULT_OK, result.resultCode)
        val folderId = result.resultData!!.getStringExtra(FolderRegistry.EXTRA_FOLDER_ID)!!
        assertEquals(directories * perDirectory + 1, registry.folder(folderId)!!.fileCount)

        val client = SoundpackClient(context, SoundpackKind.SFZ)
        val pack = client.catalog().packs.single { it.id == folderId }
        // The first query in a process may start a background rescan of folders not yet
        // scanned in it; this one was scanned when it was linked, so nothing should run.
        registry.awaitBackgroundScans()
        val walks = registry.walkCount

        val opens = 200
        val started2 = System.nanoTime()
        repeat(opens) { client.openFile(pack, "d${it % directories + 1}/f${it + 1}.dat").close() }
        val openMillis = (System.nanoTime() - started2) / 1e6 / opens
        repeat(10) { client.catalog() }
        assertEquals("opens and catalog queries answer from the index", walks, registry.walkCount)
        Log.i(LOG_TAG, "Large folder: ${directories * perDirectory} files linked in %.1f s (picker included); %.2f ms per open"
            .format(linkSeconds, openMillis))
    }

    private companion object {
        const val LOG_TAG = "EmbeddedFoldersTest"
        const val SMALL_NAME = "SoundpackEmbeddedTest"
        const val LARGE_NAME = "SoundpackLargeTest"
        const val SMALL = "/sdcard/Music/$SMALL_NAME"
        const val LARGE = "/sdcard/Music/$LARGE_NAME"
    }
}
