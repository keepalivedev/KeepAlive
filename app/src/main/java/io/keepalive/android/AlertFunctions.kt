package io.keepalive.android

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import java.util.Calendar

/**
 * Alert decision engine.
 *
 * This file is the top-level state machine that decides, on each alarm fire,
 * whether to prompt the user, wait, or escalate to the real alert. The
 * supporting pieces live in their own files so each can be tested in isolation:
 *
 *  - [getLastDeviceActivity] (DeviceActivityQuery.kt)  — UsageStatsManager query
 *  - [AlertMessageSender]    (SmsAlertSender.kt)        — SMS dispatch
 *  - [makeAlertCall]         (PhoneAlertCall.kt)        — phone call dispatch
 *  - [AlertService]          (AlertService.kt)          — orchestrates the final alert
 */

/**
 * Start the AlertService to send SMS/call/webhook alerts, and optionally
 * restart periodic monitoring if auto-restart is enabled.
 */
private fun sendFinalAlert(
    context: Context,
    prefs: SharedPreferences,
    nowTimestamp: Long,
    checkPeriodHours: Float,
    restPeriods: MutableList<RestPeriod>
) {
    Intent(context, AlertService::class.java).also { intent ->
        // stamp each alert with the current time so that AlertService can detect
        // redelivered (duplicate) intents and avoid sending the alert twice
        intent.putExtra(AlertService.EXTRA_ALERT_TRIGGER_TIMESTAMP, System.currentTimeMillis())

        DebugLogger.d("doAlertCheck", context.getString(R.string.debug_log_alert_service_start))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    // Reset last_alarm_stage to "periodic" now that the alert has been dispatched.
    // Without this, the BootBroadcastReceiver would read a stale "final" stage from
    // device-protected storage after a reboot and call doAlertCheck("final"), which
    // would immediately resend the alert because there's no recent activity yet.
    // Note: if auto_restart_monitoring is enabled, setAlarm() below will also
    // write "periodic" — but if auto-restart is disabled, this is the only place
    // the stage gets cleared.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        try {
            val devicePrefs = getDeviceProtectedPreferences(context)
            devicePrefs.edit(commit = true) { putString("last_alarm_stage", "periodic") }
        } catch (e: Exception) {
            Log.e("doAlertCheck", "Error resetting alarm stage after sending alert", e)
        }
    }

    if (prefs.getBoolean("auto_restart_monitoring", false)) {
        DebugLogger.d("doAlertCheck", context.getString(R.string.debug_log_auto_restart_monitoring))
        setAlarm(context, nowTimestamp, (checkPeriodHours * 60).toInt(), "periodic", restPeriods)
    }
}

/**
 * Send the "Are you there?" notification.
 */
private fun sendAreYouThereNotification(context: Context, followupPeriodMinutes: Int) {
    AlertNotificationHelper(context).sendNotification(
        context.getString(R.string.initial_check_notification_title),
        String.format(
            context.getString(R.string.initial_check_notification_text),
            followupPeriodMinutes.toString()
        ),
        AppController.ARE_YOU_THERE_NOTIFICATION_ID
    )
}

/**
 * Get the best available reference timestamp from device-protected storage.
 * Prefers last_activity_timestamp, falls back to last_check_timestamp, then [fallback].
 */
private fun getSavedReferenceTimestamp(devicePrefs: SharedPreferences, fallback: Long): Long {
    val savedActivityTimestamp = devicePrefs.getLong("last_activity_timestamp", -1L)
    val savedCheckTimestamp = devicePrefs.getLong("last_check_timestamp", -1L)
    return when {
        savedActivityTimestamp > 0 -> savedActivityTimestamp
        savedCheckTimestamp > 0 -> savedCheckTimestamp
        else -> fallback
    }
}


