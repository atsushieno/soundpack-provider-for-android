package org.androidaudioplugin.soundpack.manager

import android.app.Activity
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.androidaudioplugin.soundpack.SoundpackContract
import org.androidaudioplugin.soundpack.SoundpackKind
import org.androidaudioplugin.soundpack.client.SoundpackClient
import org.androidaudioplugin.soundpack.client.SoundpackTypes
import org.androidaudioplugin.soundpack.host.FolderRegistry
import org.androidaudioplugin.soundpack.host.SoundpackFolderPickerActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.regex.Pattern

/** Resource types in the Soundpack Manager: a type the user adds reaches apps through the
 * types provider, and the default types can be edited and reset. Runs in the Manager's process
 * and drives the system folder picker (in English); it touches only its own type and the folder
 * `/sdcard/Music/SoundpackTypesTest`. */
@RunWith(AndroidJUnit4::class)
class ResourceTypesTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val registry = FolderRegistry.get(context)
    private val types = registry.types

    private fun shell(vararg commands: String): String {
        val script = File(context.getExternalFilesDir(null), "test.sh").apply { writeText(commands.joinToString("\n") + "\n") }
        return ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("sh ${script.absolutePath}"))
            .use { it.readBytes().decodeToString() }
    }

    private fun cleanUp() {
        types.added().filter { it.label == LABEL }.forEach { registry.removeType(it.id) }
        registry.folders().filter { it.displayName == NAME }.forEach { registry.remove(it.id) }
        types.reset(SoundpackKind.HOST_SAMPLE.id)
        shell("rm -rf $FOLDER")
    }

    @Before
    fun setUp() = cleanUp()

    @After
    fun tearDown() = cleanUp()

    @Test
    fun addedTypeReachesApps() {
        val stage = File(context.getExternalFilesDir(null), "stage").apply { deleteRecursively(); mkdirs() }
        File(stage, "Inst/samples").mkdirs()
        File(stage, "Inst/a.inst").writeText("instrument")
        File(stage, "Inst/samples/x.wav").writeBytes(ByteArray(SAMPLE_BYTES))
        File(stage, "notes.txt").writeText("notes")
        shell("cp -r ${stage.absolutePath} $FOLDER")

        val kind = types.add(LABEL, "For the test", listOf("*.inst"))
        assertEquals("the id comes from the label", "soundpack.test.sampler", kind.id)
        assertTrue(kind in registry.servedKinds())
        assertTrue("apps find it by its file type",
            SoundpackTypes.withFileType(context, "*.inst").any { it.id == kind.id && it.label == LABEL })

        val initial = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Music/$NAME")
        val scenario = ActivityScenario.launchActivityForResult<SoundpackFolderPickerActivity>(registry.pickFolderIntent(setOf(kind), false, initial))
        device.wait(Until.findObject(By.text(Pattern.compile("(?i)use this folder"))), 20_000)!!.click()
        device.wait(Until.findObject(By.text(Pattern.compile("(?i)allow"))), 10_000)!!.click()
        assertEquals(scenario.result.resultData?.getStringExtra(FolderRegistry.EXTRA_ERROR), Activity.RESULT_OK, scenario.result.resultCode)
        val folderId = scenario.result.resultData!!.getStringExtra(FolderRegistry.EXTRA_FOLDER_ID)!!

        // An app knows the type by its id only, and reads it like any kind.
        val client = SoundpackClient(context, SoundpackKind.forId(kind.id))
        fun pack() = client.catalog().let { catalog ->
            catalog.packs.singleOrNull { it.id == folderId }
                ?: throw AssertionError("no pack $folderId: ${catalog.endpoints}, ${catalog.packs.map { it.id }}, ${catalog.failures}")
        }
        val pack = pack()
        assertEquals(SoundpackContract.Types.authorityFor(context.packageName), pack.endpoint.authority)
        val entries = client.entries(pack)
        assertEquals(listOf("Inst/a.inst"), entries.map { it.path })
        assertTrue("the whole folder is served", "Inst/samples/x.wav" in client.files(pack).map { it.path })
        client.openFile(pack, "Inst/samples/x.wav").use { assertEquals(SAMPLE_BYTES.toLong(), it.declaredLength) }
        client.open(client.identityOf(entries.single())).openEntry().close()

        // Its file types decide the entries.
        types.update(kind.withPresentation(filePatterns = listOf("*.inst", "*.txt")))
        registry.typeChanged(kind.id)
        assertEquals(setOf("Inst/a.inst", "notes.txt"), client.entries(pack()).map { it.path }.toSet())

        // Deleted, it is no longer served, and the folder, linked only as it, goes too.
        registry.removeType(kind.id)
        assertFalse(client.catalog().packs.any { it.id == folderId })
        assertNull(registry.folder(folderId))
        assertFalse(SoundpackTypes.list(context).any { it.id == kind.id })
    }

    @Test
    fun defaultTypesCanBeEditedAndReset() {
        types.update(SoundpackKind.HOST_SAMPLE.withPresentation(label = "Samples", filePatterns = listOf("*.wav", "*.flac")))
        assertTrue(types.isEdited(SoundpackKind.HOST_SAMPLE.id))
        assertEquals("Samples", registry.kind(SoundpackKind.HOST_SAMPLE.id)!!.label)
        assertTrue(registry.kind(SoundpackKind.HOST_SAMPLE.id)!!.isEntryCandidate("Loops/a.flac"))

        types.reset(SoundpackKind.HOST_SAMPLE.id)
        assertFalse(types.isEdited(SoundpackKind.HOST_SAMPLE.id))
        assertEquals(SoundpackKind.HOST_SAMPLE.label, registry.kind(SoundpackKind.HOST_SAMPLE.id)!!.label)
        assertThrows(IllegalArgumentException::class.java) { types.remove(SoundpackKind.HOST_SAMPLE.id) }
    }

    private companion object {
        const val LABEL = "Soundpack Test Sampler"
        const val NAME = "SoundpackTypesTest"
        const val FOLDER = "/sdcard/Music/$NAME"
        const val SAMPLE_BYTES = 4096
    }
}
