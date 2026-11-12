package com.yomu.app.capture

import android.content.Context
import android.content.res.Configuration
import android.graphics.Point
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * The whole display in pixels, as it is turned now: the size a captured frame has to be to match the screen.
 *
 * From a service or application context neither `currentWindowMetrics` nor the display's own real size is trusted to
 * follow a rotation (on some phones they keep the portrait shape while the screen is on its side). The two sides of the
 * display do not change when it turns, so they are taken from the display, and which one is the width comes from the
 * app's configuration, which is updated on every turn.
 */
fun realDisplaySize(context: Context, windowManager: WindowManager): Point {
    val metrics = DisplayMetrics()
    @Suppress("DEPRECATION")
    windowManager.defaultDisplay.getRealMetrics(metrics)
    return orientedSize(metrics.widthPixels, metrics.heightPixels, context.resources.configuration.orientation)
}

/** The sides [first] and [second] of a display as [orientation] lays them out; a square or unknown orientation keeps them as given. */
internal fun orientedSize(first: Int, second: Int, orientation: Int): Point {
    val long = maxOf(first, second)
    val short = minOf(first, second)
    return when (orientation) {
        Configuration.ORIENTATION_LANDSCAPE -> Point(long, short)
        Configuration.ORIENTATION_PORTRAIT -> Point(short, long)
        else -> Point(first, second)
    }
}