fun doAlertCheck(context: Context, alarmStage: String) {

    DebugLogger.d("doAlertCheck", context.getString(R.string.debug_log_doing_alert_check, alarmStage))

    val prefs = getEncryptedSharedPreferences(context)

    // get the necessary preferences
    val checkPeriodHours = prefs.getString("time_period_hours", "12")?.toFloatOrNull() ?: 12f
    val followupPeriodMinutes = prefs.getString("followup_time_period_minutes", "60")?.toIntOrNull() ?: 60
    val restPeriods: MutableList<RestPeriod> = loadJSONSharedPreference(prefs,"REST_PERIODS")
    val appsToMonitor: MutableList<MonitoredAppDetails> = loadJSONSharedPreference(prefs,"APPS_TO_MONITOR")

    // if the user hasn't unlocked the device yet (Direct Boot), UsageStatsManager is not
    //  available so we can't query for recent activity. instead, use the alarm state
    //  saved to device-protected storage by setAlarm() — it saves both the alarm stage
    //  (last_alarm_stage) and the scheduled time (NextAlarmTimestamp). this tells us
    //  exactly what alarm was pending and whether its time has passed.
    if (!isUserUnlocked(context)) {

        Log.d("doAlertCheck", "User is locked (Direct Boot), using saved alarm state")

        val devicePrefs = getDeviceProtectedPreferences(context)
        val savedAlarmTimestamp = devicePrefs.getLong("NextAlarmTimestamp", 0L)
        val nowTimestamp = System.currentTimeMillis()

        Log.d("doAlertCheck", "Direct Boot: alarmStage=$alarmStage, " +
                "savedAlarmTimestamp=${if (savedAlarmTimestamp > 0) getDateTimeStrFromTimestamp(savedAlarmTimestamp) else "none"}, " +
                "now=${getDateTimeStrFromTimestamp(nowTimestamp)}, " +
                "alarmIsDue=${savedAlarmTimestamp > 0 && nowTimestamp >= savedAlarmTimestamp}")

        // if there's no saved alarm timestamp (fresh install or data cleared),
        // schedule a periodic alarm from now as a safe fallback
        if (savedAlarmTimestamp <= 0) {
            Log.d("doAlertCheck", "Direct Boot: No saved alarm timestamp, scheduling periodic from now")
            setAlarm(context, nowTimestamp, (checkPeriodHours * 60).toInt(), "periodic", restPeriods)
            return
        }

        // if the alarm's scheduled time hasn't arrived yet, reschedule it.
        // this handles the case where the device rebooted before the alarm was due.
        // reconstruct the base time so setAlarm computes the same target. pass null
        // for rest periods to avoid re-adjusting — the original alarm already
        // accounted for them when it was first set.
        if (nowTimestamp < savedAlarmTimestamp) {
            Log.d("doAlertCheck", "Direct Boot: Alarm not due yet " +
                    "(due at ${getDateTimeStrFromTimestamp(savedAlarmTimestamp)}), rescheduling")

            val periodMinutes = if (alarmStage == "final") followupPeriodMinutes
                                else (checkPeriodHours * 60).toInt()
            val baseTime = savedAlarmTimestamp - (periodMinutes * 60 * 1000L)
            setAlarm(context, baseTime, periodMinutes, alarmStage, null)
            return
        }

        // --- alarm was due (now >= savedAlarmTimestamp) ---

        if (alarmStage == "final") {
            // Mirror the activity re-check the unlocked path does before firing
            // the real alert (see lastInteractiveEvent check further below).
            // UsageStatsManager is not available in Direct Boot, so instead we
            // read last_activity_timestamp from device-protected storage.
            // AcknowledgeAreYouThere.acknowledge() writes this timestamp when
            // the user responds (notification tap, or BootBroadcastReceiver
            // handling unlock), so a value newer than when the "Are you there?"
            // was posted means the user was already acknowledged — typically
            // via a race between this final alarm and the acknowledgement
            // replacing it.
            val areYouTherePostedAt = savedAlarmTimestamp - (followupPeriodMinutes * 60 * 1000L)
            val savedActivityTimestamp = devicePrefs.getLong("last_activity_timestamp", -1L)
            if (savedActivityTimestamp > 0 && savedActivityTimestamp >= areYouTherePostedAt) {
                DebugLogger.d("doAlertCheck",
                    "Direct Boot: activity at ${getDateTimeStrFromTimestamp(savedActivityTimestamp)} " +
                    "is newer than 'Are you there?' posted at ${getDateTimeStrFromTimestamp(areYouTherePostedAt)}; " +
                    "skipping final alert")
                AcknowledgeAreYouThere.acknowledge(context)
                return
            }

            // the "Are you there?" notification was already sent (by the periodic alarm
            // that scheduled this final alarm) and the followup period has elapsed.
            // send the real alert.
            DebugLogger.d("doAlertCheck", context.getString(R.string.debug_log_direct_boot_final_alarm_due))

            try {
                devicePrefs.edit(commit = true) { putBoolean("direct_boot_notification_pending", false) }
                DebugLogger.d("doAlertCheck", context.getString(R.string.debug_log_direct_boot_cleared_pending_flag))
            } catch (e: Exception) {
                Log.e("doAlertCheck", "Error clearing Direct Boot notification flag", e)
            }

            sendFinalAlert(context, prefs, nowTimestamp, checkPeriodHours, restPeriods)
            return
        }

        // --- periodic alarm was due ---

        // the check period has elapsed (the alarm was set for lastActivity + checkPeriodHours,
        // and that time has passed). check if we're in a rest period before escalating.
        val nowCalendar = Calendar.getInstance()
        var isInRestPeriod = false
        if (restPeriods.isNotEmpty()) {
            isInRestPeriod = isWithinRestPeriod(
                nowCalendar.get(Calendar.HOUR_OF_DAY),
                nowCalendar.get(Calendar.MINUTE),
                restPeriods[0]
            )
        }

        if (!isInRestPeriod) {
            Log.d("doAlertCheck", "Direct Boot: Periodic alarm due, sending 'Are you there?' notification")

            DebugLogger.d("doAlertCheck", context.getString(
                R.string.debug_log_no_events_sending_notification, checkPeriodHours))

            sendAreYouThereNotification(context, followupPeriodMinutes)

            // save the flag so BOOT_COMPLETED knows to acknowledge instead of re-alerting.
            // use commit=true (synchronous) because apply() is async and the process can
            // be killed after onReceive() returns, losing the write.
            try {
                devicePrefs.edit(commit = true) { putBoolean("direct_boot_notification_pending", true) }
                DebugLogger.d("doAlertCheck", context.getString(R.string.debug_log_direct_boot_set_pending_flag))
            } catch (e: Exception) {
                Log.e("doAlertCheck", "Error saving Direct Boot notification flag", e)
            }

            // skip the overlay during Direct Boot — AreYouThereOverlayService is not
            // directBootAware and the screen isn't accessible before unlock anyway

            // set the final alarm to follow up
            setAlarm(context, nowTimestamp, followupPeriodMinutes, "final", null)
        } else {
            // in a rest period — reschedule periodic. use last activity as the base
            // so setAlarm properly accounts for rest periods.
            Log.d("doAlertCheck", "Direct Boot: Periodic alarm due but in rest period, rescheduling")
            val ref = getSavedReferenceTimestamp(devicePrefs, nowTimestamp)
            setAlarm(context, ref, (checkPeriodHours * 60).toInt(), "periodic", restPeriods)
        }
        return
    }

    // time in the system default timezone, which is what the rest period will be in
    val nowCalendar = Calendar.getInstance()

    Log.d("doAlertCheck", "current local time is ${getDateTimeStrFromTimestamp(nowCalendar.timeInMillis, nowCalendar.timeZone.id)}")

    // time in milliseconds since epoch, store this so we use the same time for everything
    val nowTimestamp = System.currentTimeMillis()

    Log.d(
        "doAlertCheck",
        "check period is $checkPeriodHours hours, " +
                "followup period is $followupPeriodMinutes minutes."
    )

    // assume no rest periods and default to searching for activity starting at
    //  the beginning of the check period
    var activitySearchStartTimestamp = (nowTimestamp - (checkPeriodHours * 1000 * 60 * 60)).toLong()
    var isInRestPeriod = false

    Log.d("doAlertCheck", "activity search start timestamp is " +
            getDateTimeStrFromTimestamp(activitySearchStartTimestamp)
    )

    // if there is a rest period set, determine whether we are currently in a rest period and
    //  adjust the activity search start time accordingly
    if (restPeriods.isNotEmpty()) {

        // get whether we are currently in a rest period
        isInRestPeriod = isWithinRestPeriod(nowCalendar.get(Calendar.HOUR_OF_DAY),
            nowCalendar.get(Calendar.MINUTE), restPeriods[0])

        // get a date in the past that is checkPeriodHours ago while excluding any rest periods
        //  and then convert it to a timestamp
        activitySearchStartTimestamp = calculateOffsetDateTimeExcludingRestPeriod(
            nowCalendar, (checkPeriodHours * 60).toInt(), restPeriods[0], "backward"
        ).timeInMillis

        Log.d("doAlertCheck", "updating activity search start timestamp to " +
                getDateTimeStrFromTimestamp(activitySearchStartTimestamp))
    }

    // double check that there is still no recent user activity
    val lastInteractiveEvent = getLastDeviceActivity(
        context, activitySearchStartTimestamp, appsToMonitor.map { it.packageName }
    )

    // save the last activity state to device-protected storage so it is available
    //  during Direct Boot if the device reboots before the next alarm fires.
    //  this runs every alarm cycle so the data stays reasonably fresh.
    //  use commit=true because this runs inside a BroadcastReceiver (AlarmReceiver)
    //  where the process can be killed after onReceive() returns.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        try {
            val devicePrefs = getDeviceProtectedPreferences(context)
            devicePrefs.edit(commit = true) {
                putLong("last_check_timestamp", nowTimestamp)
                if (lastInteractiveEvent != null) {
                    putLong("last_activity_timestamp", lastInteractiveEvent.timeStamp)
                }
            }
        } catch (e: Exception) {
            Log.e("doAlertCheck", "Error saving activity state to device-protected storage", e)
        }
    }

    // if this is the final alarm and there is no recent activity then we need to send the alert
    // DO NOT CHECK FOR REST PERIOD HERE; final alarms should never be set during
    //  a rest period so we can assume that the 'are you there?' check was done
    //  outside of a rest period and so we should still send the alert
    if (alarmStage == "final" && lastInteractiveEvent == null) {

        sendFinalAlert(context, prefs, nowTimestamp, checkPeriodHours, restPeriods)
        return
    }

    // todo make this a debuglogger message
    if (lastInteractiveEvent == null && isInRestPeriod) {
        Log.d("doAlertCheck", "No events found but we are in a rest period, not sending alert")
    }

    // if no events were found then we need to send the followup notification and set a final alarm
    // make sure we aren't in a rest period though because we should never initiate the
    //  'are you there?' check during a rest period
    if (lastInteractiveEvent == null && !isInRestPeriod) {
        DebugLogger.d("doAlertCheck", context.getString(R.string.debug_log_no_events_sending_notification, checkPeriodHours))

        sendAreYouThereNotification(context, followupPeriodMinutes)

        // If we have overlay permission, also show a full-screen warning over other apps.
        // This makes the prompt effectively impossible to miss (e.g., while watching video).
        // Note: no need to check isUserUnlocked() here — the Direct Boot path returns
        // early above, so we are guaranteed the user is unlocked at this point.
        AreYouThereOverlay.show(
            context,
            String.format(
                context.getString(R.string.initial_check_notification_text),
                followupPeriodMinutes.toString()
            )
        )

        // if no events are found then set the alarm so we follow up; do not adjust the followup
        //  time based on the rest periods
        setAlarm(context, nowTimestamp, followupPeriodMinutes, "final", null)

    } else {
        // use the last event timestamp if we found one, otherwise it means we are in a rest period
        //  so assume that the last activity was checkPeriodHours ago so that the new alarm
        //  gets set for the end of the current rest period
        val lastInteractiveEventTimestamp = lastInteractiveEvent?.timeStamp ?: (nowTimestamp - (checkPeriodHours * 60 * 60 * 1000)).toLong()

        // this just here for informational purposes
        val lastEventMsAgo = nowTimestamp - lastInteractiveEventTimestamp
        Log.d("doAlertCheck", "last event was ${lastEventMsAgo / 1000} seconds ago")

        // set a new alarm so we can check again in the future
        setAlarm(context, lastInteractiveEventTimestamp, (checkPeriodHours * 60).toInt(),
            "periodic", restPeriods)
    }
}