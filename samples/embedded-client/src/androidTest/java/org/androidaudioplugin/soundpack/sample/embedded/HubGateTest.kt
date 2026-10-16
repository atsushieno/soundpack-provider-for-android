package org.androidaudioplugin.soundpack.sample.embedded

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.androidaudioplugin.soundpack.SoundpackContract
import org.androidaudioplugin.soundpack.SoundpackKind
import org.androidaudioplugin.soundpack.client.SoundpackClient
import org.androidaudioplugin.soundpack.host.FolderRegistry
import org.androidaudioplugin.soundpack.sample.embedded.TestSupport.context
import org.androidaudioplugin.soundpack.sample.embedded.TestSupport.device
import org.androidaudioplugin.soundpack.testkit.ProviderConformance
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The Phase 3 gate (docs/PLAN.md §6), from an app that also links folders itself:
 * - the hub's folders are readable with no connect step, like a pack app's;
 * - a folder linked both here and in the hub appears once;
 * - uninstalling the hub leaves local folders working (opt-in: it uninstalls the hub).
 *
 * Install the hub first (`./gradlew :app:installDebug`). The test answers the hub's screens and
 * the system picker with UiAutomator, in English. */
@RunWith(AndroidJUnit4::class)
class HubGateTest {
    private val registry = FolderRegistry.get(context)
    private val sfz = SoundpackClient(context, SoundpackKind.SFZ)
    private val sf2 = SoundpackClient(context, SoundpackKind.SF2)

    @Before
    fun setUp() {
        registry.folders().forEach { registry.remove(it.id) }
        TestSupport.shell("rm -rf /sdcard/Music/$NAME")
    }

    @After
    fun tearDown() {
        registry.folders().forEach { registry.remove(it.id) }
        TestSupport.shell("rm -rf /sdcard/Music/$NAME")
    }

    @Test
    fun sharedFoldersReachEveryAppOnce() {
        assumeTrue("Install the hub first: ./gradlew :app:installDebug", sfz.hubEndpoint() != null)
        val hubSfz = sfz.hubEndpoint()!!.authority
        val hubSf2 = sf2.hubEndpoint()!!.authority

        TestSupport.writeSmallFolder(NAME)
        val linked = TestSupport.pickFolder("Music/$NAME", kinds = setOf(SoundpackKind.SFZ, SoundpackKind.SF2))
        assertEquals(Activity.RESULT_OK, linked.resultCode)
        val folderId = linked.resultData!!.getStringExtra(FolderRegistry.EXTRA_FOLDER_ID)!!

        // No connect step: the hub answers this app for every kind, and reports no failure.
        assertFalse(folderId in hubPackIds(hubSfz))
        hubPackIds(hubSf2)
        assertTrue(sfz.catalog().failures.none { it.endpoint.authority == hubSfz })

        // Share the folder with the hub, as the folder screen's button does; the hub confirms the
        // kinds it is linked as here, and uses it in place by default.
        context.startActivity(registry.shareWithHubIntent(folderId)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        device.wait(Until.findObject(By.text("Share").enabled(true)), 30_000)!!.click()
        TestSupport.waitFor(description = "the hub to serve the shared folder") { hubPackIds(hubSfz).takeIf { folderId in it } }
        assertTrue(folderId in hubPackIds(hubSf2))

        // Linked here and in the hub, the folder has one pack id, so it appears once (local first).
        val packs = sfz.catalog().packs.filter { it.id == folderId }
        assertEquals(1, packs.size)
        assertTrue(packs.single().endpoint.local)

        ProviderConformance(context, hubSfz).assertConforms()
        ProviderConformance(context, hubSf2).assertConforms()

        // Unlinked here, the same saved identity resolves through the hub.
        val identity = sfz.identityOf(sfz.entries(packs.single()).single())
        registry.remove(folderId)
        val session = sfz.open(identity)
        assertEquals(hubSfz, session.pack.endpoint.authority)
        session.openEntry().close()

        // Leave the hub as it was: remove the shared folder there. A hub in real use lists other
        // folders too, and the list only holds what is on screen, so scroll to the card.
        context.startActivity(context.packageManager.getLaunchIntentForPackage(SoundpackContract.Hub.PACKAGE)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        device.wait(Until.findObject(By.text("Folders")), 15_000)!!.click()
        // The hub lists folders one kind at a time; remove it as SFZ, then as SF2.
        for (kind in listOf("SFZ", "SF2")) {
            device.wait(Until.findObject(By.text(kind)), 10_000)!!.click()
            device.wait(Until.findObject(By.scrollable(true)), 10_000)?.scrollUntil(Direction.DOWN, Until.findObject(By.text(NAME)))
            device.wait(Until.findObject(By.hasChild(By.text(NAME))), 10_000)!!.findObject(By.text("Remove")).click()
            device.wait(Until.gone(By.text(NAME)), 10_000)
        }
        TestSupport.waitFor(description = "the hub to stop serving the folder") {
            (folderId !in hubPackIds(hubSfz) && folderId !in hubPackIds(hubSf2)).takeIf { it }
        }
    }

    /** Opt-in, because it uninstalls the hub: run with `-e allowHubUninstall true`, then
     * reinstall it. */
    @Test
    fun uninstallingTheHubLeavesLocalFoldersWorking() {
        assumeTrue("Uninstalls the hub; run with -e allowHubUninstall true",
            InstrumentationRegistry.getArguments().getString("allowHubUninstall") == "true")
        TestSupport.writeSmallFolder(NAME)
        val folderId = TestSupport.pickFolder("Music/$NAME", kinds = setOf(SoundpackKind.SFZ)).resultData!!.getStringExtra(FolderRegistry.EXTRA_FOLDER_ID)!!

        TestSupport.shell("pm uninstall ${SoundpackContract.Hub.PACKAGE}")
        TestSupport.waitFor(description = "the hub to disappear") { (sfz.hubEndpoint() == null).takeIf { it } }

        val catalog = sfz.catalog()
        assertTrue(catalog.failures.isEmpty())
        val pack = catalog.packs.single { it.id == folderId }
        assertTrue(pack.endpoint.local)
        sfz.open(sfz.identityOf(sfz.entries(pack).single())).openEntry().close()
        ProviderConformance(context, SoundpackKind.SFZ.localAuthorityFor(context.packageName)).assertConforms()
    }

    private fun hubPackIds(authority: String): Set<String> =
        context.contentResolver.query(Uri.parse("content://$authority/${SoundpackContract.PATH_PACKS}"), null, null, null, null)!!
            .use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow(SoundpackContract.Packs.PACK_ID))) } }

    private companion object {
        const val NAME = "SoundpackHubTest"
    }
}
