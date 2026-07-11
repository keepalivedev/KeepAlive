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
        val sharedPrefs = getAppSharedPreferences(context)

        // Cancel the on-screen notification.
        AlertNotificationHelper(context).cancelNotification(AppController.ARE_YOU_THERE_NOTIFICATION_ID)

        // If we showed the over-other-apps overlay, remove it.
        AreYouThereOverlay.dismiss(context)

        // If the full-screen prompt activity is showing (launched over the lock
        //  screen via the notification's full-screen intent), close it too.
        AreYouThereActivity.finishActive()

        // Clear the Direct Boot notification flag in case it's still set, and
        // record the acknowledgement as activity in device-protected storage.
        // The timestamp lets the Direct Boot final-alarm branch of
        // doAlertCheck() detect a racing acknowledgement and skip the alert
        // (UsageStatsManager is not queryable before unlock, so that path has
        // no other signal for user activity).
        // NOTE: must use getDeviceProtectedPreferences directly, not
        // getAppSharedPreferences, because after unlock the latter returns
        // credential-encrypted prefs (a different backing store) while the flag
        // was written to device-protected storage during Direct Boot.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                getDeviceProtectedPreferences(context).edit(commit = true) {
                    putBoolean(PrefKeys.DIRECT_BOOT_NOTIFICATION_PENDING, false)
                    putLong(PrefKeys.LAST_ACTIVITY_TIMESTAMP, System.currentTimeMillis())
                }
            } catch (e: Exception) {
                Log.e("AcknowledgeAreYouThere", "Error updating Direct Boot state on acknowledge", e)
            }
        }

        // Re-set periodic monitoring.
        val checkPeriodHours = sharedPrefs.getString(PrefKeys.TIME_PERIOD_HOURS, "12")?.toFloatOrNull() ?: 12f
        val restPeriods: MutableList<RestPeriod> = loadJSONSharedPreference(sharedPrefs, PrefKeys.REST_PERIODS)

        setAlarm(
            context,
            System.currentTimeMillis(),
            (checkPeriodHours * 60).toInt(),
            "periodic",
            restPeriods
        )
    }
}
