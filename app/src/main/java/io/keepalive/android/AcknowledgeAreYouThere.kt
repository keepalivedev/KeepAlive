package io.keepalive.android

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.content.edit

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

        // Clear the Direct Boot notification flag in case it's still set.
        // NOTE: must use getDeviceProtectedPreferences directly, not
        // getEncryptedSharedPreferences, because after unlock the latter returns
        // credential-encrypted prefs (a different backing store) while the flag
        // was written to device-protected storage during Direct Boot.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                getDeviceProtectedPreferences(context).edit(commit = true) {
                    putBoolean("direct_boot_notification_pending", false)
                }
            } catch (e: Exception) {
                Log.e("AcknowledgeAreYouThere", "Error clearing Direct Boot notification flag", e)
            }
        }

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
