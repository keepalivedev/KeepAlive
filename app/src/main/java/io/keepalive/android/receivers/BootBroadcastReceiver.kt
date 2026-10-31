package io.keepalive.android.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.keepalive.android.AcknowledgeAreYouThere
import io.keepalive.android.AlarmRecovery
import io.keepalive.android.DebugLogger
import io.keepalive.android.PrefKeys
import io.keepalive.android.R
import io.keepalive.android.getDeviceProtectedPreferences
import io.keepalive.android.getAppSharedPreferences
import io.keepalive.android.isUserUnlocked


class BootBroadcastReceiver : BroadcastReceiver() {

    private val tag = this.javaClass.name

    override fun onReceive(context: Context, intent: Intent) {
        try {

            // the intents we are looking for
            val bootActions = arrayOf(
                "android.intent.action.BOOT_COMPLETED",
                "android.intent.action.LOCKED_BOOT_COMPLETED",

                // replacing the package cancels every alarm the app has registered,
                //  so an update has to re-arm monitoring exactly like a reboot does
                "android.intent.action.MY_PACKAGE_REPLACED"
            )
            Log.d(tag, "Intent action is ${intent.action}.")

            // note that logcat may not show all logs from this receiver during Direct Boot
            //  but you should be able to view the DebugLogger logs in the app

            // if this is a boot intent
            if (intent.action in bootActions) {

                DebugLogger.d(tag, context.getString(R.string.debug_log_boot_completed,
                    intent.action ?: "unknown"))

                // getAppSharedPreferences will automatically use device-protected
                //  storage during Direct Boot (LOCKED_BOOT_COMPLETED) since credential-
                //  encrypted storage is not available until the user unlocks the device
                val prefs = getAppSharedPreferences(context)
                val enabled = prefs.getBoolean(PrefKeys.ENABLED, false)

                // held across the acknowledge branch too, so a concurrent watchdog
                //  can't re-arm a final between the pending-flag read and acknowledge()
                if (enabled) synchronized(AlarmRecovery.stateLock) {
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
                    // MY_PACKAGE_REPLACED deliberately takes neither branch. It must not
                    // acknowledge: the unlock is what makes BOOT_COMPLETED proof of
                    // activity, and an unattended Play Store update is proof of nothing.
                    // It falls straight through to AlarmRecovery.
                    if (intent.action == "android.intent.action.BOOT_COMPLETED") {
                        // NOTE: must use getDeviceProtectedPreferences directly, not
                        // getAppSharedPreferences, because after unlock the latter
                        // returns credential-encrypted prefs (a different backing store)
                        // while the flag was written to device-protected storage during
                        // Direct Boot.
                        val devicePrefs = getDeviceProtectedPreferences(context)
                        val flagValue = devicePrefs.getBoolean(PrefKeys.DIRECT_BOOT_NOTIFICATION_PENDING, false)
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
                    } else if (intent.action == "android.intent.action.LOCKED_BOOT_COMPLETED") {
                        DebugLogger.d(tag, context.getString(R.string.debug_log_locked_boot_completed_skipping, intent.action))

                        // If the user is already unlocked, BOOT_COMPLETED will also fire
                        // and handle the alert check. Skip here to avoid calling doAlertCheck()
                        // twice — which happens during app redeploy/update where both intents
                        // fire while the device is already unlocked.
                        if (isUserUnlocked(context)) {
                            DebugLogger.d(tag, context.getString(R.string.debug_log_locked_boot_user_already_unlocked))
                            return
                        }
                    }

                    // the rest lives in AlarmRecovery so the watchdog makes the same
                    //  decisions
                    val source = if (intent.action == "android.intent.action.MY_PACKAGE_REPLACED") {
                        AlarmRecovery.Source.PACKAGE_REPLACED
                    } else {
                        AlarmRecovery.Source.BOOT
                    }
                    AlarmRecovery.restore(context, source)

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