package org.androidaudioplugin.soundpack.client

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import org.androidaudioplugin.soundpack.SoundpackContract
import org.androidaudioplugin.soundpack.SoundpackKind

/** Asks the Soundpack Manager for access to one kind and keeps the grant, then finishes with
 * RESULT_OK when granted. No UI of its own; it exists so that callers without an activity
 * result (a plugin UI hosted by a service) can connect too (docs/PLAN.md §3.6). Start it with
 * [SoundpackClient.connectActivityIntent]. */
class SoundpackConnectActivity : Activity() {
    private val client by lazy {
        val kind = intent.getStringExtra(SoundpackContract.Hub.EXTRA_KIND)?.let { SoundpackKind.builtIn(it) }
        SoundpackClient(this, requireNotNull(kind) { "SoundpackConnectActivity needs a kind" })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null)
            return
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(client.connectIntent(), REQUEST_CONNECT)
        } catch (e: Exception) {
            // Not installed, or not visible to this app.
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    @Deprecated("Uses the platform API so that it needs no AndroidX dependency")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val granted = requestCode == REQUEST_CONNECT && runCatching { client.takeHubGrant(resultCode, data) }.getOrDefault(false)
        setResult(if (granted) RESULT_OK else RESULT_CANCELED)
        finish()
    }

    private companion object {
        const val REQUEST_CONNECT = 1
    }
}
