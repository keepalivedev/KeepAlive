package io.keepalive.android.receivers


import android.Manifest
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import io.keepalive.android.*


class AlarmReceiver : BroadcastReceiver() {

    private val tag = this.javaClass.name

    override fun onReceive(context: Context, intent: Intent) {
        try {
            Log.d(tag, "AlarmReceiver.onReceive() called")
            DebugLogger.d(tag, context.getString(R.string.debug_log_alarm_just_fired))

            // for extra debugging, check to see if the app is in the foreground or background
            val appForegroundChecker = AppForegroundChecker(context)

            if (appForegroundChecker.isAppInForeground()) {
                DebugLogger.d(tag, context.getString(R.string.debug_log_app_in_foreground))
            } else {
                DebugLogger.d(tag, context.getString(R.string.debug_log_app_in_background))
            }

            // https://developer.android.com/reference/android/content/Intent#FLAG_RECEIVER_FOREGROUND
            if (intent.flags and Intent.FLAG_RECEIVER_FOREGROUND != 0) {
                DebugLogger.d(tag, context.getString(R.string.debug_log_flag_receiver_true))
            } else {
                DebugLogger.d(tag, context.getString(R.string.debug_log_flag_receiver_false))
            }

            // shouldn't need to check whether the app is still enabled here because
            //  we will cancel the alarm when the setting is changed
            // but just in case...
            val prefs = getAppSharedPreferences(context)
            val appEnabled = prefs.getBoolean(PrefKeys.ENABLED, false)

            if (!appEnabled) {
                DebugLogger.d(tag, context.getString(R.string.debug_log_app_not_enabled_alarm_went_off))
                return
            }

            // surface a revoked SEND_SMS permission from the background (issue #192).
            // the alert check still runs regardless — the send path has its own
            // failure notification if an SMS attempt actually fails
            checkSmsPermissionStillGranted(context, prefs)

            // get the current alarm stage from the intent extras
            val alarmStage = getAlarmStage(context, intent)

            // take action depending on what alarm stage it is
            doAlertCheck(context, alarmStage)

            Log.d(tag, "AlarmReceiver.onReceive() finished")

        } catch (e: Exception) {
            DebugLogger.d(tag, context.getString(R.string.debug_log_failed_processing_alarm), e)
        }
    }

    // A permission revoked after setup (battery sweep, OS update, someone
    // adjusting settings) was previously only detected when the app was next
    // opened. Since this receiver already wakes periodically, use it to check
    // that SEND_SMS is still granted and notify the user if not (issue #192).
    private fun checkSmsPermissionStillGranted(context: Context, prefs: SharedPreferences) {
        try {
            // only relevant if an enabled SMS contact is configured
            val smsContacts: MutableList<SMSEmergencyContactSetting> =
                loadJSONSharedPreference(prefs, PrefKeys.PHONE_NUMBER_SETTINGS)
            if (smsContacts.none { it.isEnabled && it.phoneNumber.isNotEmpty() }) {
                return
            }

            if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                // permission is fine - clear the notified marker so a future
                //  revocation notifies immediately
                if (prefs.getLong(PrefKeys.SMS_PERMISSION_NOTIFIED_AT, 0L) != 0L) {
                    prefs.edit { putLong(PrefKeys.SMS_PERMISSION_NOTIFIED_AT, 0L) }
                }
                return
            }

            DebugLogger.d(tag, context.getString(R.string.debug_log_sms_permission_revoked))

            // notify at most once per day while the permission stays revoked
            val lastNotifiedAt = prefs.getLong(PrefKeys.SMS_PERMISSION_NOTIFIED_AT, 0L)
            if (System.currentTimeMillis() - lastNotifiedAt < 24 * 60 * 60 * 1000L) {
                return
            }

            // tapping the notification opens MainActivity, which runs the normal
            //  permission checks and prompts
            AlertNotificationHelper(context).sendNotification(
                context.getString(R.string.sms_permission_revoked_notification_title),
                context.getString(R.string.sms_permission_revoked_notification_text),
                AppController.PERMISSION_REVOKED_NOTIFICATION_ID
            )

            prefs.edit { putLong(PrefKeys.SMS_PERMISSION_NOTIFIED_AT, System.currentTimeMillis()) }
        } catch (e: Exception) {
            // never let the permission check interfere with the alert check
            Log.e(tag, "Error checking SMS permission from background", e)
        }
    }

    private fun getAlarmStage(context: Context, intent: Intent): String {
        val extras = intent.extras ?: return "periodic"
        val declaredStage = extras.getString("AlarmStage", "periodic")
        val alarmTimestamp = extras.getLong("AlarmTimestamp", 0)

        if (alarmTimestamp == 0L) return declaredStage

        // this is just for informational purposes so we can see how well Android respects
        //  the alarm time we set...
        val alarmDtStr = getDateTimeStrFromTimestamp(alarmTimestamp)
        val currentDtStr = getDateTimeStrFromTimestamp(System.currentTimeMillis())
        val delaySeconds = (System.currentTimeMillis() - alarmTimestamp) / 1000

        DebugLogger.d(tag, context.getString(R.string.debug_log_alarm_time_comparison, currentDtStr, alarmDtStr, delaySeconds))

        val followupMinutes = getAppSharedPreferences(context)
            .getString(PrefKeys.FOLLOWUP_TIME_PERIOD_MINUTES, "60")
            ?.toIntOrNull() ?: 60

        val effectiveStage = computeEffectiveAlarmStage(declaredStage, delaySeconds, followupMinutes)
        if (effectiveStage != declaredStage) {
            DebugLogger.d(tag, context.getString(R.string.debug_log_final_alarm_stale, delaySeconds, followupMinutes * 60L))
        }
        return effectiveStage
    }

    class AppForegroundChecker(private val context: Context) {

        private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

        fun isAppInForeground(): Boolean {
            try {
                return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    isAppInForegroundAndroid10Plus()
                } else {
                    isAppInForegroundLegacy()
                }
            } catch (e: Exception) {
                Log.e("AppForegroundChecker", "Error checking if app is in foreground", e)
            }
            return false
        }

        @RequiresApi(Build.VERSION_CODES.Q)
        private fun isAppInForegroundAndroid10Plus(): Boolean {
            val processInfo = activityManager.runningAppProcesses?.firstOrNull {
                it.uid == context.applicationInfo.uid
            } ?: return false

            return processInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }

        private fun isAppInForegroundLegacy(): Boolean {
            val appProcesses = activityManager.runningAppProcesses ?: return false
            val packageName = context.packageName

            for (appProcess in appProcesses) {
                if (appProcess.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                    && appProcess.processName == packageName) {
                    return true
                }
            }
            return false
        }
    }
}

