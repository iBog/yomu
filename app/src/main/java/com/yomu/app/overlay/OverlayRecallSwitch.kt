package com.yomu.app.overlay

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.yomu.app.R
import com.yomu.app.service.PageRecallStore
import com.yomu.app.ui.theme.YomuColors

/**
 * The quick-settings page recall row (#401): the same switch as Settings → Translation, over the same stored value.
 * [onFlipped] is called for the reader's taps alone; [update] shows a value stored elsewhere without reporting it.
 */
class OverlayRecallSwitch(private val context: Context, private val onFlipped: (Boolean) -> Unit) {
    private var label: TextView? = null
    private var switch: Switch? = null
    private var enabled = PageRecallStore.DEFAULT
    private val density = context.resources.displayMetrics.density

    /** Built afresh each time the popup opens, showing the value last passed to [update]. */
    fun createView(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(16), 0, 0)
        addView(TextView(context).apply {
            text = context.getString(R.string.settings_page_recall_title)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            label = this
        })
        addView(Switch(context).apply {
            isChecked = enabled
            minHeight = dp(48)
            contentDescription = context.getString(R.string.settings_page_recall_title)
            setOnCheckedChangeListener { _, checked -> flipped(checked) }
            switch = this
        })
    }

    fun update(enabled: Boolean) {
        this.enabled = enabled
        switch?.isChecked = enabled
    }

    fun applyColors(colors: YomuColors) {
        label?.setTextColor(colors.ink)
        switch?.thumbTintList = ColorStateList.valueOf(colors.accent)
        switch?.trackTintList = ColorStateList.valueOf(colors.inkMuted)
    }

    private fun flipped(checked: Boolean) {
        if (checked == enabled) return
        enabled = checked
        onFlipped(checked)
    }

    private fun dp(value: Int): Int = (value * density).toInt()
}
