package com.yomu.app.ui

import com.yomu.app.R
import com.yomu.pipeline.translation.ReaderFailure
import com.yomu.pipeline.translation.UntranslatedNotice

/** What the reader is told when a page came back untranslated, worded in the app language. */
fun ReaderFailure.toUiText(): UiText = when (this) {
    ReaderFailure.EnglishOnly -> UiText(R.string.reader_failure_english_only)
    ReaderFailure.ModelMissing -> UiText(R.string.reader_failure_model_missing)
    is ReaderFailure.NotLoaded -> UiText(R.string.reader_failure_not_loaded, code)
}

fun UntranslatedNotice.toUiText(): UiText = UiText(R.string.reader_untranslated_notice, missing, total)
