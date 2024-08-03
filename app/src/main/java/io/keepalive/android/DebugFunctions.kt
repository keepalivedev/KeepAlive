package io.keepalive.android

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.gson.Gson
import io.keepalive.android.receivers.AlarmReceiver

// various functions that are useful for testing
class DebugFunctions {

    fun setSharedPrefs(context: Context) {

        val gson = Gson()
        val sharedPrefs = getEncryptedSharedPreferences(context)

        val smsContactList = listOf(
            SMSEmergencyContactSetting(
                alertMessage = "abc123",
                includeLocation = true,
                isEnabled = true,
                phoneNumber = "2345678901"
            )
        )

        with(sharedPrefs.edit()) {
            putString("PHONE_NUMBER_SETTINGS", gson.toJson(smsContactList))

            putString("contact_phone", "2345678901")

            // leave these at the default
            putString("time_period_hours", "0.21")
            putString("followup_time_period_minutes", "60")

            // make it think there is an alarm in the future
            // putLong("NextAlarmTimestamp", System.currentTimeMillis() + 1000 * 60 * 60 * 12)

            // enable monitoring and auto restart
            putBoolean("enabled", true)
            putBoolean("auto_restart_monitoring", true)

            putBoolean("webhook_enabled", true)

            // location is enabled if it is anything but the 'do not include' option
            putBoolean("webhook_location_enabled", true)

            // save the raw url instead of from toHttpUrlOrNull so that it will show up
            //  in a more user friendly way
            putString("webhook_url", "https://home.pathead.io/home/webhook_test")
            putString("webhook_method", "POST")
            putString("webhook_include_location", "JSON - Body")
            putInt("webhook_timeout", 30)
            putInt("webhook_retries", 3)
            putBoolean("webhook_verify_certificate", false)
            putString("webhook_headers", "{}")

            apply()
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