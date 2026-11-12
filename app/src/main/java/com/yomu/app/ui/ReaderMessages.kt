package com.yomu.app.ui

import com.yomu.app.R
import com.yomu.pipeline.translation.ReaderFailure
import com.yomu.pipeline.translation.UntranslatedNotice

/** What the reader is told when a page came back untranslated, worded in the app language. */
fun ReaderFailure.toUiText(): UiText = when (this) {
    ReaderFailure.EnglishOnly -> UiText(R.string.reader_failure_english_only)
    ReaderFailure.ModelMissing -> UiText(R.string.reader_failure_model_missing)
    is ReaderFailure.NotLoaded -> UiText(R.string.reader_failure_not_loaded, code)
    is ReaderFailure.NetworkFailed -> networkFailureText(code)
}

fun UntranslatedNotice.toUiText(): UiText = UiText(R.string.reader_untranslated_notice, missing, total)

/** What a failed network model request tells the reader; [code] is a `network_` code from the client. */
private fun networkFailureText(code: String): UiText = when {
    code == "network_unauthorized" -> UiText(R.string.reader_failure_network_unauthorized)
    code == "network_timeout" -> UiText(R.string.reader_failure_network_timeout)
    code == "network_unreachable" || code == "network_bad_url" -> UiText(R.string.reader_failure_network_unreachable)
    else -> UiText(R.string.reader_failure_network_other, code)
}
