package io.keepalive.android

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import java.util.Calendar

/**
 * Alert decision engine.
 *
 * This file is the top-level state machine that decides, on each alarm fire,
 * whether to prompt the user, wait, or escalate to the real alert. All
 * side-effecting operations go through [AlertCheckDeps], so the engine can
 * be tested with a fake deps implementation — no Context, no AlarmManager,
 * no UsageStatsManager needed.
 *
 * Supporting pieces live in their own files:
 *  - [getLastDeviceActivity] (DeviceActivityQuery.kt)  — UsageStatsManager query
 *  - [AlertMessageSender]    (SmsAlertSender.kt)        — SMS dispatch
 *  - [makeAlertCall]         (PhoneAlertCall.kt)        — phone call dispatch
 *  - [AlertService]          (AlertService.kt)          — orchestrates the final alert
 */

/**
 * Get the best available reference timestamp from device-protected storage.
 * Prefers last_activity_timestamp, falls back to last_check_timestamp, then [fallback].
 *
 * Pure helper — no side effects, safe to call directly from tests.
 */
internal fun getSavedReferenceTimestamp(devicePrefs: SharedPreferences, fallback: Long): Long {
    val savedActivityTimestamp = devicePrefs.getLong("last_activity_timestamp", -1L)
    val savedCheckTimestamp = devicePrefs.getLong("last_check_timestamp", -1L)
    return when {
        savedActivityTimestamp > 0 -> savedActivityTimestamp
        savedCheckTimestamp > 0 -> savedCheckTimestamp
        else -> fallback
    }
}


/**
 * Production entry point. Wraps the real device in [ProductionAlertCheckDeps]
 * and delegates to the testable overload below.
 */
fun doAlertCheck(context: Context, alarmStage: String) {
    doAlertCheck(ProductionAlertCheckDeps(context), alarmStage)
}


/**
 * Testable decision engine. All device interaction goes through [deps]; the
 * only direct calls here are to pure helpers and string resources via the
 * notification/overlay methods on [deps].
 */
