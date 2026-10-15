package org.androidaudioplugin.soundpack.sample.embedded

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
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
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The Phase 3 gate (docs/PLAN.md §6), from an app that also links folders itself:
 * - it sees hub folders only for the kinds it was granted;
 * - revoking a grant in the hub is honoured;
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
        sfz.releaseHubGrant()
        sf2.releaseHubGrant()
        TestSupport.shell("rm -rf /sdcard/Music/$NAME")
    }

    @After
    fun tearDown() {
        registry.folders().forEach { registry.remove(it.id) }
        sfz.releaseHubGrant()
        sf2.releaseHubGrant()
        TestSupport.shell("rm -rf /sdcard/Music/$NAME")
    }

    @Test
    fun consentKindsDeduplicationAndRevocation() {
        assumeTrue("Install the hub first: ./gradlew :app:installDebug", sfz.hubEndpoint() != null)
        val hubSfz = sfz.hubEndpoint()!!.authority
        val hubSf2 = sf2.hubEndpoint()!!.authority

        TestSupport.writeSmallFolder(NAME)
        val linked = TestSupport.pickFolder("Music/$NAME")
        assertEquals(Activity.RESULT_OK, linked.resultCode)
        val folderId = linked.resultData!!.getStringExtra(FolderRegistry.EXTRA_FOLDER_ID)!!

        // Not connected: the hub refuses, and the catalog reports consent rather than an error.
        assertTrue(sfz.hubAccess() is SoundpackClient.HubAccess.NeedsConsent)
        assertRefused(hubSfz)
        assertTrue(sfz.catalog().failures.single { it.endpoint.authority == hubSfz }.needsConsent)

        // Connect for SFZ only, through the client library's trampoline and the hub's screen.
        // Not closed: the trampoline has already finished, and closing a finished scenario throws.
        val connect = ActivityScenario.launchActivityForResult<Activity>(sfz.connectActivityIntent())
        device.wait(Until.findObject(By.text("Allow")), 15_000)!!.click()
        assertEquals(Activity.RESULT_OK, connect.result.resultCode)
        assertEquals(SoundpackClient.HubAccess.Granted, sfz.hubAccess())
        assertTrue(sf2.hubAccess() is SoundpackClient.HubAccess.NeedsConsent)

        // Share the folder with the hub, as the folder screen's button does; the hub asks.
        context.startActivity(registry.shareWithHubIntent(folderId)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        device.wait(Until.findObject(By.text("Share").enabled(true)), 30_000)!!.click()
        TestSupport.waitFor(description = "the hub to serve the shared folder") { hubPackIds(hubSfz).takeIf { folderId in it } }

        // Granted kinds only: the hub's SF2 provider still refuses this app.
        assertRefused(hubSf2)

        // Linked here and in the hub, the folder has one pack id, so it appears once (local first).
        val packs = sfz.catalog().packs.filter { it.id == folderId }
        assertEquals(1, packs.size)
        assertTrue(packs.single().endpoint.local)

        // Through the grant, the hub's provider follows the contract.
        ProviderConformance(context, hubSfz).assertConforms()

        // Unlinked here, the same saved identity resolves through the hub.
        val identity = sfz.identityOf(sfz.entries(packs.single()).single())
        registry.remove(folderId)
        val session = sfz.open(identity)
        assertEquals(hubSfz, session.pack.endpoint.authority)
        session.openEntry().close()

        // Disconnect in the hub's "Connected apps": access ends at once.
        context.startActivity(context.packageManager.getLaunchIntentForPackage(SoundpackContract.Hub.PACKAGE)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        device.wait(Until.findObject(By.text("Connected apps")), 15_000)!!.click()
        device.wait(Until.findObject(By.hasChild(By.text("$APP_LABEL: SFZ"))), 10_000)!!.findObject(By.text("Disconnect")).click()
        TestSupport.waitFor(description = "the grant to be revoked") { (sfz.hubAccess() is SoundpackClient.HubAccess.NeedsConsent).takeIf { it } }
        assertRefused(hubSfz)

        // Leave the hub as it was: remove the shared folder there.
        device.findObject(By.text("Folders")).click()
        device.wait(Until.findObject(By.hasChild(By.text(NAME))), 10_000)!!.findObject(By.text("Remove")).click()
        device.wait(Until.gone(By.text(NAME)), 10_000)
    }

    /** Opt-in, because it uninstalls the hub: run with `-e allowHubUninstall true`, then
     * reinstall it. */
    @Test
    fun uninstallingTheHubLeavesLocalFoldersWorking() {
        assumeTrue("Uninstalls the hub; run with -e allowHubUninstall true",
            InstrumentationRegistry.getArguments().getString("allowHubUninstall") == "true")
        TestSupport.writeSmallFolder(NAME)
        val folderId = TestSupport.pickFolder("Music/$NAME").resultData!!.getStringExtra(FolderRegistry.EXTRA_FOLDER_ID)!!

        TestSupport.shell("pm uninstall ${SoundpackContract.Hub.PACKAGE}")
        TestSupport.waitFor(description = "the hub to disappear") { (sfz.hubEndpoint() == null).takeIf { it } }
        assertEquals(SoundpackClient.HubAccess.NotInstalled, sfz.hubAccess())

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

    private fun assertRefused(authority: String) {
        val result = runCatching { hubPackIds(authority) }
        assertTrue("$authority must refuse this app, but returned ${result.getOrNull()}", result.exceptionOrNull() is SecurityException)
    }

    private companion object {
        const val NAME = "SoundpackHubTest"
        const val APP_LABEL = "Soundpack Embedded Sample"
    }
}
