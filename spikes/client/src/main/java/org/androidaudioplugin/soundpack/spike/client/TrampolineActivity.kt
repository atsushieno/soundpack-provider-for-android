package org.androidaudioplugin.soundpack.spike.client

import android.content.Intent
import android.os.Bundle
import org.androidaudioplugin.soundpack.spike.SpikeActivity

/** S5: the app's own activity, started from the service-hosted UI. If it shows up, starting
 * it was allowed. From here the real consent flow (startActivityForResult) also works. */
class TrampolineActivity : SpikeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        S5Record.add(this, "TrampolineActivity started (task ${taskId})")
        log("S5: TrampolineActivity started from the service-hosted UI: the activity start was allowed.")
        button("Connect to the hub from here (the real flow)") { HubChecks.startConnect(this) }
    }

    @Deprecated("Spike code uses the platform API directly")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != HubChecks.REQUEST_CONNECT)
            return
        val taken = HubChecks.takeGrant(this, resultCode, data)
        S5Record.add(this, "consent from trampoline: $taken")
        log("S5: $taken")
        background("S5") { log("S5: " + HubChecks.readHub(this)) }
    }
}
