package com.yomu.app

import android.content.SharedPreferences
import javax.inject.Inject
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import com.yomu.core.Constants
import com.yomu.core.TargetLanguage
import com.yomu.app.ui.theme.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.yomu.app.service.OverlayService
import com.yomu.app.ui.navigation.AppNavigation
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var sharedPreferences: SharedPreferences

    override fun attachBaseContext(base: Context) = super.attachBaseContext(base.withAppLanguage())

    /** A language the overlay's switch asked for, waiting for Home to offer the model it needs (#338). */
    private var languageRequest by mutableStateOf<TargetLanguage?>(null)

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            startOverlayService(data, result.resultCode)
        }
    }

    private fun startOverlayService(data: Intent, resultCode: Int) {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                getString(R.string.main_allow_overlay),
                Toast.LENGTH_SHORT
            ).show()
            val overlayIntent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(overlayIntent)
            return
        }

        val intent = Intent(this, OverlayService::class.java).apply {
            putExtra(OverlayService.EXTRA_MEDIA_PROJECTION_DATA, data)
            putExtra(OverlayService.EXTRA_RESULT_CODE, resultCode)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    fun launchScreenCaptureConsent() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                getString(R.string.main_allow_overlay),
                Toast.LENGTH_SHORT
            ).show()
            val overlayIntent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(overlayIntent)
            return
        }

        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(manager.createScreenCaptureIntent())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) languageRequest = intent.languageRequest()

        setContent {
            var theme by remember { mutableStateOf(sharedPreferences.getString(Constants.PREF_THEME, "system") ?: "system") }
            DisposableEffect(sharedPreferences) {
                val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
                    if (key == Constants.PREF_THEME) theme = prefs.getString(key, "system") ?: "system"
                }
                sharedPreferences.registerOnSharedPreferenceChangeListener(listener)
                onDispose { sharedPreferences.unregisterOnSharedPreferenceChangeListener(listener) }
            }
            val mode = resolveThemeMode(theme, isSystemInDarkTheme())
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = mode == ThemeMode.Day
                    isAppearanceLightNavigationBars = mode == ThemeMode.Day
                }
            }
            YomuTheme(colors = if (mode == ThemeMode.Night) NightPaper else DayPaper) {
                AppNavigation(
                    onRequestScreenCapture = { launchScreenCaptureConsent() },
                    languageRequest = languageRequest,
                    onLanguageRequestHandled = { languageRequest = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        languageRequest = intent.languageRequest()
    }

    private fun Intent.languageRequest(): TargetLanguage? = TargetLanguage.fromCode(getStringExtra(EXTRA_TARGET_LANGUAGE))

    companion object {
        const val EXTRA_TARGET_LANGUAGE = "target_language"
    }
}
