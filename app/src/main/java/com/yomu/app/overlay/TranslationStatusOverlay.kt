package com.yomu.app.overlay

import android.content.Context
import com.yomu.app.R
import com.yomu.app.ui.theme.paperColors
import com.yomu.app.ui.theme.paperBackground
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import com.yomu.pipeline.TranslationPipeline

class TranslationStatusOverlay(
    private val context: Context,
    private val windowManager: WindowManager
) {
    private var statusView: TextView? = null

    fun showOrUpdate(message: String) {
        if (statusView == null) {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = (24 * context.resources.displayMetrics.density).toInt()
            }

            statusView = TextView(context).apply {
                text = message
                setTextColor(context.paperColors().ink)
                background = context.paperBackground()
                maxWidth = (overlayControlSize(context, windowManager).x - 32 * resources.displayMetrics.density).toInt().coerceAtLeast(1)
                accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
                textSize = 14f
                val horizontalPadding = (12 * context.resources.displayMetrics.density).toInt()
                val verticalPadding = (8 * context.resources.displayMetrics.density).toInt()
                setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
            }
            windowManager.addView(statusView, params)
            return
        }

        statusView?.text = message
    }

    fun updateAppearance() {
        statusView?.apply {
            setTextColor(context.paperColors().ink)
            background = context.paperBackground()
        }
    }

    fun remove() {
        statusView?.let { windowManager.removeView(it) }
        statusView = null
    }

    /** Returns the status view’s bounds in screen pixels, or null when no status view is shown. */
    fun boundsOnScreen(): OverlayBounds? = statusView?.boundsOnScreen()

    /** Returns the reader-facing status message for a pipeline stage. */
    fun messageForStage(stage: TranslationPipeline.Stage): String {
        return when (stage) {
            TranslationPipeline.Stage.BUBBLE_DETECTION -> context.getString(R.string.overlay_stage_bubble_detection)
            TranslationPipeline.Stage.OCR -> context.getString(R.string.overlay_stage_ocr)
            TranslationPipeline.Stage.CONTEXT_ASSEMBLY -> context.getString(R.string.overlay_stage_context)
            TranslationPipeline.Stage.TRANSLATION -> context.getString(R.string.overlay_stage_translation)
            TranslationPipeline.Stage.DONE -> context.getString(R.string.overlay_stage_done)
            TranslationPipeline.Stage.ERROR -> context.getString(R.string.overlay_stage_error)
        }
    }
}
