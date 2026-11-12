package com.yomu.app.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.yomu.app.R
import com.yomu.app.detection.DetectionThresholdStore
import com.yomu.app.ui.theme.paperBackground
import com.yomu.app.ui.theme.paperColors
import com.yomu.core.Constants
import kotlin.math.abs

class QuickSettingsPopup(
    private val context: Context,
    private val windowManager: WindowManager,
    private val modelName: () -> String,
    private val languageSwitch: OverlayLanguageSwitch,
    private val recallSwitch: OverlayRecallSwitch,
    private val onFontSizeChanged: (Float) -> Unit,
    private val onThresholdChanged: (Float) -> Unit,
    private val hasLastPage: () -> Boolean,
    private val onTranslateAgainRequested: () -> Unit,
    private val onReportRequested: () -> Unit,
    private val onOpenAppRequested: () -> Unit,
    private val onStopRequested: () -> Unit,
    private val blockTouchesBelow: (onRelease: () -> Unit) -> Unit,
    private val unblockTouchesBelow: () -> Unit
) {
    private var popupView: ScrollView? = null
    private val labels = mutableListOf<TextView>()
    private val mutedLabels = mutableListOf<TextView>()
    private val accentLabels = mutableListOf<TextView>()
    private val dimmableButtons = mutableListOf<Button>()
    private val seekBars = mutableListOf<SeekBar>()
    private var fontSizeSeekBar: SeekBar? = null
    private var thresholdSeekBar: SeekBar? = null
    private var thresholdLabel: TextView? = null
    private var fontSizeReset: Button? = null
    private var thresholdReset: Button? = null
    private val lastPageButtons = mutableListOf<Button>()
    private var fontSizeScale = Constants.DEFAULT_FONT_SIZE_SCALE
    private var threshold = DetectionThresholdStore.DEFAULT
    private val density = context.resources.displayMetrics.density

    fun show(anchorX: Int, anchorY: Int) {
        if (popupView != null) return
        val available = overlayControlSize(context, windowManager)
        val margin = dp(8)
        val width = dp(280).coerceAtMost((available.x - margin * 2).coerceAtLeast(1))
        val content = ScrollView(context).apply {
            addView(createContentView())
            background = context.paperBackground()
            elevation = density
        }
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val height = content.measuredHeight.coerceAtMost((available.y - margin * 2).coerceAtLeast(1))
        val params = WindowManager.LayoutParams(
            width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (anchorX + dp(16)).coerceIn(margin, (available.x - width - margin).coerceAtLeast(margin))
            y = (anchorY + dp(16)).coerceIn(margin, (available.y - height - margin).coerceAtLeast(margin))
        }
        popupView = content
        updateAppearance()
        blockTouchesBelow(::remove)
        windowManager.addView(content, params)
        content.alpha = 0f
        content.pivotX = (anchorX - params.x).toFloat().coerceIn(0f, width.toFloat())
        content.pivotY = (anchorY - params.y).toFloat().coerceIn(0f, height.toFloat())
        if (ValueAnimator.areAnimatorsEnabled()) { content.scaleX = 0.96f; content.scaleY = 0.96f }
        content.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start()
    }

    fun remove() {
        popupView?.let {
            it.animate().cancel()
            if (it.isAttachedToWindow) windowManager.removeView(it)
        }
        popupView = null
        unblockTouchesBelow()
        labels.clear()
        mutedLabels.clear()
        accentLabels.clear()
        dimmableButtons.clear()
        seekBars.clear()
        fontSizeSeekBar = null
        thresholdSeekBar = null
        thresholdLabel = null
        fontSizeReset = null
        thresholdReset = null
        lastPageButtons.clear()
    }

    fun updateAppearance() {
        val colors = context.paperColors()
        popupView?.background = context.paperBackground()
        labels.forEach { it.setTextColor(colors.ink) }
        mutedLabels.forEach { it.setTextColor(colors.inkMuted) }
        accentLabels.forEach { it.setTextColor(colors.accent) }
        val dimmableColors = ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(colors.inkMuted, colors.ink)
        )
        dimmableButtons.forEach { it.setTextColor(dimmableColors) }
        languageSwitch.applyColors(colors)
        recallSwitch.applyColors(colors)
        seekBars.forEach {
            it.thumbTintList = ColorStateList.valueOf(colors.accent)
            it.progressTintList = ColorStateList.valueOf(colors.accent)
            it.progressBackgroundTintList = ColorStateList.valueOf(colors.inkMuted)
        }
    }

    fun updateFontSizeScale(scale: Float) {
        fontSizeScale = scale.coerceIn(0.5f, 2f)
        fontSizeSeekBar?.progress = fontSizeProgress(fontSizeScale)
        refreshFontSize()
    }

    fun refreshLastPageAvailability() = lastPageButtons.forEach { it.isEnabled = hasLastPage() }

    fun updateThreshold(value: Float) {
        threshold = DetectionThresholdStore.snap(value)
        thresholdSeekBar?.progress = DetectionThresholdStore.stepOf(threshold)
        refreshThreshold()
    }

    private fun createContentView(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        addView(row().apply {
            addView(TextView(context).apply {
                text = context.getString(R.string.overlay_quick_settings)
                textSize = 20f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                labels.add(this)
            })
            addView(textButton("✕") { remove() }.apply {
                contentDescription = context.getString(R.string.overlay_quick_settings_close)
                labels.add(this)
            })
        })
        addView(mutedLabel(modelName()))
        addView(languageSwitch.createView())
        addView(labelRow(sectionLabel(context.getString(R.string.overlay_font_size)), resetButton { resetFontSize() }.also { fontSizeReset = it }))
        addView(SeekBar(context).apply {
            max = 100
            progress = fontSizeProgress(fontSizeScale)
            minimumHeight = dp(48)
            contentDescription = context.getString(R.string.overlay_font_size_description)
            fontSizeSeekBar = this
            seekBars.add(this)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        fontSizeScale = 0.5f + progress / 100f * 1.5f
                        refreshFontSize()
                        onFontSizeChanged(fontSizeScale)
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        })
        addView(labelRow(
            sectionLabel(thresholdText()).also { thresholdLabel = it },
            resetButton { resetThreshold() }.also { thresholdReset = it }
        ))
        addView(SeekBar(context).apply {
            max = DetectionThresholdStore.STEPS
            progress = DetectionThresholdStore.stepOf(threshold)
            minimumHeight = dp(48)
            contentDescription = context.getString(R.string.overlay_threshold_description)
            thresholdSeekBar = this
            seekBars.add(this)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        threshold = DetectionThresholdStore.atStep(progress)
                        refreshThreshold()
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                // Saved on release, like the Pipeline settings slider.
                override fun onStopTrackingTouch(seekBar: SeekBar?) = onThresholdChanged(threshold)
            })
        })
        addView(recallSwitch.createView())
        val lastPageActions = listOf(
            R.string.overlay_translate_again to onTranslateAgainRequested,
            R.string.overlay_report_last_page to onReportRequested,
        )
        for ((label, action) in lastPageActions) {
            addView(textButton(context.getString(label)) { remove(); action() }.apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
                dimmableButtons.add(this)
                lastPageButtons.add(this)
            })
        }
        addView(row().apply {
            addView(textButton(context.getString(R.string.overlay_open_yomu)) { remove(); onOpenAppRequested() }.apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                labels.add(this)
            })
            addView(textButton(context.getString(R.string.overlay_stop_service), onStopRequested).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                accentLabels.add(this)
            })
        })
        refreshThreshold()
        refreshFontSize()
        refreshLastPageAvailability()
    }

    private fun resetFontSize() {
        fontSizeScale = Constants.DEFAULT_FONT_SIZE_SCALE
        fontSizeSeekBar?.progress = fontSizeProgress(fontSizeScale)
        refreshFontSize()
        onFontSizeChanged(fontSizeScale)
    }

    private fun resetThreshold() {
        threshold = DetectionThresholdStore.DEFAULT
        thresholdSeekBar?.progress = DetectionThresholdStore.stepOf(threshold)
        refreshThreshold()
        onThresholdChanged(threshold)
    }

    // A slider step is coarser than the stored scale, so the default is "close enough", not equal.
    private fun refreshFontSize() {
        fontSizeReset?.isEnabled = abs(fontSizeScale - Constants.DEFAULT_FONT_SIZE_SCALE) > FONT_SIZE_STEP / 2
    }

    private fun refreshThreshold() {
        thresholdLabel?.text = thresholdText()
        thresholdReset?.isEnabled = threshold != DetectionThresholdStore.DEFAULT
    }

    private fun thresholdText(): String = context.getString(R.string.overlay_threshold, threshold)

    private fun fontSizeProgress(scale: Float): Int = ((scale - 0.5f) / 1.5f * 100).toInt()

    /** A section's label on the left, its small Reset on the right. */
    private fun labelRow(label: TextView, reset: Button): LinearLayout = row().apply {
        setPadding(0, dp(16), 0, dp(4))
        addView(label)
        addView(reset)
    }

    private fun sectionLabel(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 14f
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        labels.add(this)
    }

    private fun resetButton(onReset: () -> Unit): Button =
        textButton(context.getString(R.string.overlay_reset), onReset).apply { dimmableButtons.add(this) }

    private fun mutedLabel(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 13f
        mutedLabels.add(this)
    }

    private fun row(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun textButton(label: String, action: () -> Unit): Button = Button(context).apply {
        text = label
        textSize = 14f
        isAllCaps = false
        minWidth = dp(48)
        minHeight = dp(48)
        setPadding(dp(8), 0, dp(8), 0)
        background = null
        stateListAnimator = null
        setOnClickListener { action() }
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    private companion object {
        /** One of the font slider's 100 steps, in scale units. */
        const val FONT_SIZE_STEP = 1.5f / 100
    }
}
