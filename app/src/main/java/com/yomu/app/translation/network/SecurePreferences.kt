package com.yomu.app.translation.network

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Preferences whose keys and values are encrypted with a key in the Android Keystore, for what the reader would not
 * want read off the phone: the server's address, its API key and the model asked for.
 */
object SecurePreferences {
    const val FILE = "yomu_secure_prefs"

    /** Used only when the Keystore cannot be used at all. Not encrypted, and kept out of backups like [FILE]. */
    const val FALLBACK_FILE = "yomu_network_unsecured"

    private const val TAG = "SecurePreferences"

    fun open(context: Context): SharedPreferences = try {
        create(context)
    } catch (e: Exception) {
        // The file and the Keystore key are out of step (a restore, a cleared key): the secrets are lost, start them afresh.
        Log.w(TAG, "Secure preferences unreadable, starting them afresh: ${e.message}")
        context.deleteSharedPreferences(FILE)
        try {
            create(context)
        } catch (e2: Exception) {
            Log.e(TAG, "Keystore unavailable, network settings are kept unencrypted: ${e2.message}")
            context.getSharedPreferences(FALLBACK_FILE, Context.MODE_PRIVATE)
        }
    }

    private fun create(context: Context): SharedPreferences = EncryptedSharedPreferences.create(
        context,
        FILE,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
}
