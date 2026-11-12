package com.yomu.app.ui.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yomu.app.R
import com.yomu.app.service.OverlayService
import com.yomu.app.ui.UiText

/** Turns the overlay service's start, stop and failed-start broadcasts into Home state changes. */
internal class ServiceStateReceiver(private val update: ((HomeUiState) -> HomeUiState) -> Unit) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            OverlayService.ACTION_SERVICE_STARTED -> update {
                it.copy(isServiceRunning = true, serviceError = false, confirmation = UiText(R.string.home_reading_is_on))
            }
            OverlayService.ACTION_SERVICE_STOPPED -> update { it.copy(isServiceRunning = false) }
            OverlayService.ACTION_SERVICE_START_FAILED -> update { it.copy(isServiceRunning = false, serviceError = true) }
        }
    }
}
