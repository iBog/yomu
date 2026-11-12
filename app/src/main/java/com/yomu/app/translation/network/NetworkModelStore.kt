package com.yomu.app.translation.network

import android.content.SharedPreferences

/** Which engine translates a page: the model on the phone or the one on a server. */
enum class TranslationBackend(val code: String) {
    DEVICE("device"),
    NETWORK("network");

    companion object {
        fun fromCode(code: String?): TranslationBackend = entries.firstOrNull { it.code == code } ?: DEVICE
    }
}

/**
 * The network model's settings. Everything private (the address, the API key, the model and how long it stays loaded)
 * lives in [secure], encrypted; only which engine translates stays in the plain [prefs], where it is no secret.
 */
class NetworkModelStore(private val prefs: SharedPreferences, private val secure: SharedPreferences) {

    fun config(): NetworkConfig =
        NetworkConfig(read(KEY_URL), read(KEY_API_KEY), read(KEY_MODEL), keepAliveMinutes())

    fun saveUrl(url: String) = secure.edit().putString(KEY_URL, url.trim()).apply()

    fun saveApiKey(key: String) = secure.edit().putString(KEY_API_KEY, key.trim()).apply()

    fun keepAliveMinutes(): Int =
        runCatching { secure.getInt(KEY_KEEP_ALIVE, DEFAULT_KEEP_ALIVE_MINUTES) }.getOrDefault(DEFAULT_KEEP_ALIVE_MINUTES).takeIf { it > 0 } ?: DEFAULT_KEEP_ALIVE_MINUTES

    fun saveKeepAliveMinutes(minutes: Int) = secure.edit().putInt(KEY_KEEP_ALIVE, minutes).apply()

    fun saveModel(model: String) = secure.edit().putString(KEY_MODEL, model.trim()).apply()

    fun backend(): TranslationBackend =
        TranslationBackend.fromCode(runCatching { prefs.getString(KEY_BACKEND, null) }.getOrNull())

    fun saveBackend(backend: TranslationBackend) = prefs.edit().putString(KEY_BACKEND, backend.code).apply()

    /** Whether pages go to the server: chosen, and with an address and a model to ask. */
    fun active(): Boolean = backend() == TranslationBackend.NETWORK && config().complete

    /** Hears a change to any of the private settings; the key it is told is the plain name. */
    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        secure.registerOnSharedPreferenceChangeListener(listener)

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        secure.unregisterOnSharedPreferenceChangeListener(listener)

    /**
     * Moves what earlier builds kept in plain files into [secure]: the address, model and keep-alive time from the main
     * preferences, and the API key from [legacySecrets]. A value already in [secure] wins, and nothing stays behind in plain text.
     */
    fun migrateLegacy(legacySecrets: SharedPreferences) {
        val moves = secure.edit()
        val cleanup = prefs.edit()
        runCatching { prefs.getString(KEY_URL, null) }.getOrNull()?.let { if (!secure.contains(KEY_URL)) moves.putString(KEY_URL, it) }
        runCatching { prefs.getString(KEY_MODEL, null) }.getOrNull()?.let { if (!secure.contains(KEY_MODEL)) moves.putString(KEY_MODEL, it) }
        if (prefs.contains(KEY_KEEP_ALIVE)) {
            runCatching { prefs.getInt(KEY_KEEP_ALIVE, 0) }.getOrNull()?.let { if (!secure.contains(KEY_KEEP_ALIVE)) moves.putInt(KEY_KEEP_ALIVE, it) }
        }
        runCatching { legacySecrets.getString(KEY_API_KEY, null) }.getOrNull()?.let { if (!secure.contains(KEY_API_KEY)) moves.putString(KEY_API_KEY, it) }
        moves.apply()
        cleanup.remove(KEY_URL).remove(KEY_MODEL).remove(KEY_KEEP_ALIVE).apply()
        legacySecrets.edit().clear().apply()
    }

    private fun read(key: String): String = runCatching { secure.getString(key, null) }.getOrNull().orEmpty()

    companion object {
        const val LEGACY_SECRETS_FILE = "yomu_network_secret"
        const val KEY_URL = "network_model_url"
        const val KEY_MODEL = "network_model_name"
        const val KEY_BACKEND = "translation_backend"
        const val KEY_KEEP_ALIVE = "network_keep_alive_minutes"
        const val KEY_API_KEY = "network_model_api_key"
    }
}
