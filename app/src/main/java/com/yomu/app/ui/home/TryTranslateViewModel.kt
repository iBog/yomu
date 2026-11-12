package com.yomu.app.ui.home

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yomu.app.R
import com.yomu.app.translation.NameGlossaryStore
import com.yomu.app.ui.UiText
import com.yomu.app.ui.toUiText
import com.yomu.core.SpokenLanguage
import com.yomu.core.TargetLanguage
import com.yomu.pipeline.TranslationPipeline
import com.yomu.pipeline.translation.TranslationResult
import com.yomu.pipeline.translation.readerFailure
import com.yomu.pipeline.translation.untranslatedNotice
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the translation test box shows: [text] is the translation, [error] why there is none; [note] qualifies a partial one. */
data class TryState(
    val running: Boolean = false,
    val text: String? = null,
    val error: UiText? = null,
    val note: UiText? = null,
    val millis: Long = 0L
) {
    val failed: Boolean get() = error != null
}

internal fun TranslationResult.toTryState(): TryState {
    readerFailure()?.let { return TryState(error = it.toUiText()) }
    if (translations.none { it.answered }) return TryState(error = UiText(R.string.try_no_usable_translation), millis = translationTimeMs)
    return TryState(text = translations.joinToString("\n") { it.translatedText }, note = untranslatedNotice()?.toUiText(), millis = translationTimeMs)
}

/** Runs pasted text through the model and languages Home has selected, to judge a translation without capturing a page. */
@HiltViewModel
class TryTranslateViewModel @Inject constructor(
    private val pipeline: TranslationPipeline,
    private val sharedPreferences: SharedPreferences
) : ViewModel() {
    private val _state = MutableStateFlow(TryState())
    val state = _state.asStateFlow()
    private var job: Job? = null

    fun translate(text: String, spoken: SpokenLanguage?, target: TargetLanguage) {
        job?.cancel()
        _state.value = TryState(running = true)
        job = viewModelScope.launch {
            _state.value = try {
                pipeline.translateLines(text.lines(), target, spoken, NameGlossaryStore(sharedPreferences).load()).toTryState()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                TryState(error = UiText(R.string.try_translation_failed, e.message ?: e::class.simpleName.orEmpty()))
            }
        }
    }
}
