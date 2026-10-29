package com.yomu.app.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.yomu.core.Constants

/** What the shade offers while reading; each is an intent action [OverlayService] handles. */
enum class ShadeAction(val label: String, val intentAction: String) {
    TRANSLATE("Translate", "com.yomu.app.action.TRANSLATE"),
    CANCEL("Cancel", "com.yomu.app.action.CANCEL"),
    STOP("Stop reading", "com.yomu.app.action.STOP");

    companion object {
        fun of(intentAction: String?): ShadeAction? = entries.firstOrNull { it.intentAction == intentAction }
    }
}

/** The reading notification's text, what a tap on it does, and its buttons. */
data class ReadingNotificationContent(val text: String, val tap: ShadeAction?, val actions: List<ShadeAction>)

fun readingNotificationContent(mode: TriggerMode, translating: Boolean): ReadingNotificationContent = when {
    mode == TriggerMode.FLOATING_BUTTON ->
        ReadingNotificationContent("Tap the floating button to translate manga", tap = null, actions = emptyList())
    translating ->
        ReadingNotificationContent("Translating the page. Tap to cancel", ShadeAction.CANCEL, listOf(ShadeAction.CANCEL, ShadeAction.STOP))
    else ->
        ReadingNotificationContent("Tap to translate the page on screen", ShadeAction.TRANSLATE, listOf(ShadeAction.TRANSLATE, ShadeAction.STOP))
}

/** Builds the foreground notification that, in [TriggerMode.NOTIFICATION], is the reader's only control. */
class ReadingNotification(private val context: Context) {

    fun build(content: ReadingNotificationContent): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, Constants.OVERLAY_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        builder
            .setContentTitle("Yomu")
            .setContentText(content.text)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        content.tap?.let { builder.setContentIntent(pendingIntent(it)) }
        content.actions.forEach { action ->
            @Suppress("DEPRECATION")
            builder.addAction(Notification.Action.Builder(0, action.label, pendingIntent(action)).build())
        }
        return builder.build()
    }

    /**
     * A translation goes through [ShadeTriggerActivity]: only an activity start collapses the shade on
     * Android 12+, and the capture must not see it. Cancel and stop capture nothing, so they reach the service directly.
     */
    private fun pendingIntent(action: ShadeAction): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (action == ShadeAction.TRANSLATE) {
            val intent = Intent(context, ShadeTriggerActivity::class.java)
                .setAction(action.intentAction)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            PendingIntent.getActivity(context, action.ordinal, intent, flags)
        } else {
            val intent = Intent(context, OverlayService::class.java).setAction(action.intentAction)
            PendingIntent.getService(context, action.ordinal, intent, flags)
        }
    }
}
