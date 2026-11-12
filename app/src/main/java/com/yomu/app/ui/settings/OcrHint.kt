package com.yomu.app.ui.settings

import com.yomu.app.R
import com.yomu.app.service.ReadingModelSelection
import com.yomu.app.service.SlotDeliverable
import com.yomu.app.ui.UiText
import com.yomu.app.ui.labelRes
import com.yomu.core.SpokenLanguage

/**
 * What the source language setting tells the reader about the OCR model: Auto keeps the model chosen in Pipeline, a
 * language switches to the one that reads its script, a switch waits for the download, and a model chosen in Pipeline
 * that doesn't read the language leaves the source treated as Auto.
 */
internal fun ocrHint(spoken: SpokenLanguage?, selected: SlotDeliverable?, pending: SlotDeliverable?): UiText = when {
    pending != null -> UiText(R.string.settings_source_ocr_pending, pending.name, selected?.name ?: UiText(R.string.service_no_model_selected))
    selected == null -> UiText(R.string.settings_source_ocr_none)
    spoken == null -> UiText(R.string.settings_source_ocr_auto, selected.name)
    ReadingModelSelection.sourceOf(selected.id) != spoken.script -> UiText(R.string.settings_source_ocr_mismatch, selected.name, UiText(spoken.labelRes()))
    else -> UiText(R.string.settings_source_ocr_language, UiText(spoken.labelRes()), selected.name)
}
