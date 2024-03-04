package io.keepalive.android

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.annotation.ColorRes
import androidx.preference.PreferenceManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.keepalive.android.receivers.AlarmReceiver
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.TimeZone


// data class used to represent an SMS emergency contact setting
data class SMSEmergencyContactSetting(
    var phoneNumber: String,
    var alertMessage: String,
    var isEnabled: Boolean,
    var includeLocation: Boolean
)

// data class used to represent a rest period
data class RestPeriod(
    var startHour: Int,
    var startMinute: Int,
    var endHour: Int,
    var endMinute: Int
)

// data class used to track data about a monitored app
data class MonitoredAppInfo(
    val packageName: String,
    val appName: String,
    val lastUsed: Long,
    val className: String
)

// load a JSON string from shared preferences and convert it to a list of objects
// used with the SMSEmergencyContactSetting and RestPeriods
inline fun <reified T> loadJSONSharedPreference(
    sharedPrefs: SharedPreferences,
    preferenceKey: String
): MutableList<T> {
    val jsonString = sharedPrefs.getString(preferenceKey, null) ?: return mutableListOf()
    Log.d("loadJSONSharedPref", "Loading $preferenceKey: $jsonString")

    val gson = Gson()
    return gson.fromJson(
        jsonString,
        object : TypeToken<List<T>>() {}.type
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
        DebugLogger.d("getSharedPrefs", "Failed getting shared preferences?!", e)
        PreferenceManager.getDefaultSharedPreferences(context)
    }
}


fun getDateTimeStrFromTimestamp(timestamp: Long, timeZoneId: String = "UTC"): String {
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    formatter.timeZone = TimeZone.getTimeZone(timeZoneId)
    return formatter.format(Date(timestamp))
}


// set an alarm so that we can check up on the user in the future
fun setAlarm(
    context: Context,
    alarmInMs: Long,
    alarmStage: String,
    restPeriods: MutableList<RestPeriod>? = null
) {

    // when the alarm is supposed to go off
    var alarmTimestamp = System.currentTimeMillis() + alarmInMs

    // if we have any rest periods; this will be null for final stage alarms
    //  so that it ignores rest periods
    if (!restPeriods.isNullOrEmpty()) {

        // if the future alert time would be during a rest period then delay it
        //  until the end of the rest period
        val adjustedAlarmTimestamp = adjustTimestampIfInRestPeriod(alarmTimestamp, restPeriods[0])

        // informational check so we know if the timestamp was adjusted
        if (adjustedAlarmTimestamp != alarmTimestamp) {

            Log.d(
                "setAlarm", "Original alarm set for " +
                        "${getDateTimeStrFromTimestamp(alarmTimestamp)} which would " +
                        "be during rest period ${restPeriods[0]}, adjusting to " +
                        getDateTimeStrFromTimestamp(adjustedAlarmTimestamp)
            )

            alarmTimestamp = adjustedAlarmTimestamp
        }
    }

    // convert the timestamp to a string for use in logging
    val alarmDtStr = getDateTimeStrFromTimestamp(alarmTimestamp)

    // more info https://developer.android.com/training/scheduling/alarms
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    if (alarmManager == null) {
        DebugLogger.d("setAlarm", "Failed to get AlarmManager?!")
        return
    }

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

        // we are not using setExactAndAllowWhileIdle() because it could lead to more
        //  battery usage but can ultimately still get delayed or ignored by the OS?
        // the alternative is to use setAlarmClock() for every alarm but that would definitely
        //  lead to more battery usage...
        // from the docs: To perform work while the device is in Doze, create an inexact alarm
        //                 using setAndAllowWhileIdle(), and start a job from the alarm.
        // will never go off before, but may be delayed up to an hour??
        // also, if viewing the alarms in the Background Task Inspector, the 'Trigger time'
        //  that is displayed is based on SystemClock.elapsedRealtime() so can be misleading...
        // furthermore, emulators can exhibit weird behavior when using ELAPSED_REALTIME_WAKEUP
        //  alarms because their internal time is off, potentially causing an alarm to go off
        //  and be re-set continuously because the emulator's time is in the future...
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

            DebugLogger.d("setAlarm", "Setting new periodic alarm for approximately $alarmDtStr")

            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,

                // this uses system time instead of RTC time
                SystemClock.elapsedRealtime() + alarmInMs,
                pendingIntent
            )
        } else {

            DebugLogger.d("setAlarm", "Setting new periodic alarm for exactly $alarmDtStr")

            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(
                    alarmTimestamp,
                    pendingIntent
                ), pendingIntent
            )
        }

    } else {

        // assume we can use exact alarms by default
        var useExact = true

        // API 31+ added new permissions for setting exact alarms so we need to
        //  make sure we have them
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            useExact = alarmManager.canScheduleExactAlarms()
        }

        if (useExact) {
            DebugLogger.d("setAlarm", "Setting final exact alarm to go off at $alarmDtStr")

            // according to the docs, this is the only thing that won't get delayed by the OS?
            // random note, this shows up on the lock screen as a pending alarm
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(
                    alarmTimestamp,
                    pendingIntent
                ), pendingIntent
            )

            // if for some reason we can't use exact alarms, try setting a normal one
        } else {

            DebugLogger.d(
                "setAlarm", "Unable to set exact alarm?!  " +
                        "Setting normal final alarm to go off at $alarmDtStr"
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + alarmInMs,
                    pendingIntent
                )
            } else {
                DebugLogger.d(
                    "setAlarm", "This is API 22 but we weren't able to set an exact alarm?!"
                )
                // this should really never happen right? but if it does this is what we would
                //  have to use...
                alarmManager.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + alarmInMs,
                    pendingIntent
                )
            }
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
        putLong("NextAlarmTimestamp", alarmTimestamp)
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

