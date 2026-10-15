package org.androidaudioplugin.soundpack.spike.pack

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.view.SurfaceControlViewHost
import android.view.SurfaceView
import android.annotation.TargetApi
import org.androidaudioplugin.soundpack.spike.Spike
import org.androidaudioplugin.soundpack.spike.SpikeActivity

/** S5: this app plays the AAP host. Like aap-core's AudioPluginSurfaceControlClient, it binds
 * the client's view service, sends its SurfaceView's host token, and shows the
 * SurfacePackage it gets back. The client's embedded UI then tries to start activities. */
class EmbedHostActivity : SpikeActivity() {
    private lateinit var surfaceView: SurfaceView
    private var connection: ServiceConnection? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        log("Spike Pack: serves the S1/S3 provider, and is the host app for S5.")
        log("Run S1 in the client BEFORE S5: binding the client's service makes this app visible to it.")
        button("S5: embed client UI (bind like aap-core: BIND_AUTO_CREATE)") {
            embed(Context.BIND_AUTO_CREATE)
        }
        button("S5: embed client UI (+ BIND_ALLOW_ACTIVITY_STARTS, Android 14+)") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                embed(Context.BIND_AUTO_CREATE or Context.BIND_ALLOW_ACTIVITY_STARTS)
            else
                log("S5: BIND_ALLOW_ACTIVITY_STARTS needs Android 14")
        }
        surfaceView = SurfaceView(this).apply { setZOrderOnTop(true) }
        addExtra(surfaceView, (320 * resources.displayMetrics.density).toInt())
    }

    private fun embed(flags: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            log("S5: needs Android 11 (SurfaceControlViewHost)")
            return
        }
        connection?.let { unbindService(it) }
        val replies = Messenger(Handler(Looper.getMainLooper()) { message ->
            if (message.what == Spike.MSG_VIEW_CREATED)
                attach(message.data)
            true
        })
        val newConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                Messenger(binder).send(Message.obtain(null, Spike.MSG_CREATE_VIEW).apply {
                    replyTo = replies
                    data = Bundle().apply {
                        @Suppress("DEPRECATION")
                        putBinder(Spike.KEY_HOST_TOKEN, surfaceView.hostToken)
                        // As aap-core does on Android 15+.
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM)
                            surfaceView.rootSurfaceControl?.inputTransferToken?.let {
                                putParcelable(Spike.KEY_INPUT_TRANSFER_TOKEN, it)
                            }
                        putInt(Spike.KEY_DISPLAY_ID, surfaceView.display.displayId)
                        putInt(Spike.KEY_WIDTH, surfaceView.width)
                        putInt(Spike.KEY_HEIGHT, surfaceView.height)
                    }
                })
            }

            override fun onServiceDisconnected(name: ComponentName) {
                log("S5: client view service disconnected")
            }
        }
        val bound = bindService(Intent().setClassName(Spike.CLIENT_PACKAGE, Spike.VIEW_SERVICE_CLASS),
            newConnection, flags)
        log("S5: bindService(flags=0x${flags.toString(16)}) returned $bound")
        if (bound)
            connection = newConnection
    }

    @TargetApi(Build.VERSION_CODES.R)
    private fun attach(data: Bundle) {
        val surfacePackage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            data.getParcelable(Spike.KEY_SURFACE_PACKAGE, SurfaceControlViewHost.SurfacePackage::class.java)
        else
            @Suppress("DEPRECATION") data.getParcelable(Spike.KEY_SURFACE_PACKAGE)
        if (surfacePackage == null) {
            log("S5: no SurfacePackage in the reply")
            return
        }
        surfaceView.setChildSurfacePackage(surfacePackage)
        log("S5: client UI embedded. Tap its buttons; each should bring up an activity.")
    }

    override fun onDestroy() {
        connection?.let { unbindService(it) }
        super.onDestroy()
    }
}
