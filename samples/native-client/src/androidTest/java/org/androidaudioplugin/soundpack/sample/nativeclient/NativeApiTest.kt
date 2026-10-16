package org.androidaudioplugin.soundpack.sample.nativeclient

import android.app.Activity
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.regex.Pattern

/** The C API as a native app uses it (docs/PLAN.md Phase 5). Needs the sample SF2 pack
 * installed (`./gradlew :samples:sample-sf2-pack:installDebug`); the folder test answers the
 * system picker in English and uses `/sdcard/Music/SoundpackNativeTest`. */
@RunWith(AndroidJUnit4::class)
class NativeApiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private var folderId: String? = null

    private fun entries(kind: String) = NativeClient.listEntries(kind).toList().chunked(6)

    private fun folders() = NativeClient.folders().toList().chunked(7)

    private fun sinesIdentity() = entries("sf2")
        .single { it[0].contains("/org.androidaudioplugin.soundpack.sample.sines/") && it[2] == "Sines.sf2" }[0]

    private fun shell(vararg commands: String) {
        val script = File(context.getExternalFilesDir(null), "test.sh").apply { writeText(commands.joinToString("\n") + "\n") }
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("sh ${script.absolutePath}"))
            .use { it.readBytes() }
    }

    @Before
    fun setUp() {
        assertEquals(NativeClient.lastError(), 0, NativeClient.init(context))
    }

    @After
    fun tearDown() {
        folderId?.let { NativeClient.removeFolder(it) }
        shell("rm -rf /sdcard/Music/$FOLDER")
    }

    @Test
    fun listsPackEntriesWithTheirMetadata() {
        val sines = entries("sf2").single { it[0] == sinesIdentity() }
        assertEquals("Sample Sines (SF2)", sines[1])
        assertTrue(sines[0], sines[0].startsWith("commons-soundpack://sf2/"))
        assertTrue("SF2 presets come from the metadata: ${sines[5]}", sines[5].contains("\"presets\""))
    }

    @Test
    fun opensPrefetchesAndMapsAnEntry() {
        val opened = NativeClient.describe(sinesIdentity())
        assertNotNull(NativeClient.lastError(), opened)
        val described = opened!!
        assertEquals("Sines.sf2", described[0])
        assertEquals(2, described[1].toInt())
        val size = described[2].toLong()
        assertTrue(size > 0)
        assertEquals("RIFF", described[3].substring(0, 4))
        assertEquals("sfbk", described[3].substring(8, 12))
        assertTrue("an APK asset is a range of the APK", described[4].toLong() > 0)
        assertEquals(size, described[5].toLong())
    }

    @Test
    fun reportsFailures() {
        assertNull(NativeClient.describe("not-an-identity"))
        assertTrue(NativeClient.lastError(), NativeClient.lastError().contains("Not a soundpack identity"))
        assertNull(NativeClient.addFolder("content://nowhere/tree/x", "sf2"))
        assertTrue(NativeClient.lastError().isNotEmpty())
    }

    @Test
    fun linksListsRescansAndRemovesAFolder() {
        val stage = File(context.getExternalFilesDir(null), "stage").apply { deleteRecursively(); mkdirs() }
        listOf("Sines.sf2", "Drums/Clicks.sf2").forEach { asset ->
            File(stage, asset).apply { parentFile!!.mkdirs() }.outputStream().use { out ->
                instrumentation.context.assets.open(asset).use { it.copyTo(out) }
            }
        }
        shell("rm -rf /sdcard/Music/$FOLDER", "cp -r ${stage.absolutePath} /sdcard/Music/$FOLDER")

        // The app's own picker, then the native code links the folder.
        val initial = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Music/$FOLDER")
        val scenario = ActivityScenario.launchActivityForResult<PickFolderActivity>(
            Intent(context, PickFolderActivity::class.java).putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial.toString()))
        device.wait(Until.findObject(By.text(Pattern.compile("(?i)use this folder"))), 20_000)!!.click()
        device.wait(Until.findObject(By.text(Pattern.compile("(?i)allow"))), 10_000)!!.click()
        assertEquals(Activity.RESULT_OK, scenario.result.resultCode)
        val tree = scenario.result.resultData!!.data!!.toString()

        val added = NativeClient.addFolder(tree, "sf2")
        assertNotNull(NativeClient.lastError(), added)
        folderId = added
        val folder = folders().single { it[0] == folderId }
        assertEquals(FOLDER, folder[1])
        assertEquals("sf2", folder[3])
        assertEquals("1", folder[4])
        assertEquals(2, folder[5].toInt())

        // Its SF2s are entries now, presets included, without the app opening them.
        val local = entries("sf2").filter { it[0].contains("/$folderId/") }
        assertEquals(setOf("Sines.sf2", "Drums/Clicks.sf2"), local.map { it[2] }.toSet())
        assertTrue(local.all { it[5].contains("\"presets\"") })

        // An identity the app builds from the folder id and a path opens the same file.
        val built = NativeClient.formatIdentity("sf2", folderId!!, "Drums/Clicks.sf2")!!
        assertEquals("Drums/Clicks.sf2", NativeClient.describe(built)!![0])

        assertEquals(NativeClient.lastError(), 0, NativeClient.rescan(folderId))
        assertEquals(0, NativeClient.rescan(null))

        assertEquals(0, NativeClient.removeFolder(folderId!!))
        assertFalse(folders().any { it[0] == folderId })
        assertTrue(entries("sf2").none { it[0].contains("/$folderId/") })
        folderId = null
    }

    private companion object {
        const val FOLDER = "SoundpackNativeTest"
    }
}
