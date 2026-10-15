package org.androidaudioplugin.soundpack.spike.client

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import kotlin.concurrent.thread

/** S2d: runs the hub read in the `:plugin` process. It proves that a grant taken by the
 * app's activity is usable by every process of the app (the UID holds it), as an AAP
 * plugin's service process needs. */
class PluginProcessService : Service() {
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        val replyTo = message.replyTo
        thread {
            val result = HubChecks.readHub(this)
            replyTo.send(Message.obtain(null, REPLY).apply {
                data = Bundle().apply { putString(KEY_RESULT, result) }
            })
        }
        true
    })

    override fun onBind(intent: Intent): IBinder = messenger.binder

    companion object {
        const val REQUEST = 1
        const val REPLY = 2
        const val KEY_RESULT = "result"
    }
}
