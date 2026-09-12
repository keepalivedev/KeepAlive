package io.keepalive.android

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import io.keepalive.android.receivers.computeEffectiveAlarmStage
import java.util.Calendar

/**
 * The one place that decides what to do with the saved alarm state when
 * something other than the alarm itself asks: a reboot, a package replace, or
 * the watchdog.
 *
 * The state is `last_alarm_stage` and `NextAlarmTimestamp`, both written by
 * [setAlarm] into device-protected storage on every API level. That copy is
 * the one to trust: it is the only copy a [setAlarm] that ran during Direct
 * Boot refreshes, and it is what the Direct Boot path of [doAlertCheck]
 * reads. Reading the stage from one store and the time from another is how
 * the two drift apart after a locked reboot.
 *
 * [stateLock] serialises everything that reads-then-writes that state:
 * [restore] itself, [setAlarm]/[cancelAlarm], and
 * [io.keepalive.android.receivers.AlarmReceiver] around its own delivery. It
 * is reentrant, so callers that already hold it can call [setAlarm] freely.
 */
object AlarmRecovery {

    private const val TAG = "AlarmRecovery"

    val stateLock = Any()

    enum class Source { BOOT, PACKAGE_REPLACED, WATCHDOG }

    // how far past its scheduled time an alarm must be before the watchdog treats
    //  it as dropped rather than merely late. inexact alarms below API 31 may be
    //  delivered up to an hour late by design, and Doze maintenance windows can
    //  stretch further; anything inside this window is left to AlarmManager.
    private const val WATCHDOG_OVERDUE_GRACE_MINUTES = 60

    // fire the replacement alarm almost immediately; the overdue check already
    //  established that it should have gone off
    private const val REARM_DELAY_MINUTES = 1

    // slack on top of the furthest a legitimate alarm could land
    private const val PLAUSIBILITY_SLACK_MINUTES = 24 * 60

