package io.keepalive.android.receivers


import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import io.keepalive.android.*


class AlarmReceiver : BroadcastReceiver() {

    private val tag = this.javaClass.name

    override fun onReceive(context: Context, intent: Intent) {
        try {
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
            val prefs = getEncryptedSharedPreferences(context)
            val appEnabled = prefs.getBoolean("enabled", false)

            if (!appEnabled) {
                DebugLogger.d(tag, context.getString(R.string.debug_log_app_not_enabled_alarm_went_off))
                return
            }

            /*
            if (!PermissionManager(context, null).checkHavePermissions()) {
                Log.d(tag, "We still need some permissions?!")
            }
            */

            // get the current alarm stage from the intent extras
            val alarmStage = getAlarmStage(context, intent)

            // take action depending on what alarm stage it is
            doAlertCheck(context, alarmStage)

            Log.d(tag, "AlarmReceiver.onReceive() finished")

        } catch (e: Exception) {
            DebugLogger.d(tag, context.getString(R.string.debug_log_failed_processing_alarm), e)
        }
    }

    private fun getAlarmStage(context: Context, intent: Intent): String {
        var alarmStage = ""

        // the current alarm stage should be passed in as an extra
        intent.extras?.let {

            alarmStage = it.getString("AlarmStage", "periodic")

            // also check when the alarm was supposed to go off and compare to
            //   when it actually did go off
            val alarmTimestamp = it.getLong("AlarmTimestamp", 0)

            // this is just for informational purposes so we can see how well Android respects
            //  the alarm time we set...
            if (alarmTimestamp != 0L) {

                val alarmDtStr = getDateTimeStrFromTimestamp(alarmTimestamp)
                val currentDtStr = getDateTimeStrFromTimestamp(System.currentTimeMillis())
                val timeAgo = (System.currentTimeMillis() - alarmTimestamp) / 1000

                DebugLogger.d(tag, context.getString(R.string.debug_log_alarm_time_comparison, currentDtStr, alarmDtStr, timeAgo))
            }
        }
        return alarmStage
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

