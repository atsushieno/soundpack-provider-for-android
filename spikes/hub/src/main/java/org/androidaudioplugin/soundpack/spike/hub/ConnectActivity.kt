package org.androidaudioplugin.soundpack.spike.hub

import android.content.Intent
import android.os.Bundle
import org.androidaudioplugin.soundpack.spike.Spike
import org.androidaudioplugin.soundpack.spike.SpikeActivity

/** S2 consent: on "Allow", returns content://<hub authority>/ with
 * READ | PERSISTABLE | PREFIX, for the caller to persist. */
class ConnectActivity : SpikeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val caller = callingPackage
        if (caller == null)
            log("S2/S5: started without startActivityForResult, so no result can be returned. " +
                "This screen appearing at all is the S5 result.")
        else
            log("S2: $caller asks to read the spike SFZ content served by this hub.")
        button("Allow") {
            setResult(RESULT_OK, Intent().setData(Spike.hubRoot).addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION))
            log("S2: granted ${Spike.hubRoot} to $caller")
            finish()
        }
        button("Deny") {
            setResult(RESULT_CANCELED)
            finish()
        }
    }
}
