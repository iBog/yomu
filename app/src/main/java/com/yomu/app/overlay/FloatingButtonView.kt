package com.yomu.app.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.sin
import com.yomu.app.R
import com.yomu.app.ui.theme.paperColors

class FloatingButtonView(context: Context) : View(context) {
    enum class State { IDLE, TRANSLATING }

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        textSize = 24f * density
    }
    private val bounds = RectF()
    private val stopBounds = RectF()
    private var rotationAngle = 0f
    private var animator: ValueAnimator? = null
    private val motionListener = if (Build.VERSION.SDK_INT >= 33) {
        ValueAnimator.DurationScaleChangeListener { post { updateAppearance() } }
    } else null
    var currentState: State = State.IDLE
        private set

    init { updateAppearance() }

    fun setState(state: State) {
        currentState = state
        updateAppearance()
    }

    fun updateAppearance() {
        contentDescription = context.getString(
            if (currentState == State.IDLE) R.string.overlay_button_translate_description else R.string.overlay_button_stop_description
        )
        if (currentState == State.TRANSLATING && isAttachedToWindow && ValueAnimator.areAnimatorsEnabled()) {
            if (animator == null) {
                animator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 1000L
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    addUpdateListener {
                        rotationAngle = it.animatedFraction * 360f
                        invalidate()
                    }
                    start()
                }
            }
        } else {
            stopAnimation()
        }
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Build.VERSION.SDK_INT >= 33) {
            motionListener?.let { ValueAnimator.registerDurationScaleChangeListener(it) }
        }
        updateAppearance()
    }

    override fun onDetachedFromWindow() {
        if (Build.VERSION.SDK_INT >= 33) {
            motionListener?.let { ValueAnimator.unregisterDurationScaleChangeListener(it) }
        }
        stopAnimation()
        super.onDetachedFromWindow()
    }

    private fun stopAnimation() {
        animator?.cancel()
        animator = null
        rotationAngle = 0f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val colors = context.paperColors()
        val inset = 2f * density
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(width, height) / 2f - inset
        bounds.set(cx - radius, cy - radius, cx + radius, cy + radius)
        fill.color = colors.paperRaised
        canvas.drawCircle(cx, cy, radius, fill)
        edge.strokeWidth = 2f * density
        edge.color = colors.inkMuted
        canvas.drawCircle(cx, cy, radius, edge)
        bounds.inset(4f * density, 4f * density)
        fill.color = if (currentState == State.IDLE) colors.accent else colors.paperRaised
        canvas.drawCircle(cx, cy, bounds.width() / 2f, fill)
        if (currentState == State.IDLE) {
            textPaint.color = colors.onAccent
            val textYOffset = -(textPaint.ascent() + textPaint.descent()) / 2f
            canvas.drawText("読", cx, cy + textYOffset, textPaint)
        } else {
            // Stop square: a tap while translating cancels (#76).
            fill.color = colors.ink
            val half = 7f * density
            stopBounds.set(cx - half, cy - half, cx + half, cy + half)
            canvas.drawRoundRect(stopBounds, 2f * density, 2f * density, fill)
        }
        if (currentState == State.TRANSLATING) {
            edge.color = colors.accent
            val pulse = if (animator == null) 0f else (1f + sin(Math.toRadians(rotationAngle.toDouble())).toFloat()) / 2f
            edge.strokeWidth = (2f + pulse) * density
            canvas.drawArc(bounds, rotationAngle - 90f, 100f + 20f * pulse, false, edge)
        }
    }
}
