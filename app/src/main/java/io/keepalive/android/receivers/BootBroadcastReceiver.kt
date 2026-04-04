package io.keepalive.android.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.edit
import io.keepalive.android.AlertNotificationHelper
import io.keepalive.android.AppController
import io.keepalive.android.AreYouThereOverlay
import io.keepalive.android.DebugLogger
import io.keepalive.android.R
import io.keepalive.android.doAlertCheck
import io.keepalive.android.getDeviceProtectedPreferences
import io.keepalive.android.getEncryptedSharedPreferences


class BootBroadcastReceiver : BroadcastReceiver() {

    private val tag = this.javaClass.name

    override fun onReceive(context: Context, intent: Intent) {
        try {

            // the intents we are looking for
            val bootActions = arrayOf(
                "android.intent.action.BOOT_COMPLETED",
                "android.intent.action.LOCKED_BOOT_COMPLETED"
            )
            Log.d(tag, "Intent action is ${intent.action}.")

            // if this is a boot intent
            if (intent.action in bootActions) {

                DebugLogger.d(tag, context.getString(R.string.debug_log_boot_completed))

                // getEncryptedSharedPreferences will automatically use device-protected
                //  storage during Direct Boot (LOCKED_BOOT_COMPLETED) since credential-
                //  encrypted storage is not available until the user unlocks the device
                val prefs = getEncryptedSharedPreferences(context)
                val enabled = prefs.getBoolean("enabled", false)

                if (enabled) {
                    Log.d(tag, "boot intent is {${intent.action}}")

                    // When BOOT_COMPLETED fires after Direct Boot, check if an "Are you there?"
                    // notification was posted during Direct Boot. If so, re-post it with a
                    // working PendingIntent (now that credential storage and MainActivity are
                    // accessible) and show the overlay.
                    //
                    // We MUST skip doAlertCheck() here because the saved alarm stage is "final"
                    // at this point (set when the notification was sent during Direct Boot).
                    // Calling doAlertCheck("final") would be wrong in either case:
                    //  - If the unlock IS detected as activity: it resets to periodic, cancelling
                    //    the final countdown and leaving the notification without a PendingIntent.
                    //  - If the unlock is NOT detected (race condition with UsageStatsManager,
                    //    or API < 28 with no monitored apps): it sees no activity + stage "final"
                    //    and immediately sends the alert before the user can acknowledge.
                    if (intent.action == "android.intent.action.BOOT_COMPLETED") {
                        // NOTE: must use getDeviceProtectedPreferences directly, not
                        // getEncryptedSharedPreferences, because after unlock the latter
                        // returns credential-encrypted prefs (a different backing store)
                        // while the flag was written to device-protected storage during
                        // Direct Boot.
                        val devicePrefs = getDeviceProtectedPreferences(context)

                        if (devicePrefs.getBoolean("direct_boot_notification_pending", false)) {
                            Log.d(tag, "BOOT_COMPLETED: Direct Boot notification pending, re-posting with PendingIntent")

                            // clear the flag
                            devicePrefs.edit { putBoolean("direct_boot_notification_pending", false) }

                            // re-post the notification - now isUserUnlocked() returns true so
                            // it will include the PendingIntent for tapping
                            val followupPeriodMinutes = prefs.getString(
                                "followup_time_period_minutes", "60"
                            )?.toIntOrNull() ?: 60

                            val alertNotificationHelper = AlertNotificationHelper(context)
                            alertNotificationHelper.sendNotification(
                                context.getString(R.string.initial_check_notification_title),
                                String.format(
                                    context.getString(R.string.initial_check_notification_text),
                                    followupPeriodMinutes.toString()
                                ),
                                AppController.ARE_YOU_THERE_NOTIFICATION_ID,
                                overwrite = true
                            )

                            // show the overlay now that the screen is accessible
                            AreYouThereOverlay.show(
                                context,
                                String.format(
                                    context.getString(R.string.initial_check_notification_text),
                                    followupPeriodMinutes.toString()
                                )
                            )

                            // don't call doAlertCheck() - the final alarm set during Direct
                            // Boot is still active and we want to preserve it
                            return
                        }
                    }

                    // restore the alarm stage that was saved before the reboot so we don't
                    // lose track of whether a final alarm was pending
                    val devicePrefs = getDeviceProtectedPreferences(context)
                    val savedAlarmStage = devicePrefs.getString("last_alarm_stage", "periodic") ?: "periodic"

                    Log.d(tag, "Restored alarm stage from device-protected storage: $savedAlarmStage")

                    // since we can't assume that the user initiated the reboot, run the alert
                    //  check using the saved alarm stage and last detected activity
                    doAlertCheck(context, savedAlarmStage)

                } else {
                    DebugLogger.d(tag, context.getString(R.string.debug_log_app_is_disabled))
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Exception in BootBroadcastReceiver: ${e.localizedMessage}", e)
            try {
                DebugLogger.d(tag, context.getString(R.string.debug_log_boot_receiver_exception, e.localizedMessage), e)
            } catch (logEx: Exception) {
                // DebugLogger may not be available during Direct Boot if file I/O fails
                Log.e(tag, "Failed to write to DebugLogger: ${logEx.localizedMessage}", logEx)
            }
        }
    }
}