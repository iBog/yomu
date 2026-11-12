package com.yomu.app.translation

import android.content.SharedPreferences
import com.yomu.app.translation.network.NetworkModelStore
import com.yomu.core.Constants
import com.yomu.core.GenerationBound
import com.yomu.core.GenerationParams
import com.yomu.core.TranslationSlot
import com.yomu.ml.LlamaTranslationBridge
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TranslationModelSelection @Inject constructor(
    private val llamaSlot: LlamaTranslationBridge,
    private val sharedPreferences: SharedPreferences,
    private val llmModelsDir: File = File(""),
    private val totalMemBytes: Long = 0L,
    private val networkSlot: TranslationSlot? = null,
    private val networkStore: NetworkModelStore? = null
) {
    private val generationStore = GenerationProfileStore(sharedPreferences)
    private val limitsStore = ResourceLimitsStore(sharedPreferences)

    /** The server's slot while the network model is chosen and set up, otherwise the model on the phone. */
    fun current(): TranslationSlot = if (networkActive()) networkSlot ?: llamaSlot else llamaSlot

    fun networkActive(): Boolean = networkSlot != null && networkStore?.active() == true

    /** The model on the server while the network model is in use, or null. */
    fun networkModelName(): String? = networkStore?.config()?.model?.takeIf { networkActive() }

    /** Names the model pages are translated with, for the recall key: it changes when the server, the model or the backend does. */
    fun activeModelKey(): String? = if (networkActive()) networkStore?.config()?.let { "network:${it.url}|${it.model}" }
    else currentLlmModel().id.takeUnless { llmModelCleared() }

    fun currentLlmModel(): LlmModelOption = LlmModelCatalog.selectedOrDefault(sharedPreferences.storedLlmModelId())

    /** True when a model id is stored but unreadable or no longer in the catalog, so [currentLlmModel] fell back. */
    fun storedLlmModelRecovered(): Boolean =
        sharedPreferences.contains(Constants.PREF_LLM_MODEL) &&
            !llmModelCleared() &&
            LlmModelCatalog.fromId(sharedPreferences.storedLlmModelId()) == null

    /** An empty stored id marks a slot whose model was deleted; picking a model stores its id over it. */
    fun llmModelCleared(): Boolean = sharedPreferences.storedLlmModelId() == ""

    fun clearLlmModel() {
        sharedPreferences.edit().putString(Constants.PREF_LLM_MODEL, "").apply()
    }

    /** Drop the stored values that loaded as defaults, so their warning is shown once, not every visit. */
    fun clearRecovered() {
        if (storedLlmModelRecovered()) sharedPreferences.edit().remove(Constants.PREF_LLM_MODEL).apply()
        generationStore.forget(generationProfile().recovered)
    }

    suspend fun selectLlmModel(option: LlmModelOption) {
        sharedPreferences.edit().putString(Constants.PREF_LLM_MODEL, option.id).apply()
        applyLlmProfile(option)
    }

    /** The effective profile: the selected deliverable's maker sampling under the reader's saved fields. */
    fun generationProfile(): GenerationProfileStore.Loaded = generationStore.load(currentLlmModel().generationDefaults)

    /** What "Reset to defaults" returns to — the selected deliverable's maker sampling (ADR-0017). */
    fun generationDefaults(): GenerationParams = currentLlmModel().generationDefaults

    /**
     * Persist one reader-facing sampler value and hand the new profile to the slot, so the next
     * capture uses it without restarting the overlay (#192). Refused values change nothing.
     */
    suspend fun saveGeneration(bound: GenerationBound, value: Float): Boolean {
        if (!generationStore.save(bound, value)) return false
        applyLlmProfile(currentLlmModel())
        return true
    }

    suspend fun resetGeneration() {
        generationStore.reset()
        applyLlmProfile(currentLlmModel())
    }

    fun resourceLimit(limit: ResourceLimit): Int = limitsStore.load(limit)

    /** Persist one resource cap; threads and context reach the slot, which reloads the model on its next page (#79). */
    suspend fun saveResourceLimit(limit: ResourceLimit, value: Int): Boolean {
        if (!limitsStore.save(limit, value)) return false
        applyLlmProfile(currentLlmModel())
        return true
    }

    suspend fun resetResourceLimits() {
        limitsStore.reset()
        applyLlmProfile(currentLlmModel())
    }

    fun lastPageDurationMs(): Long? = llamaSlot.lastPageDurationMs

    private suspend fun applyLlmProfile(option: LlmModelOption) {
        llamaSlot.selectModel(
            LlmModelCatalog.profileFor(
                option, llmModelsDir, generationStore.load(option.generationDefaults).params, limitsStore.runtime(), limitsStore.fitBudget(totalMemBytes)
            )
        )
    }

    fun close() {
        llamaSlot.close()
        networkSlot?.close()
    }
}