// adjust a timestamp to the end of a rest period if it is within the rest period
fun adjustTimestampIfInRestPeriod(
    utcTimestampMillis: Long,
    restPeriod: RestPeriod,
    timeZoneId: String = TimeZone.getDefault().id
): Long {
    val localZone = TimeZone.getTimeZone(timeZoneId)
    val calendar = Calendar.getInstance(localZone).apply {
        timeInMillis = utcTimestampMillis
    }

    val hourOfDay = calendar.get(Calendar.HOUR_OF_DAY)
    val minute = calendar.get(Calendar.MINUTE)

    if (isWithinRestPeriod(hourOfDay, minute, restPeriod)) {
        val startTimeMinutes = restPeriod.startHour * 60 + restPeriod.startMinute
        val endTimeMinutes = restPeriod.endHour * 60 + restPeriod.endMinute
        val currentTimeMinutes = hourOfDay * 60 + minute

        // Calculate the adjustment in minutes
        val adjustmentMinutes = if (endTimeMinutes > startTimeMinutes) {
            endTimeMinutes - currentTimeMinutes
        } else {
            if (currentTimeMinutes >= startTimeMinutes) {
                24 * 60 - currentTimeMinutes + endTimeMinutes // Crosses midnight to the next day
            } else {
                endTimeMinutes - currentTimeMinutes // Before midnight, same day
            }
        }

        // Adjust the calendar object
        calendar.add(Calendar.MINUTE, adjustmentMinutes)
        return calendar.timeInMillis
    }

    return utcTimestampMillis
}


// we need to look for activity over a certain time range while making sure to exclude rest periods
fun calculatePastDateTimeExcludingRestPeriod(
    targetDtCalendar: Calendar, checkPeriodHours: Float, restPeriod: RestPeriod
): Calendar {

    // make a copy of the target date time so we don't modify the original
    val thisTargetDtCalendar = targetDtCalendar.clone() as Calendar

    // at a minimum, the amount of time that needs to be subtracted based on the check period
    var minutesToSubtract = (checkPeriodHours * 60).toLong()

    // track how many minutes were skipped for informational purposes, should either be 0 or
    //  equal to the # of minutes in the rest period?
    var skippedMinutes = 0

    // special case to check if the rest period start and end times are the same to prevent
    //  an infinite loop in the code below; assume we have no rest period in this case
    // the user shouldn't be allowed to save a rest period with the same start and end times but
    //  leave this check here just in case...
    if (restPeriod.startHour == restPeriod.endHour && restPeriod.startMinute == restPeriod.endMinute) {
        Log.d("calcPastDtExcRestPeriod", "Invalid rest period? $restPeriod")
        thisTargetDtCalendar.add(Calendar.MINUTE, -minutesToSubtract.toInt())
    } else {
        while (minutesToSubtract > 0) {

            // every minute, check whether the current time is within the rest period and, if not,
            //  subtract another minute until we get to 0 minutes, i.e. the end of the check period

            thisTargetDtCalendar.add(Calendar.MINUTE, -1)

            // if the current time isn't within the rest period, subtract another minute
            if (!isWithinRestPeriod(
                    thisTargetDtCalendar.get(Calendar.HOUR_OF_DAY),
                    thisTargetDtCalendar.get(Calendar.MINUTE),
                    restPeriod
            )) {
                minutesToSubtract--
            } else {
                skippedMinutes++
            }
        }
    }

    // Convert the local datetime to UTC
    val utcCalendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    utcCalendar.timeInMillis = thisTargetDtCalendar.timeInMillis

    Log.d(
        "calcPastDtExcRestPeriod",
        "Returning local DT $thisTargetDtCalendar, $utcCalendar UTC, " +
                "skipped $skippedMinutes minutes, check period hours was $checkPeriodHours"
    )

    return utcCalendar
}

// function that returns whether the given time is within the given rest period
fun isWithinRestPeriod(
    hourOfDay: Int,
    minute: Int,
    restPeriod: RestPeriod
): Boolean {
    val startHour = restPeriod.startHour
    val startMinute = restPeriod.startMinute
    val endHour = restPeriod.endHour
    val endMinute = restPeriod.endMinute

    val startTimeMinutes = startHour * 60 + startMinute
    val endTimeMinutes = endHour * 60 + endMinute
    val currentTimeMinutes = hourOfDay * 60 + minute

    return if (startTimeMinutes < endTimeMinutes) {
        currentTimeMinutes in startTimeMinutes until endTimeMinutes
    } else {
        currentTimeMinutes >= startTimeMinutes || currentTimeMinutes < endTimeMinutes
    }
}

// this will just be kept in memory and if we close the app the logs are lost
object DebugLogger {
    private val logBuffer = Collections.synchronizedList(mutableListOf<String>())
    private const val MAX_BUFFER_SIZE = 100

    fun d(tag: String, message: String, ex: Exception? = null) {
        Log.d(tag, message, ex)
        val logMessage = "${getDateTimeStrFromTimestamp(System.currentTimeMillis())}: $message"
        addLog(logMessage)
    }

    private fun addLog(log: String) {
        synchronized(logBuffer) {
            logBuffer.add(0, log) // Add new log at the beginning
            if (logBuffer.size > MAX_BUFFER_SIZE) {
                logBuffer.removeAt(logBuffer.size - 1) // Remove oldest log
            }
        }
    }

    fun getLogs(): List<String> {
        return logBuffer.toList() // Return a copy of the logs
    }
}

fun getColorCompat(context: Context, @ColorRes colorResId: Int): Int {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        context.resources.getColor(colorResId, context.theme)
    } else {
        @Suppress("DEPRECATION")
        context.resources.getColor(colorResId)
    }
}