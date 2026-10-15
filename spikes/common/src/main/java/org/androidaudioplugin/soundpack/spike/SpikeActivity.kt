package org.androidaudioplugin.soundpack.spike

import android.app.Activity
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

/** A plain-View screen: a column of buttons, a selectable log, and an optional area below.
 * Everything logged also goes to logcat under [Spike.TAG]. */
open class SpikeActivity : Activity() {
    private lateinit var buttons: LinearLayout
    private lateinit var logView: TextView
    private lateinit var extras: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buttons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        logView = TextView(this).apply {
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
            textSize = 12f
        }
        extras = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scrolled = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(buttons)
            addView(logView)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(ScrollView(this@SpikeActivity).apply { addView(scrolled) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(extras)
            setOnApplyWindowInsetsListener { view, insets ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars())
                    view.setPadding(bars.left + PAD, bars.top + PAD, bars.right + PAD, bars.bottom + PAD)
                } else {
                    @Suppress("DEPRECATION")
                    view.setPadding(insets.systemWindowInsetLeft + PAD, insets.systemWindowInsetTop + PAD,
                        insets.systemWindowInsetRight + PAD, insets.systemWindowInsetBottom + PAD)
                }
                insets
            }
        }
        setContentView(root)
    }

    protected fun button(label: String, action: () -> Unit) {
        buttons.addView(Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
        })
    }

    /** Adds a view below the scrolling log, e.g. the S5 SurfaceView. */
    protected fun addExtra(view: View, height: Int) {
        extras.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height))
    }

    fun log(line: String) {
        Log.i(Spike.TAG, line)
        runOnUiThread { logView.append(line + "\n") }
    }

    /** Runs [block] off the main thread, logging any failure instead of crashing. */
    protected fun background(name: String, block: () -> Unit) {
        thread(name = name) {
            try {
                block()
            } catch (t: Throwable) {
                log("$name: FAILED ${t.javaClass.simpleName}: ${t.message}")
                Log.e(Spike.TAG, "$name failed", t)
            }
        }
    }

    private companion object {
        const val PAD = 16
    }
}