    fun restore(context: Context, source: Source) {
        synchronized(stateLock) {

            val prefs = getAppSharedPreferences(context)
            if (!prefs.getBoolean(PrefKeys.ENABLED, false)) {
                Log.d(TAG, "Monitoring is disabled, nothing to restore")
                return
            }

            // State left by a build without the alert_sent marker has to be repaired
            //  before it is acted on: a locked-boot recovery would otherwise re-arm
            //  and overwrite the very timestamp the repair keys on, and the disarm
            //  would be lost for good. The migration is a no-op once done and waits
            //  for an unlocked start; until it has run, so does recovery.
            migrateAlertSentMarker(context)
            val devicePrefs = getDeviceProtectedPreferences(context)
            if (!devicePrefs.getBoolean(PrefKeys.ALERT_SENT_MARKER_MIGRATED, false)) {
                Log.d(TAG, "Deferring recovery until the alert_sent migration can run")
                return
            }

            val savedStage = devicePrefs.getString(PrefKeys.LAST_ALARM_STAGE, "periodic") ?: "periodic"
            val savedTimestamp = devicePrefs.getLong(PrefKeys.NEXT_ALARM_TIMESTAMP, 0L)

            // "alert_sent" means the final alert already went out and monitoring was
            //  deliberately left disarmed (Auto-Restart Monitoring off). Re-arming
            //  would reintroduce the false alerts from issue #181.
            if (savedStage == "alert_sent") {
                if (source == Source.WATCHDOG) {
                    // the watchdog runs every couple of hours; keep the user-visible
                    //  log for the one-off sources
                    Log.d(TAG, "Alert was already sent and monitoring is disarmed, staying disarmed")
                } else {
                    DebugLogger.d(TAG, context.getString(R.string.debug_log_boot_alert_already_sent))
                }
                return
            }

            val checkPeriodMinutes = readCheckPeriodMinutes(prefs)
            val followupMinutes = prefs.getString(PrefKeys.FOLLOWUP_TIME_PERIOD_MINUTES, "60")
                ?.toIntOrNull() ?: 60
            val restPeriods: MutableList<RestPeriod> =
                loadJSONSharedPreference(prefs, PrefKeys.REST_PERIODS)
            val now = System.currentTimeMillis()

            // never set, or further out than this configuration could ever put it
            //  (device clock moved backward, or a legacy out-of-range period). start
            //  a fresh cycle rather than trusting it; the watchdog would otherwise
            //  wait on it indefinitely.
            if (savedTimestamp <= 0L ||
                savedTimestamp > latestPlausibleAlarm(now, checkPeriodMinutes, followupMinutes, restPeriods)
            ) {
                if (savedTimestamp > 0L) {
                    DebugLogger.d(TAG, context.getString(
                        R.string.debug_log_saved_alarm_implausible,
                        getDateTimeStrFromTimestamp(savedTimestamp)))
                } else {
                    Log.d(TAG, "No saved alarm time, starting a fresh cycle")
                }
                setAlarm(context, now, checkPeriodMinutes, "periodic", restPeriods)
                return
            }

            val msUntilSaved = savedTimestamp - now

            if (source == Source.WATCHDOG) {
                val overdueMs = -msUntilSaved
                if (overdueMs < WATCHDOG_OVERDUE_GRACE_MINUTES * 60_000L) {
                    Log.d(TAG, "Next alarm is not overdue past the grace period, nothing to do")
                    return
                }

                // the same rule AlarmReceiver applies to a late delivery: a "final" that
                //  is older than the follow-up period gets a fresh prompt, not an
                //  immediate alert. it has to be applied here because the replacement
                //  alarm carries a fresh timestamp and would otherwise look on time.
                val stage = computeEffectiveAlarmStage(savedStage, overdueMs / 1000, followupMinutes)

                DebugLogger.d(TAG, context.getString(
                    R.string.debug_log_watchdog_rearming,
                    getDateTimeStrFromTimestamp(savedTimestamp)))

                // Re-arm rather than calling doAlertCheck() from a WorkManager thread. A
                //  job holds no foreground-service exemption, so dispatching the alert
                //  from here would fail to start AlertService on API 31+ (see the comment
                //  in ProductionAlertCheckDeps.dispatchFinalAlert) while still recording
                //  "alert_sent". Putting the alarm back means the check runs from
                //  AlarmReceiver, which does hold the exemption, and setAlarm() uses
                //  setAlarmClock() for a final stage, which Doze cannot defer.
                if (stage == "final") {
                    // a force stop cancels the prompt along with the alarm; give the
                    //  user one to answer. both helpers are no-ops if it survived.
                    repostAreYouThere(context, prefs, devicePrefs, now + REARM_DELAY_MINUTES * 60_000L)
                }
                setAlarm(context, now, REARM_DELAY_MINUTES, stage, null)
                return
            }

            // --- reboot or package replace: this process holds the FGS exemption ---

            if (msUntilSaved > 0) {
                // the alarm was cancelled but its schedule is still right: put it back
                //  for the same time rather than running a check early.
                if (savedStage == "final") {
                    // The "Are you there?" prompt did not survive: the process and its
                    //  overlay are gone and the notification was cancelled. Keep the
                    //  escalation on its original clock (a final is exact and ignores
                    //  rest periods on purpose) but give the user the prompt back.
                    DebugLogger.d(TAG, context.getString(
                        R.string.debug_log_prompt_lost_reposting,
                        getDateTimeStrFromTimestamp(savedTimestamp)))
                    repostAreYouThere(context, prefs, devicePrefs, savedTimestamp)
                } else {
                    DebugLogger.d(TAG, context.getString(
                        R.string.debug_log_alarm_not_due_rescheduling,
                        getDateTimeStrFromTimestamp(savedTimestamp)))
                }

                // zero offset from the saved time: with no rest periods setAlarm() adds
                //  exactly that many minutes, so this lands on savedTimestamp
                setAlarm(context, savedTimestamp, 0, savedStage, null)

                // the unlock that let BOOT_COMPLETED run is activity, and doAlertCheck()
                //  is what used to record it on every boot. keep the Direct Boot
                //  activity check from comparing against a stale value later.
                if (source == Source.BOOT && isUserUnlocked(context)) {
                    try {
                        devicePrefs.edit(commit = true) {
                            putLong(PrefKeys.LAST_CHECK_TIMESTAMP, now)
                            putLong(PrefKeys.LAST_ACTIVITY_TIMESTAMP, now)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error recording unlock as activity", e)
                    }
                }
                return
            }

            // overdue: run the check now, with the same late-delivery rule the
            //  receiver applies so a final older than the follow-up re-prompts
            val stage = computeEffectiveAlarmStage(savedStage, -msUntilSaved / 1000, followupMinutes)
            DebugLogger.d(TAG, context.getString(R.string.debug_log_restored_alarm_stage, stage))
            doAlertCheck(context, stage)
        }
    }

    /**
     * One-time repair for installs upgrading from a build that predates the
     * "alert_sent" marker (every release up to 1.5.1). Those builds wrote
     * `last_alarm_stage = "periodic"` after dispatching the alert and, with
     * Auto-Restart off, never re-armed — leaving exactly the shape [restore]
     * treats as a dropped periodic alarm. Without this, the update itself would
     * silently re-arm monitoring for those users, or escalate again.
     *
     * The signal is `LastAlertAt`, which only a real alert writes, at dispatch:
     * an alert at or after the saved alarm time means that alarm was the final
     * that fired. It lives in whichever store the alert ran against, so both are
     * read, and the repair waits for an unlocked start so the credential copy is
     * visible. Must run before any recovery path in the same process start.
     */
    fun migrateAlertSentMarker(context: Context) {
        try {
            if (!isUserUnlocked(context)) return

            val devicePrefs = getDeviceProtectedPreferences(context)
            if (devicePrefs.getBoolean(PrefKeys.ALERT_SENT_MARKER_MIGRATED, false)) return

            val prefs = getAppSharedPreferences(context)
            val savedTimestamp = devicePrefs.getLong(PrefKeys.NEXT_ALARM_TIMESTAMP, 0L)
            val lastAlertAt = maxOf(
                prefs.getLong(PrefKeys.LAST_ALERT_AT, 0L),
                devicePrefs.getLong(PrefKeys.LAST_ALERT_AT, 0L)
            )
            val autoRestart = prefs.getBoolean(PrefKeys.AUTO_RESTART_MONITORING, false)

            val alertWentOut = !autoRestart && savedTimestamp > 0L && lastAlertAt >= savedTimestamp
            if (alertWentOut) {
                DebugLogger.d(TAG, context.getString(R.string.debug_log_alert_sent_migrated))
            }

            devicePrefs.edit(commit = true) {
                if (alertWentOut) putString(PrefKeys.LAST_ALARM_STAGE, "alert_sent")
                putBoolean(PrefKeys.ALERT_SENT_MARKER_MIGRATED, true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error migrating the alert_sent marker", e)
        }
    }

    // the furthest a legitimately scheduled alarm could be from now: a periodic
    //  alarm is the check period counted in active (non-rest) minutes, a final is
    //  that plus the follow-up, and then some slack
    private fun latestPlausibleAlarm(
        now: Long, checkPeriodMinutes: Int, followupMinutes: Int, restPeriods: List<RestPeriod>
    ): Long {
        val latestPeriodic = if (restPeriods.isNotEmpty()) {
            val base = Calendar.getInstance().apply { timeInMillis = now }
            calculateOffsetDateTimeExcludingRestPeriod(base, checkPeriodMinutes, restPeriods[0], "forward")
                .timeInMillis
        } else {
            now + checkPeriodMinutes * 60_000L
        }
        return latestPeriodic + (followupMinutes + PLAUSIBILITY_SLACK_MINUTES) * 60_000L
    }

    // the check period as stored, clamped to the bounds the settings screen
    //  enforces on input - values saved before that validation existed can be
    //  anything, and an unbounded period would put the recovery alarm years out
    private fun readCheckPeriodMinutes(prefs: SharedPreferences): Int {
        val hours = prefs.getString(PrefKeys.TIME_PERIOD_HOURS, "12")?.toFloatOrNull() ?: 12f
        return (hours * 60).toInt().coerceIn(
            AppController.ALARM_MINIMUM_TIME_PERIOD_MINUTES,
            AppController.ALARM_MAXIMUM_TIME_PERIOD_MINUTES
        )
    }

    // same prompt doAlertCheck posts, minus the alarm it would set; the caller
    //  is about to put the original final alarm back
    private fun repostAreYouThere(
        context: Context, prefs: SharedPreferences, devicePrefs: SharedPreferences, deadlineMillis: Long
    ) {
        val minutesRemaining = ((deadlineMillis - System.currentTimeMillis() + 59_999L) / 60_000L)
            .coerceAtLeast(1L)
        val text = String.format(
            context.getString(R.string.initial_check_notification_text), minutesRemaining.toString()
        )
        AlertNotificationHelper(context).sendNotification(
            context.getString(R.string.initial_check_notification_title),
            text,
            AppController.ARE_YOU_THERE_NOTIFICATION_ID
        )

        if (isUserUnlocked(context)) {
            if (prefs.getBoolean(PrefKeys.ARE_YOU_THERE_OVERLAY_ENABLED, true)) {
                AreYouThereOverlay.show(context, text, deadlineMillis)
            }
        } else {
            // same flag the Direct Boot path sets, so the unlock that follows is
            //  treated as the acknowledgement
            try {
                devicePrefs.edit(commit = true) {
                    putBoolean(PrefKeys.DIRECT_BOOT_NOTIFICATION_PENDING, true)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error saving Direct Boot notification flag", e)
            }
        }
    }
}
