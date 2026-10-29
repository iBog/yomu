package com.yomu.app.service

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * An invisible hop from the notification to [OverlayService]. Starting it collapses the shade, which a
 * service or broadcast intent cannot do on Android 12+; it finishes at once, leaving the reader's page on screen.
 */
class ShadeTriggerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.action?.let { action -> startService(Intent(this, OverlayService::class.java).setAction(action)) }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
