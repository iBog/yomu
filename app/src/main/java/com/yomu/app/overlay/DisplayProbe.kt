package com.yomu.app.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.util.Log
import android.view.View
import android.view.WindowManager

/**
 * An invisible window that covers the whole display, kept only to ask it how big the display is now. On some phones every
 * size a service can ask for directly (window metrics, the display's real size, the app's orientation) keeps the shape the
 * screen had when the service started, while a window is laid out again at every turn, so its size is the one that is
 * always right. [onChanged] hears each size, with `first` true for the first.
 */
class DisplayProbe(
    private val context: Context,
    private val windowManager: WindowManager,
    private val onChanged: (size: Point, first: Boolean) -> Unit
) {
    private var view: View? = null

    private companion object {
        const val TAG = "DisplayProbe"
    }

    /** The display as the probe last saw it, or null before its first layout. */
    var size: Point? = null
        private set

    fun attach() {
        if (view != null) return
        view = object : View(context) {
            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                super.onSizeChanged(w, h, oldw, oldh)
                if (w <= 0 || h <= 0) return
                val first = size == null
                size = Point(w, h)
                Log.i(TAG, "display ${w}x$h first=$first")
                onChanged(Point(w, h), first)
            }
        }.also { windowManager.addView(it, layoutParams()) }
    }

    fun detach() {
        view?.let { windowManager.removeView(it) }
        view = null
        size = null
    }

    private fun layoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        alpha = 0f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Bars and cutouts must not shrink it: it measures the display, not the room left for content.
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
    }
}
