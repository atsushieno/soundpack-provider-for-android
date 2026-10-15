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
 * conformance suite, and scans a large folder once rather than on every open.
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
        shell("rm -rf $SMALL")
    }

    /** The large fixture is kept between runs: creating 20,000 files through shared storage
     * takes minutes. Remove it with `adb shell rm -rf /sdcard/Music/SoundpackLargeTest`. */
    @After
    fun tearDown() {
        registry.awaitBackgroundScans()
        registry.folders().forEach { registry.remove(it.id) }
        shell("rm -rf $SMALL")
    }

    @Test
    fun linksServesAndListsAFolder() {
        writeSmallFolder()
        val result = pickFolder("Music/$SMALL_NAME", kinds = null)
        assertEquals("picker result: ${result.resultData?.getStringExtra(FolderRegistry.EXTRA_ERROR)}", Activity.RESULT_OK, result.resultCode)
        val folderId = result.resultData!!.getStringExtra(FolderRegistry.EXTRA_FOLDER_ID)!!
        val folder = registry.folder(folderId)!!
        assertEquals(FolderRegistry.packIdFor(folder.treeUri), folderId)
        assertEquals("both kinds have entries, so both are detected", setOf(SoundpackKind.SFZ, SoundpackKind.SF2), folder.kinds)
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
            assertEquals(SAMPLE_BYTES.toLong(), afd.declaredLength)
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
        shell("echo extra > $SMALL/Piano/samples/b.txt")
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

    @Test
    fun largeFolderIsScannedOnceNotPerOpen() {
        val directories = 20
        val perDirectory = 1000
        for (d in 1..directories)
            // This app cannot list shared storage itself (scoped storage); the shell can.
            if (shell("ls $LARGE/d$d 2>/dev/null | wc -l").trim() != perDirectory.toString())
                shell("mkdir -p $LARGE/d$d", "seq -f '$LARGE/d$d/f%g.dat' 1 $perDirectory | xargs touch")
        shell("echo '<region> sample=*sine' > $LARGE/Big.sfz")

        val started = System.nanoTime()
        val result = pickFolder("Music/$LARGE_NAME", kinds = setOf(SoundpackKind.SFZ))
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

    private fun writeSmallFolder() {
        // Staged in this app's external files directory, which the shell can read, then copied
        // to shared storage as a user would (adb push, a file manager).
        val stage = File(context.getExternalFilesDir(null), "stage").apply { deleteRecursively(); mkdirs() }
        File(stage, "Piano/samples").mkdirs()
        File(stage, "Piano/piano.sfz").writeText("<control> default_path=samples/\n<region> sample=a.wav\n")
        File(stage, "Piano/samples/a.wav").writeBytes(Random(1).nextBytes(SAMPLE_BYTES))
        File(stage, "Piano/._piano.sfz").writeText("AppleDouble")
        File(stage, ".DS_Store").writeText("hidden")
        File(stage, "README").writeText("not an instrument")
        listOf("Sines.sf2", "Drums/Clicks.sf2").forEach { name ->
            File(stage, "Kits/$name").apply { parentFile!!.mkdirs() }.outputStream().use { out ->
                instrumentation.context.assets.open(name).use { it.copyTo(out) }
            }
        }
        shell("cp -r ${stage.absolutePath} $SMALL")
    }

    /** Runs [SoundpackFolderPickerActivity] and answers the system picker as a user would. */
    private fun pickFolder(path: String, kinds: Set<SoundpackKind>?): android.app.Instrumentation.ActivityResult {
        val initial = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$path")
        val scenario = ActivityScenario.launchActivityForResult<SoundpackFolderPickerActivity>(registry.pickFolderIntent(kinds, initial))
        device.wait(Until.findObject(By.text(Pattern.compile("(?i)use this folder"))), 20_000)!!.click()
        device.wait(Until.findObject(By.text(Pattern.compile("(?i)allow"))), 10_000)!!.click()
        return scenario.result
    }

    /** UiAutomation runs commands without a shell, so pipes and redirection go through a script
     * in a place the shell can read. */
    private fun shell(vararg commands: String): String {
        val script = File(context.getExternalFilesDir(null), "test.sh").apply { writeText(commands.joinToString("\n") + "\n") }
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("sh ${script.absolutePath}"))
            .use { it.readBytes().decodeToString() }
    }

    private companion object {
        const val LOG_TAG = "EmbeddedFoldersTest"
        const val SMALL_NAME = "SoundpackEmbeddedTest"
        const val LARGE_NAME = "SoundpackLargeTest"
        const val SMALL = "/sdcard/Music/$SMALL_NAME"
        const val LARGE = "/sdcard/Music/$LARGE_NAME"
        const val SAMPLE_BYTES = 65536
    }
}
