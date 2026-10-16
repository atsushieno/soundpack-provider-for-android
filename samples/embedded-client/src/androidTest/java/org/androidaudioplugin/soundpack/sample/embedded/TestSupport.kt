package org.androidaudioplugin.soundpack.sample.embedded

import android.app.Instrumentation
import android.content.Context
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.androidaudioplugin.soundpack.SoundpackKind
import org.androidaudioplugin.soundpack.host.FolderRegistry
import org.androidaudioplugin.soundpack.host.SoundpackFolderPickerActivity
import java.io.File
import java.util.regex.Pattern
import kotlin.random.Random

/** Device-test helpers: shared-storage fixtures through the shell, and the system folder
 * picker answered with UiAutomator (in English). */
internal object TestSupport {
    const val SAMPLE_BYTES = 65536

    val instrumentation: Instrumentation get() = InstrumentationRegistry.getInstrumentation()
    val context: Context get() = instrumentation.targetContext
    val device: UiDevice get() = UiDevice.getInstance(instrumentation)

    /** UiAutomation runs commands without a shell, so pipes and redirection go through a script
     * in a place the shell can read. */
    fun shell(vararg commands: String): String {
        val script = File(context.getExternalFilesDir(null), "test.sh").apply { writeText(commands.joinToString("\n") + "\n") }
        return ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("sh ${script.absolutePath}"))
            .use { it.readBytes().decodeToString() }
    }

    /** An SFZ instrument with an include-free sample, two SoundFonts, and hidden files, copied
     * to `/sdcard/Music/<name>` as a user would (adb push, a file manager). */
    fun writeSmallFolder(name: String) {
        val stage = File(context.getExternalFilesDir(null), "stage").apply { deleteRecursively(); mkdirs() }
        File(stage, "Piano/samples").mkdirs()
        File(stage, "Piano/piano.sfz").writeText("<control> default_path=samples/\n<region> sample=a.wav\n")
        File(stage, "Piano/samples/a.wav").writeBytes(Random(1).nextBytes(SAMPLE_BYTES))
        File(stage, "Piano/._piano.sfz").writeText("AppleDouble")
        File(stage, ".DS_Store").writeText("hidden")
        File(stage, "README").writeText("not an instrument")
        listOf("Sines.sf2", "Drums/Clicks.sf2").forEach { asset ->
            File(stage, "Kits/$asset").apply { parentFile!!.mkdirs() }.outputStream().use { out ->
                instrumentation.context.assets.open(asset).use { it.copyTo(out) }
            }
        }
        shell("rm -rf /sdcard/Music/$name", "cp -r ${stage.absolutePath} /sdcard/Music/$name")
    }

    /** Runs [SoundpackFolderPickerActivity] for [kinds], chosen first as a user would (D6), and
     * answers the system picker. */
    fun pickFolder(path: String, kinds: Set<SoundpackKind>, import: Boolean = false): Instrumentation.ActivityResult {
        val registry = FolderRegistry.get(context)
        val initial = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$path")
        val scenario = ActivityScenario.launchActivityForResult<SoundpackFolderPickerActivity>(registry.pickFolderIntent(kinds, import, initial))
        device.wait(Until.findObject(By.text(Pattern.compile("(?i)use this folder"))), 20_000)!!.click()
        device.wait(Until.findObject(By.text(Pattern.compile("(?i)allow"))), 10_000)!!.click()
        return scenario.result
    }

    fun <T> waitFor(timeoutMillis: Long = 20_000, description: String, probe: () -> T?): T {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            runCatching(probe).getOrNull()?.let { return it }
            Thread.sleep(250)
        }
        throw AssertionError("Timed out waiting for $description")
    }
}
