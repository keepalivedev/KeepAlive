package io.keepalive.android.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.keepalive.android.AcknowledgeAreYouThere
import io.keepalive.android.DebugLogger
import io.keepalive.android.R
import io.keepalive.android.doAlertCheck
import io.keepalive.android.getDeviceProtectedPreferences
import io.keepalive.android.getEncryptedSharedPreferences
import io.keepalive.android.isUserUnlocked


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

            // note that logcat may not show all logs from this receiver during Direct Boot
            //  but you should be able to view the DebugLogger logs in the app

            // if this is a boot intent
            if (intent.action in bootActions) {

                DebugLogger.d(tag, context.getString(R.string.debug_log_boot_completed,
                    intent.action ?: "unknown"))

                // getEncryptedSharedPreferences will automatically use device-protected
                //  storage during Direct Boot (LOCKED_BOOT_COMPLETED) since credential-
                //  encrypted storage is not available until the user unlocks the device
                val prefs = getEncryptedSharedPreferences(context)
                val enabled = prefs.getBoolean("enabled", false)

                if (enabled) {
                    Log.d(tag, "boot intent is ${intent.action}")

                    // When BOOT_COMPLETED fires after Direct Boot, check if an "Are you there?"
                    // notification was posted during Direct Boot. If the flag is set, the user
                    // just unlocked the device — which IS proof of activity — so we simply
                    // cancel the notification and reset to a fresh periodic alarm cycle.
                    //
                    // We MUST skip doAlertCheck() when the flag is set because the saved alarm
                    // stage is "final" at this point. Calling doAlertCheck("final") would be
                    // wrong in either case:
                    //  - If the unlock IS detected as activity: it resets to periodic, but the
                    //    stale notification remains.
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
                        val flagValue = devicePrefs.getBoolean("direct_boot_notification_pending", false)
                        val userUnlocked = isUserUnlocked(context)

                        DebugLogger.d(tag, context.getString(R.string.debug_log_boot_completed_flag_status, flagValue, userUnlocked))

                        if (flagValue) {
                            // The user just unlocked the device — that IS proof of activity.
                            // Cancel the "Are you there?" notification and reset to a fresh
                            // periodic alarm cycle. No need to re-post or show the overlay.
                            DebugLogger.d(tag, context.getString(R.string.debug_log_boot_completed_user_unlocked))

                            AcknowledgeAreYouThere.acknowledge(context)

                            // acknowledge() already clears the flag, cancels the notification,
                            // dismisses any overlay, and sets a fresh periodic alarm.
                            return
                        } else {
                            DebugLogger.d(tag, context.getString(R.string.debug_log_boot_completed_no_pending_notification))
                        }
                    } else {
                        DebugLogger.d(tag, context.getString(R.string.debug_log_locked_boot_completed_skipping, intent.action))
                    }

                    // restore the alarm stage that was saved before the reboot so we don't
                    // lose track of whether a final alarm was pending
                    val devicePrefs = getDeviceProtectedPreferences(context)
                    val savedAlarmStage = devicePrefs.getString("last_alarm_stage", "periodic") ?: "periodic"

                    DebugLogger.d(tag, context.getString(R.string.debug_log_restored_alarm_stage, savedAlarmStage))

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