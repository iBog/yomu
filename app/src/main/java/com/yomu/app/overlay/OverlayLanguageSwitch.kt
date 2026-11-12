package com.yomu.app.overlay

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import com.yomu.app.R
import com.yomu.app.ui.UiText
import com.yomu.app.ui.labelRes
import com.yomu.app.ui.theme.YomuColors
import com.yomu.app.ui.theme.paperBackground
import com.yomu.core.TargetLanguage

/**
 * The quick-settings language chip, drawn like the one on Home; tapping it unfolds the languages below it (#338).
 * [onChosen] decides what a choice does, then calls [update] once a language is set.
 */
class OverlayLanguageSwitch(
    private val context: Context,
    private val sourceName: () -> String,
    private val targetLanguage: () -> TargetLanguage,
    private val unavailable: (TargetLanguage) -> UiText?,
    private val onChosen: (TargetLanguage) -> Unit
) {
    private var chip: TextView? = null
    private var options: RadioGroup? = null
    private val radioButtons = mutableListOf<RadioButton>()
    private val density = context.resources.displayMetrics.density

    /** Built afresh each time the popup opens, so it shows the language as it is now. */
    fun createView(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        radioButtons.clear()
        val languages = languageOptions()
        addView(TextView(context).apply {
            text = chipText()
            textSize = 13f
            background = context.paperBackground()
            setPadding(dp(12), dp(6), dp(12), dp(6))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(12) }
            setOnClickListener { languages.visibility = if (languages.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
            chip = this
        })
        addView(languages)
    }

    /** Shows the language now in use and folds the languages away. */
    fun update() {
        chip?.text = chipText()
        options?.visibility = View.GONE
    }

    fun applyColors(colors: YomuColors) {
        chip?.setTextColor(colors.inkMuted)
        val text = ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(colors.inkMuted, colors.ink))
        radioButtons.forEach {
            it.setTextColor(text)
            it.buttonTintList = ColorStateList.valueOf(colors.accent)
        }
    }

    private fun chipText(): String = context.getString(R.string.overlay_language_chip, sourceName(), context.getString(targetLanguage().labelRes()))

    private fun languageOptions(): RadioGroup = RadioGroup(context).apply {
        visibility = View.GONE
        val current = targetLanguage()
        TargetLanguage.entries.forEach { language ->
            val reason = unavailable(language)?.resolve(context)
            addView(RadioButton(context).apply {
                text = listOfNotNull(context.getString(language.labelRes()), reason).joinToString(" · ")
                textSize = 14f
                minHeight = dp(48)
                isChecked = language == current
                isEnabled = reason == null
                setOnClickListener { onChosen(language) }
                radioButtons.add(this)
            })
        }
        options = this
    }

    private fun dp(value: Int): Int = (value * density).toInt()
}
