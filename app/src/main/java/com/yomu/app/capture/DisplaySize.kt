package com.yomu.app.capture

import android.graphics.Point
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager

/** The whole display in pixels, as it is turned now: the size a captured frame has to be to match the screen. */
fun realDisplaySize(windowManager: WindowManager): Point {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val bounds = windowManager.currentWindowMetrics.bounds
        return Point(bounds.width(), bounds.height())
    }
    val metrics = DisplayMetrics()
    @Suppress("DEPRECATION")
    windowManager.defaultDisplay.getRealMetrics(metrics)
    return Point(metrics.widthPixels, metrics.heightPixels)
}
