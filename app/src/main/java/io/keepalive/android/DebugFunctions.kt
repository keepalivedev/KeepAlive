package io.keepalive.android

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.edit
import com.google.gson.Gson
import io.keepalive.android.receivers.AlarmReceiver

// various functions that are useful for testing
class DebugFunctions {

    fun setSharedPrefs(context: Context) {

        val gson = Gson()
        val sharedPrefs = getAppSharedPreferences(context)

        val smsContactList = listOf(
            SMSEmergencyContactSetting(
                alertMessage = "abc123",
                includeLocation = true,
                isEnabled = true,
                phoneNumber = "2345678901"
            )
        )

        sharedPrefs.edit {
            putString(PrefKeys.PHONE_NUMBER_SETTINGS, gson.toJson(smsContactList))

            putString(PrefKeys.CONTACT_PHONE, "2345678901")

            // leave these at the default
            putString(PrefKeys.TIME_PERIOD_HOURS, "0.21")
            putString(PrefKeys.FOLLOWUP_TIME_PERIOD_MINUTES, "60")

            // make it think there is an alarm in the future
            // putLong(PrefKeys.NEXT_ALARM_TIMESTAMP, System.currentTimeMillis() + 1000 * 60 * 60 * 12)

            // enable monitoring and auto restart
            putBoolean(PrefKeys.ENABLED, true)
            putBoolean(PrefKeys.AUTO_RESTART_MONITORING, true)

            putBoolean(PrefKeys.WEBHOOK_ENABLED, true)

            // location is enabled if it is anything but the 'do not include' option
            putBoolean(PrefKeys.WEBHOOK_LOCATION_ENABLED, true)

            // save the raw url instead of from toHttpUrlOrNull so that it will show up
            //  in a more user friendly way
            putString(PrefKeys.WEBHOOK_URL, "https://example.com/webhook_test")
            putString(PrefKeys.WEBHOOK_METHOD, "POST")
            putString(PrefKeys.WEBHOOK_INCLUDE_LOCATION, "JSON - Body")
            putInt(PrefKeys.WEBHOOK_TIMEOUT, 30)
            putInt(PrefKeys.WEBHOOK_RETRIES, 3)
            putBoolean(PrefKeys.WEBHOOK_VERIFY_CERTIFICATE, false)
            putString(PrefKeys.WEBHOOK_HEADERS, "{}")
        }
    }

    fun setTestAlarm(context: Context, alarmDuration: Int) {

        // get a timestamp for when the alarm should go off
        val alarmTimestamp = System.currentTimeMillis() + alarmDuration * 1000
        val intent = Intent(context, AlarmReceiver::class.java).also {
            it.putExtra("AlarmStage", "final")
            it.putExtra("AlarmTimestamp", alarmTimestamp)
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val pendingIntent = PendingIntent.getBroadcast(
            context, AppController.ACTIVITY_ALARM_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        println("Setting alarm for about dialog to ${getDateTimeStrFromTimestamp(alarmTimestamp)}")

        //if (alarmManager!!.canScheduleExactAlarms()) {
        alarmManager!!.setAlarmClock(
            AlarmManager.AlarmClockInfo(
                alarmTimestamp,
                pendingIntent
            ), pendingIntent
        )
        //}
    }
}