internal fun doAlertCheck(deps: AlertCheckDeps, alarmStage: String) {

    DebugLogger.d("doAlertCheck", deps.getString(R.string.debug_log_doing_alert_check, alarmStage))

    val prefs = deps.credentialPrefs()

    // get the necessary preferences
    val checkPeriodHours = prefs.getString("time_period_hours", "12")?.toFloatOrNull() ?: 12f
    val followupPeriodMinutes = prefs.getString("followup_time_period_minutes", "60")?.toIntOrNull() ?: 60
    val restPeriods: MutableList<RestPeriod> = loadJSONSharedPreference(prefs, "REST_PERIODS")
    val appsToMonitor: MutableList<MonitoredAppDetails> = loadJSONSharedPreference(prefs, "APPS_TO_MONITOR")

    // if the user hasn't unlocked the device yet (Direct Boot), UsageStatsManager is not
    //  available so we can't query for recent activity. instead, use the alarm state
    //  saved to device-protected storage by setAlarm() — it saves both the alarm stage
    //  (last_alarm_stage) and the scheduled time (NextAlarmTimestamp). this tells us
    //  exactly what alarm was pending and whether its time has passed.
    if (!deps.isUserUnlocked()) {

        Log.d("doAlertCheck", "User is locked (Direct Boot), using saved alarm state")

        // devicePrefs should always be non-null in Direct Boot (API >= N),
        // but guard defensively anyway.
        val devicePrefs = deps.devicePrefs() ?: run {
            Log.e("doAlertCheck", "Direct Boot path but devicePrefs() returned null; aborting")
            return
        }
        val savedAlarmTimestamp = devicePrefs.getLong("NextAlarmTimestamp", 0L)
        val nowTimestamp = deps.now()

        Log.d("doAlertCheck", "Direct Boot: alarmStage=$alarmStage, " +
                "savedAlarmTimestamp=${if (savedAlarmTimestamp > 0) getDateTimeStrFromTimestamp(savedAlarmTimestamp) else "none"}, " +
                "now=${getDateTimeStrFromTimestamp(nowTimestamp)}, " +
                "alarmIsDue=${savedAlarmTimestamp > 0 && nowTimestamp >= savedAlarmTimestamp}")

        // if there's no saved alarm timestamp (fresh install or data cleared),
        // schedule a periodic alarm from now as a safe fallback
        if (savedAlarmTimestamp <= 0) {
            Log.d("doAlertCheck", "Direct Boot: No saved alarm timestamp, scheduling periodic from now")
            deps.scheduleAlarm(nowTimestamp, (checkPeriodHours * 60).toInt(), "periodic", restPeriods)
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
            deps.scheduleAlarm(baseTime, periodMinutes, alarmStage, null)
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
                deps.acknowledgeAreYouThere()
                return
            }

            // the "Are you there?" notification was already sent (by the periodic alarm
            // that scheduled this final alarm) and the followup period has elapsed.
            // send the real alert.
            DebugLogger.d("doAlertCheck", deps.getString(R.string.debug_log_direct_boot_final_alarm_due))

            try {
                devicePrefs.edit(commit = true) { putBoolean("direct_boot_notification_pending", false) }
                DebugLogger.d("doAlertCheck", deps.getString(R.string.debug_log_direct_boot_cleared_pending_flag))
            } catch (e: Exception) {
                Log.e("doAlertCheck", "Error clearing Direct Boot notification flag", e)
            }

            deps.dispatchFinalAlert(prefs, nowTimestamp, checkPeriodHours, restPeriods)
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

            DebugLogger.d("doAlertCheck", deps.getString(
                R.string.debug_log_no_events_sending_notification, checkPeriodHours))

            deps.showAreYouThereNotification(followupPeriodMinutes)

            // save the flag so BOOT_COMPLETED knows to acknowledge instead of re-alerting.
            // use commit=true (synchronous) because apply() is async and the process can
            // be killed after onReceive() returns, losing the write.
            try {
                devicePrefs.edit(commit = true) { putBoolean("direct_boot_notification_pending", true) }
                DebugLogger.d("doAlertCheck", deps.getString(R.string.debug_log_direct_boot_set_pending_flag))
            } catch (e: Exception) {
                Log.e("doAlertCheck", "Error saving Direct Boot notification flag", e)
            }

            // skip the overlay during Direct Boot — AreYouThereOverlayService is not
            // directBootAware and the screen isn't accessible before unlock anyway

            // set the final alarm to follow up
            deps.scheduleAlarm(nowTimestamp, followupPeriodMinutes, "final", null)
        } else {
            // in a rest period — reschedule periodic. use last activity as the base
            // so setAlarm properly accounts for rest periods.
            Log.d("doAlertCheck", "Direct Boot: Periodic alarm due but in rest period, rescheduling")
            val ref = getSavedReferenceTimestamp(devicePrefs, nowTimestamp)
            deps.scheduleAlarm(ref, (checkPeriodHours * 60).toInt(), "periodic", restPeriods)
        }
        return
    }

    // time in the system default timezone, which is what the rest period will be in
    val nowCalendar = Calendar.getInstance()

    Log.d("doAlertCheck", "current local time is ${getDateTimeStrFromTimestamp(nowCalendar.timeInMillis, nowCalendar.timeZone.id)}")

    // time in milliseconds since epoch, store this so we use the same time for everything
    val nowTimestamp = deps.now()

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
    val lastInteractiveEvent = deps.getLastDeviceActivity(
        activitySearchStartTimestamp, appsToMonitor.map { it.packageName }
    )

    // save the last activity state to device-protected storage so it is available
    //  during Direct Boot if the device reboots before the next alarm fires.
    //  this runs every alarm cycle so the data stays reasonably fresh.
    //  use commit=true because this runs inside a BroadcastReceiver (AlarmReceiver)
    //  where the process can be killed after onReceive() returns.
    deps.devicePrefs()?.let { devicePrefs ->
        try {
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

        deps.dispatchFinalAlert(prefs, nowTimestamp, checkPeriodHours, restPeriods)
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
        DebugLogger.d("doAlertCheck", deps.getString(R.string.debug_log_no_events_sending_notification, checkPeriodHours))

        deps.showAreYouThereNotification(followupPeriodMinutes)

        // If we have overlay permission, also show a full-screen warning over other apps.
        // This makes the prompt effectively impossible to miss (e.g., while watching video).
        // Note: no need to check isUserUnlocked() here — the Direct Boot path returns
        // early above, so we are guaranteed the user is unlocked at this point.
        deps.showAreYouThereOverlay(followupPeriodMinutes)

        // if no events are found then set the alarm so we follow up; do not adjust the followup
        //  time based on the rest periods
        deps.scheduleAlarm(nowTimestamp, followupPeriodMinutes, "final", null)

    } else {
        // use the last event timestamp if we found one, otherwise it means we are in a rest period
        //  so assume that the last activity was checkPeriodHours ago so that the new alarm
        //  gets set for the end of the current rest period
        val lastInteractiveEventTimestamp = lastInteractiveEvent?.timeStamp ?: (nowTimestamp - (checkPeriodHours * 60 * 60 * 1000)).toLong()

        // this just here for informational purposes
        val lastEventMsAgo = nowTimestamp - lastInteractiveEventTimestamp
        Log.d("doAlertCheck", "last event was ${lastEventMsAgo / 1000} seconds ago")

        // set a new alarm so we can check again in the future
        deps.scheduleAlarm(lastInteractiveEventTimestamp, (checkPeriodHours * 60).toInt(),
            "periodic", restPeriods)
    }
}
