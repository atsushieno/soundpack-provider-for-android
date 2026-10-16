package org.androidaudioplugin.soundpack.spike.client

import android.annotation.TargetApi
import android.app.Service
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import android.view.ContextThemeWrapper
import android.window.InputTransferToken
import android.view.SurfaceControlViewHost
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.androidaudioplugin.soundpack.spike.Spike

/** S5: plays AAP's AudioPluginViewService. The host sends its SurfaceView's host token; we
 * render a small UI into a SurfaceControlViewHost and send back the SurfacePackage.
 * The UI's buttons start activities from this service-hosted UI, which is the step under
 * test. */
class SpikeViewService : Service() {
    private var viewHost: SurfaceControlViewHost? = null

    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.what == Spike.MSG_CREATE_VIEW && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            createView(message)
        true
    })

    override fun onBind(intent: Intent): IBinder = messenger.binder

    @TargetApi(Build.VERSION_CODES.R)
    private fun createView(message: Message) {
        val data = message.data
        val display = getSystemService(DisplayManager::class.java).getDisplay(data.getInt(Spike.KEY_DISPLAY_ID))
        viewHost?.release()
        // Same constructor choice as aap-core's AudioPluginViewService.
        val inputTransferToken = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM)
            data.getParcelable(Spike.KEY_INPUT_TRANSFER_TOKEN, InputTransferToken::class.java)
        else null
        val host = if (inputTransferToken != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM)
            SurfaceControlViewHost(this, display, inputTransferToken)
        else
            SurfaceControlViewHost(this, display, data.getBinder(Spike.KEY_HOST_TOKEN))
        host.setView(buildView(), data.getInt(Spike.KEY_WIDTH), data.getInt(Spike.KEY_HEIGHT))
        viewHost = host
        message.replyTo.send(Message.obtain(null, Spike.MSG_VIEW_CREATED).apply {
            this.data = Bundle().apply { putParcelable(Spike.KEY_SURFACE_PACKAGE, host.surfacePackage) }
        })
        S5Record.add(this, "embedded UI created in the host")
    }

    private fun buildView(): View {
        val themed = ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Light)
        val status = TextView(themed).apply { text = "Spike Client UI, hosted by SpikeViewService" }
        fun launch(label: String, intent: Intent) = Button(themed).apply {
            text = label
            isAllCaps = false
            setOnClickListener {
                try {
                    startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    status.text = "Requested: $label. Did a screen appear?"
                    S5Record.add(this@SpikeViewService, "startActivity requested: $label")
                } catch (e: Exception) {
                    status.text = "$label: ${e.javaClass.simpleName}"
                    S5Record.add(this@SpikeViewService, "startActivity threw ${e.javaClass.simpleName}: ${e.message}")
                    Log.e(Spike.TAG, "S5 launch failed", e)
                }
            }
        }
        return LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFE8EEF8.toInt())
            setPadding(24, 24, 24, 24)
            addView(status)
            addView(launch("Open own activity (like SfzFoldersActivity)",
                Intent(this@SpikeViewService, TrampolineActivity::class.java)))
            addView(launch("Open the hub's consent screen directly",
                Intent(Spike.ACTION_CONNECT).setPackage(Spike.HUB_PACKAGE)))
        }
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            viewHost?.release()
        super.onDestroy()
    }
}
