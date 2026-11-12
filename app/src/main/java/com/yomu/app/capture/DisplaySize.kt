package com.yomu.app.capture

import android.graphics.Point
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * The whole display in pixels, as it is turned now: the size a captured frame has to be to match the screen.
 * `currentWindowMetrics` is not used: from a service or application context it keeps the size the window had when it
 * was made, so it never sees a rotation, where the display's own real size does.
 */
fun realDisplaySize(windowManager: WindowManager): Point {
    val metrics = DisplayMetrics()
    @Suppress("DEPRECATION")
    windowManager.defaultDisplay.getRealMetrics(metrics)
    return Point(metrics.widthPixels, metrics.heightPixels)
}
