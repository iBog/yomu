package com.yomu.app

import android.app.Application
import android.content.Context
import dagger.hilt.android.HiltAndroidApp
import java.security.KeyStore

@HiltAndroidApp
class YomuApplication : Application() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(base.withAppLanguage())

    override fun onCreate() {
        super.onCreate()
        clearRetiredHfToken()
    }

    // #187: the HuggingFace sign-in is gone; drop any token an earlier build persisted. Both calls
    // are no-ops once nothing is left.
    private fun clearRetiredHfToken() {
        deleteSharedPreferences("hf_auth")
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("yomu_hf_token_v1") }
    }
}
