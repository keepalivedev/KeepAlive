package io.keepalive.android

import android.content.Context

/**
 * Shared acknowledgement behavior for the "Are you still there?" prompt.
 */
object AcknowledgeAreYouThere {

    fun acknowledge(context: Context) {
        val sharedPrefs = getEncryptedSharedPreferences(context)

        // Cancel the on-screen notification.
        AlertNotificationHelper(context).cancelNotification(AppController.ARE_YOU_THERE_NOTIFICATION_ID)

        // If we showed the over-other-apps overlay, remove it.
        AreYouThereOverlay.dismiss(context)

        // Re-set periodic monitoring.
        val checkPeriodHours = sharedPrefs.getString("time_period_hours", "12")?.toFloatOrNull() ?: 12f
        val restPeriods: MutableList<RestPeriod> = loadJSONSharedPreference(sharedPrefs, "REST_PERIODS")

        setAlarm(
            context,
            System.currentTimeMillis(),
            (checkPeriodHours * 60).toInt(),
            "periodic",
            restPeriods
        )
    }
}
