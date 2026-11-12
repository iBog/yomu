package com.yomu.app.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.util.Log
import android.view.WindowInsets
import com.yomu.app.capture.realDisplaySize
import android.view.WindowManager
import android.widget.FrameLayout
import com.yomu.pipeline.typesetting.TypesetBubble

class TranslationRenderOverlay(
    private val context: Context,
    private val windowManager: WindowManager,
    /** The whole display as it is turned now, which is what a captured frame is laid over. */
    private val displaySize: () -> Point = { realDisplaySize(windowManager) }
) {
    companion object {
        private const val TAG = "TranslationRender"
        private const val OCR_BACKGROUND_ALPHA = 0xAA
        private const val OCR_TEXT_SIZE_DP = 14f
        private const val OCR_LINE_SPACING = 1.2f
    }

    private var overlayView: FrameLayout? = null
    private var pageWidth: Int = 0
    private var pageHeight: Int = 0
    private var bubbleStates: List<OverlayBubbleState> = emptyList()

    /** Replaces the preview with the finished page in the supplied bubble order, enabling dismissal gestures. */
    fun show(
        bubbles: List<TypesetBubble>,
        pageWidth: Int,
        pageHeight: Int
    ) {
        remove()

        val params = createLayoutParams()
        overlayView = object : TranslatedPageLayout(context) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            private val screenLocation = IntArray(2)

            /** Maps finished bubbles into canvas coordinates and records their bounds for gesture hit testing. */
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                getLocationOnScreen(screenLocation)
                val display = displaySize()
                val mapParams = OverlayCoordinateMapper.params(
                    captureWidth = pageWidth,
                    captureHeight = pageHeight,
                    displayWidth = display.x,
                    displayHeight = display.y,
                    overlayScreenX = screenLocation[0],
                    overlayScreenY = screenLocation[1]
                )
                drawnBounds = bubbles.map { canvasBounds(it, mapParams, canvas) }
                bubbles.forEachIndexed { index, bubble ->
                    drawTypesetBubble(canvas, bubble, drawnBounds[index], paint, mapParams)
                }
            }
        }.apply {
            setWillNotDraw(false)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { remove() }
            onGesture = { outcome ->
                when (outcome) {
                    TranslatedPageGestures.Outcome.None -> Unit
                    TranslatedPageGestures.Outcome.DismissPage -> performClick()
                    TranslatedPageGestures.Outcome.CloseCard -> {
                        card?.let(::removeView)
                        card = null
                    }
                    is TranslatedPageGestures.Outcome.OpenCard -> {
                        val tapped = drawnBounds[outcome.bubbleIndex]
                        // The card takes the half of the page away from the bubble, so the reader still sees it.
                        val cardAtTop = (tapped.top + tapped.bottom) / 2 > height / 2
                        card = bubbleCard(context, bubbles[outcome.bubbleIndex], cardAtTop).also(::addView)
                    }
                }
            }
        }

        windowManager.addView(overlayView, params)
    }

    /** Snapshots OCR states and refreshes the preview, creating an untouchable window when needed. */
    fun showOcrBubbles(
        states: List<OverlayBubbleState>,
        pageWidth: Int,
        pageHeight: Int
    ) {
        this.pageWidth = pageWidth
        this.pageHeight = pageHeight
        bubbleStates = states.toList()

        val existingView = overlayView
        if (existingView != null) {
            existingView.invalidate()
            return
        }

        val params = createLayoutParams().apply {
            // A live preview sits above the floating button: taking touches would swallow the cancel tap (#76).
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        overlayView = object : FrameLayout(context) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            private val screenLocation = IntArray(2)

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                getLocationOnScreen(screenLocation)
                val display = displaySize()
                val mapParams = OverlayCoordinateMapper.params(
                    captureWidth = this@TranslationRenderOverlay.pageWidth,
                    captureHeight = this@TranslationRenderOverlay.pageHeight,
                    displayWidth = display.x,
                    displayHeight = display.y,
                    overlayScreenX = screenLocation[0],
                    overlayScreenY = screenLocation[1]
                )
                Log.d(
                    TAG,
                    "ocr render canvasW=${canvas.width} canvasH=${canvas.height} pageW=$pageWidth pageH=$pageHeight overlayX=${screenLocation[0]} overlayY=${screenLocation[1]} scaleX=${mapParams.scaleX} scaleY=${mapParams.scaleY}"
                )
                for (state in bubbleStates) {
                    drawOverlayBubbleState(canvas, state, paint, mapParams)
                }
            }
        }.apply {
            setWillNotDraw(false)
            setBackgroundColor(Color.TRANSPARENT)
        }

        windowManager.addView(overlayView, params)
    }

    /** Switches one preview box to its typeset bubble; the preview stays untouchable while bubbles arrive. */
    fun showTypesetBubble(bubble: TypesetBubble) {
        bubbleStates = bubbleStates.withTypeset(bubble)
        overlayView?.invalidate()
    }

    /** Detaches the current overlay and clears its preview states. */
    fun remove() {
        overlayView?.let { windowManager.removeView(it) }
        overlayView = null
        bubbleStates = emptyList()
    }

    /** Maps a typeset bubble from capture coordinates and clips its bounds to the canvas. */
    private fun canvasBounds(
        bubble: TypesetBubble,
        params: OverlayCoordinateMapper.MapParams,
        canvas: Canvas
    ): OverlayBounds = OverlayCoordinateMapper.clampToCanvas(
        OverlayCoordinateMapper.map(bubble.boundingBox, params),
        canvas.width.toFloat(),
        canvas.height.toFloat()
    )

    /** Creates a transparent, full-screen overlay window that does not take input focus. */
    private fun createLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSPARENT
        ).apply {
            // A reader that hides the status bar puts bubbles beside the camera cutout (#377). Bars are
            // fitted only while shown, so a bubble card never sits under the clock.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = WindowInsets.Type.systemBars()
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
    }

    private fun drawTypesetBubble(
        canvas: Canvas,
        bubble: TypesetBubble,
        mappedBounds: OverlayBounds,
        paint: Paint,
        params: OverlayCoordinateMapper.MapParams
    ) {
        val bx = mappedBounds.left
        val by = mappedBounds.top
        val bw = mappedBounds.width()
        val bh = mappedBounds.height()
        val radius = minOf(bw, bh) * 0.12f

        paint.isAntiAlias = true
        paint.color = bubble.backgroundColor
        canvas.drawRoundRect(bx, by, bx + bw, by + bh, radius, radius, paint)

        val saveCount = canvas.save()
        canvas.clipRect(bx, by, bx + bw, by + bh)

        paint.color = bubble.textColor
        // The box is mapped into canvas space; the typeset size has to follow it or text that
        // was measured to fit overflows the drawn bubble.
        val scale = minOf(params.scaleX, params.scaleY)
        paint.textSize = bubble.fontSize * scale
        paint.typeface = Typeface.DEFAULT

        val lineHeight = bubble.lineHeight * scale
        val totalTextHeight = lineHeight * bubble.textLines.size
        val blockTop = by + (bh - totalTextHeight) / 2f
        var textY = blockTop - paint.fontMetrics.ascent

        for (line in bubble.textLines) {
            val lineWidth = paint.measureText(line)
            val textX = bx + (bw - lineWidth) / 2f
            canvas.drawText(line, textX, textY, paint)
            textY += lineHeight
        }

        canvas.restoreToCount(saveCount)
    }

    /** Draws the final typeset bubble when available, otherwise its dark OCR preview. */
    private fun drawOverlayBubbleState(
        canvas: Canvas,
        state: OverlayBubbleState,
        paint: Paint,
        params: OverlayCoordinateMapper.MapParams
    ) {
        // Drawn exactly as the finished page will draw it, so nothing moves when the page completes.
        state.typeset?.let { typeset ->
            drawTypesetBubble(canvas, typeset, canvasBounds(typeset, params, canvas), paint, params)
            return
        }
        val bounds = state.bounds
        val sourceBounds = floatArrayOf(bounds.left, bounds.top, bounds.right, bounds.bottom)
        val mappedBounds = OverlayCoordinateMapper.map(sourceBounds, params)
        val bx = mappedBounds.left
        val by = mappedBounds.top
        val bw = mappedBounds.width()
        val bh = mappedBounds.height()
        val radius = minOf(bw, bh) * 0.12f

        paint.isAntiAlias = true
        paint.color = Color.argb(OCR_BACKGROUND_ALPHA, 0, 0, 0)
        canvas.drawRoundRect(bx, by, bx + bw, by + bh, radius, radius, paint)

        val saveCount = canvas.save()
        canvas.clipRect(bx, by, bx + bw, by + bh)

        paint.color = Color.WHITE
        paint.textSize = dpToPx(OCR_TEXT_SIZE_DP)
        paint.typeface = Typeface.DEFAULT_BOLD

        val lines = wrapText(state.ocrText, bw * 0.9f, paint)
        val lineHeight = paint.fontSpacing * OCR_LINE_SPACING
        val totalTextHeight = lineHeight * lines.size
        val blockTop = by + (bh - totalTextHeight) / 2f
        var textY = blockTop - paint.fontMetrics.ascent

        for (line in lines) {
            val lineWidth = paint.measureText(line)
            val textX = bx + (bw - lineWidth) / 2f
            canvas.drawText(line, textX, textY, paint)
            textY += lineHeight
        }

        canvas.restoreToCount(saveCount)
    }

    private fun dpToPx(dp: Float): Float = dp * context.resources.displayMetrics.density

    private fun wrapText(text: String, maxWidth: Float, paint: Paint): List<String> {
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var currentLine = StringBuilder()

        for (word in words) {
            val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
            if (paint.measureText(testLine) <= maxWidth) {
                currentLine.append(if (currentLine.isEmpty()) "" else " ").append(word)
            } else {
                if (currentLine.isNotEmpty()) lines.add(currentLine.toString())
                currentLine = StringBuilder(word)
            }
        }
        if (currentLine.isNotEmpty()) lines.add(currentLine.toString())
        return lines
    }
}
