package com.yomu.app.ui

import androidx.annotation.StringRes
import com.yomu.app.R
import com.yomu.core.SpokenLanguage
import com.yomu.core.TargetLanguage

/** How a language is named on screen. `promptName` is what the model is told and never changes. */
@StringRes
fun TargetLanguage.labelRes(): Int = when (this) {
    TargetLanguage.ENGLISH -> R.string.language_en
    TargetLanguage.BRAZILIAN_PORTUGUESE -> R.string.language_pt_br
    TargetLanguage.SPANISH -> R.string.language_es
    TargetLanguage.FRENCH -> R.string.language_fr
    TargetLanguage.GERMAN -> R.string.language_de
    TargetLanguage.RUSSIAN -> R.string.language_ru
}

@StringRes
fun SpokenLanguage.labelRes(): Int = when (this) {
    SpokenLanguage.JAPANESE -> R.string.language_ja
    SpokenLanguage.CHINESE -> R.string.language_zh
    SpokenLanguage.KOREAN -> R.string.language_ko
    SpokenLanguage.ENGLISH -> R.string.language_en
    SpokenLanguage.SPANISH -> R.string.language_es
    SpokenLanguage.FRENCH -> R.string.language_fr
    SpokenLanguage.GERMAN -> R.string.language_de
    SpokenLanguage.ITALIAN -> R.string.language_it
    SpokenLanguage.PORTUGUESE -> R.string.language_pt
    SpokenLanguage.RUSSIAN -> R.string.language_ru
    SpokenLanguage.UKRAINIAN -> R.string.language_uk
}
