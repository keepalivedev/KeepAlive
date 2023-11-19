package io.keepalive.android

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.preference.PreferenceManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.keepalive.android.receivers.AlarmReceiver
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Date


// data class used to represent an SMS emergency contact setting
data class SMSEmergencyContactSetting(
    var phoneNumber: String,
    var alertMessage: String,
    var isEnabled: Boolean,
    var includeLocation: Boolean
)

// load the SMS emergency contact settings from shared preferences
fun loadSMSEmergencyContactSettings(sharedPrefs: SharedPreferences): MutableList<SMSEmergencyContactSetting> {

    // the SMS contact settings are stored as a json string
    val jsonString = sharedPrefs.getString("PHONE_NUMBER_SETTINGS", null) ?: return mutableListOf()
    Log.d("loadSettings", "SMS Emergency Contact Settings: $jsonString")
    println("SMS Emergency Contact Settings: $jsonString")

    val gson = Gson()

    // turn the json into a list of SMSEmergencyContactSetting objects
    return gson.fromJson(
        jsonString,
        object : TypeToken<List<SMSEmergencyContactSetting>>() {}.type
    )
}

// save the SMS emergency contact settings to shared preferences
fun saveSMSEmergencyContactSettings(
    sharedPrefs: SharedPreferences?,
    phoneNumberList: MutableList<SMSEmergencyContactSetting>,
    gson: Gson
) {

    try {
        // turn the phone number list into a string
        val jsonString = gson.toJson(phoneNumberList)
        Log.d("PhoneNumberAdapter", "Saving settings: $jsonString")

        // if any of the phone numbers have location enabled then we need to set the
        //  location_enabled preference to true
        var includeLocation = false

        for (phoneNumberSetting in phoneNumberList) {
            if (phoneNumberSetting.includeLocation) {
                Log.d("saveSettings", "phoneNumberSetting: $phoneNumberSetting")
                includeLocation = true
            }
        }

        with(sharedPrefs!!.edit()) {
            putString("PHONE_NUMBER_SETTINGS", jsonString)

            // this is stored as a single setting so that it can be more easily checked elsewhere
            putBoolean("location_enabled", includeLocation)
            apply()
        }
    } catch (e: Exception) {
        Log.e("PhoneNumberAdapter", "Error saving settings: ${e.message}")
    }
}

// originally implemented because I thought one of the questions in the play store asked about it
//  but that wasn't the case.  to reduce complexity and the chances of this failing, just
//  use the default shared preferences as we aren't really store anything sensitive anyway
fun getEncryptedSharedPreferences(context: Context): SharedPreferences {
    return try {
        /*
        Log.d("getEncryptedSharedPreferences", "Getting encrypted shared preferences")
        // this gets a system generated master key that is stored in the android keystore?
        // by default this won't require that the device be unlocked?
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        // get or create the encrypted shared preferences and return it
        EncryptedSharedPreferences.create(
            context,
            AppController.ENCRYPTED_SHARED_PREFERENCES_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        */
        PreferenceManager.getDefaultSharedPreferences(context)

    } catch (e: Exception) {

        // fall back to the default shared preferences...
        Log.e("getEncryptedSharedPreferences", "Failed getting encrypted shared preferences?!", e)
        PreferenceManager.getDefaultSharedPreferences(context)
    }
}

fun getDateTimeStrFromTimestamp(timestamp: Long, timeZone: ZoneId = ZoneOffset.UTC): String {
    return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(timeZone)
        .format(Date(timestamp).toInstant())
}


// set an alarm so that we can check up on the user in the future
fun setAlarm(context: Context, alarmInMs: Long, alarmStage: String = "initial") {

    // when the alarm is supposed to go off
    val alarmTimestamp = System.currentTimeMillis() + alarmInMs

    // convert the timestamp to a string for use in logging
    val alarmDtStr = getDateTimeStrFromTimestamp(alarmTimestamp)

    // more info https://developer.android.com/training/scheduling/alarms
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    // when an alarm goes off it will call the AlarmReceiver
    val intent = Intent(context, AlarmReceiver::class.java).also {

        // let the AlarmReceiver know what alarm stage this is
        it.putExtra("AlarmStage", alarmStage)

        // also add what time the alarm was supposed to go off so we can track it
        it.putExtra("AlarmTimestamp", alarmTimestamp)
    }

    // the FLAG_IMMUTABLE part only refers to OTHER APPS being able to modify the intent??
    // need FLAG_UPDATE_CURRENT in order to be able to update the extras on the intent
    //  yes but does that allow us to actually update the alarm itself?
    val pendingIntent = PendingIntent.getBroadcast(
        context, AppController.ACTIVITY_ALARM_REQUEST_CODE, intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    // these alarms are not super time sensitive so as long as they happen eventually it's fine?
    if (alarmStage == "periodic") {

        Log.d("setAlarm", "Setting new periodic alarm for approximately $alarmDtStr")

        // we are not using setExactAndAllowWhileIdle() because it could lead to more
        //  battery usage but can ultimately still get delayed or ignored by the OS?
        // the alternative is to use setAlarmClock() for every alarm but that would definitely
        //  lead to more battery usage...
        // from the docs: To perform work while the device is in Doze, create an inexact alarm
        //                 using setAndAllowWhileIdle(), and start a job from the alarm.
        // will never go off before, but may be delayed up to an hour??
        alarmManager?.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,

            // this uses system time instead of RTC time
            SystemClock.elapsedRealtime() + alarmInMs,
            pendingIntent
        )
    } else {

        // assume we can use exact alarms by default
        var useExact = true

        // API 31+ added new permissions for setting exact alarms so we need to
        //  make sure we have them
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            useExact = alarmManager != null && alarmManager.canScheduleExactAlarms()
        }

        if (useExact) {
            Log.d("setAlarm", "Setting new exact alarm to go off at $alarmDtStr")

            // according to the docs, this is the only thing that won't get delayed by the OS?
            // random note, this shows up on the lock screen as a pending alarm
            alarmManager?.setAlarmClock(
                AlarmManager.AlarmClockInfo(
                    alarmTimestamp,
                    pendingIntent
                ), pendingIntent
            )

            // if for some reason we can't use exact alarms, try setting a normal one
        } else {

            Log.d(
                "setAlarm", "Unable to set exact alarm?!  " +
                        "Setting normal alarm to go off at $alarmDtStr"
            )
            alarmManager?.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + alarmInMs,
                pendingIntent
            )
        }
    }

    val prefs = getEncryptedSharedPreferences(context)

    // previous alarm may not actually be active anymore but no way to know for sure
    val previousAlarmTimestamp = prefs.getLong("NextAlarmTimestamp", 0)
    Log.d(
        "setAlarm", "Previous alarm was set to" +
                " ${getDateTimeStrFromTimestamp(previousAlarmTimestamp)}"
    )

    // track when the next alarm is set to go off in our preferences
    with(prefs.edit()) {
        putLong("NextAlarmTimestamp", System.currentTimeMillis() + alarmInMs)
        apply()
    }
}

// cancelling alarm usually not necessary as setting a new one will overwrite any existing ones
fun cancelAlarm(context: Context) {

    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val intent = Intent(context, AlarmReceiver::class.java)
    val pendingIntent = PendingIntent.getBroadcast(
        context, AppController.ACTIVITY_ALARM_REQUEST_CODE, intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    alarmManager.cancel(pendingIntent)
}