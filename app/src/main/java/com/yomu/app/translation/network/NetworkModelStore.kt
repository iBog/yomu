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
 * The network model's settings. The API key lives in [secrets], a file of its own that backups leave out, so it
 * does not travel with the rest of the preferences.
 */
class NetworkModelStore(private val prefs: SharedPreferences, private val secrets: SharedPreferences) {

    fun config(): NetworkConfig =
        NetworkConfig(read(prefs, KEY_URL), read(secrets, KEY_API_KEY), read(prefs, KEY_MODEL), keepAliveMinutes())

    fun saveUrl(url: String) = prefs.edit().putString(KEY_URL, url.trim()).apply()

    fun saveApiKey(key: String) = secrets.edit().putString(KEY_API_KEY, key.trim()).apply()

    fun keepAliveMinutes(): Int =
        runCatching { prefs.getInt(KEY_KEEP_ALIVE, DEFAULT_KEEP_ALIVE_MINUTES) }.getOrDefault(DEFAULT_KEEP_ALIVE_MINUTES).takeIf { it > 0 } ?: DEFAULT_KEEP_ALIVE_MINUTES

    fun saveKeepAliveMinutes(minutes: Int) = prefs.edit().putInt(KEY_KEEP_ALIVE, minutes).apply()

    fun saveModel(model: String) = prefs.edit().putString(KEY_MODEL, model.trim()).apply()

    fun backend(): TranslationBackend =
        TranslationBackend.fromCode(runCatching { prefs.getString(KEY_BACKEND, null) }.getOrNull())

    fun saveBackend(backend: TranslationBackend) = prefs.edit().putString(KEY_BACKEND, backend.code).apply()

    /** Whether pages go to the server: chosen, and with an address and a model to ask. */
    fun active(): Boolean = backend() == TranslationBackend.NETWORK && config().complete

    private fun read(from: SharedPreferences, key: String): String =
        runCatching { from.getString(key, null) }.getOrNull().orEmpty()

    companion object {
        const val SECRETS_FILE = "yomu_network_secret"
        const val KEY_URL = "network_model_url"
        const val KEY_MODEL = "network_model_name"
        const val KEY_BACKEND = "translation_backend"
        const val KEY_KEEP_ALIVE = "network_keep_alive_minutes"
        const val KEY_API_KEY = "network_model_api_key"
    }
}
