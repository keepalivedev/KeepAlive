package io.keepalive.android

import android.app.usage.UsageEvents
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.core.content.edit

/**
 * All side-effecting operations [doAlertCheck] needs.
 *
 * Production code passes [ProductionAlertCheckDeps]; tests pass a fake that
 * records calls and returns canned responses. This lets the decision engine
 * be tested without a real device — set up the fake's state, call
 * [doAlertCheck], and assert on which methods were invoked and what prefs
 * were written.
 *
 * Pure helpers ([isWithinRestPeriod], [calculateOffsetDateTimeExcludingRestPeriod],
 * [getDateTimeStrFromTimestamp], [loadJSONSharedPreference]) are deliberately
 * NOT on this interface — they're deterministic and tests can call them directly.
 */
interface AlertCheckDeps {

    // --- Clock & device state ---

    /** Current wall-clock time in ms since epoch. Overridable for deterministic tests. */
    fun now(): Long

    /** Whether the user has unlocked the device since boot (Direct Boot transition). */
    fun isUserUnlocked(): Boolean

    /**
     * Resolve an Android string resource with format args. Used by the
     * decision engine when building DebugLogger messages. Tests can return
     * any canned string — the engine never branches on the content.
     */
    fun getString(resId: Int, vararg args: Any): String

    // --- Storage ---

    /** Credential-encrypted preferences (user settings: check period, contacts, etc.). */
    fun credentialPrefs(): SharedPreferences

    /**
     * Device-protected preferences (runtime state for Direct Boot recovery).
     * Returns null on API < N where device-protected storage doesn't exist.
     */
    fun devicePrefs(): SharedPreferences?

    // --- Queries ---

    /** Latest lock/unlock or app-foreground event since [startTimestamp], or null. */
    fun getLastDeviceActivity(startTimestamp: Long, monitoredApps: List<String>): UsageEvents.Event?

    // --- Actions ---

    /** Schedule the next [receivers.AlarmReceiver] firing via AlarmManager. */
    fun scheduleAlarm(baseTimestamp: Long, periodMinutes: Int, stage: String, restPeriods: MutableList<RestPeriod>?)

    /** Post the "Are you there?" user notification. */
    fun showAreYouThereNotification(followupPeriodMinutes: Int)

    /**
     * Show the over-other-apps full-screen "Are you there?" overlay.
     * Takes the raw follow-up minutes so the deps impl can own the string
     * resource formatting and tests can assert on the integer arg directly.
     */
    fun showAreYouThereOverlay(followupPeriodMinutes: Int)

    /** Cancel the "Are you there?" prompt and reset monitoring to periodic. */
    fun acknowledgeAreYouThere()

    /** Dispatch the real alert via AlertService, reset the alarm stage, and optionally restart periodic. */
    fun dispatchFinalAlert(prefs: SharedPreferences, nowTimestamp: Long, checkPeriodHours: Float, restPeriods: MutableList<RestPeriod>)
}


/**
 * Production implementation. Thin wrappers around the existing top-level
 * functions — should be byte-for-byte equivalent to the pre-refactor
 * [doAlertCheck] behavior.
 */
class ProductionAlertCheckDeps(private val context: Context) : AlertCheckDeps {

    override fun now(): Long = System.currentTimeMillis()

    override fun isUserUnlocked(): Boolean = io.keepalive.android.isUserUnlocked(context)

    override fun getString(resId: Int, vararg args: Any): String = context.getString(resId, *args)

    override fun credentialPrefs(): SharedPreferences = getEncryptedSharedPreferences(context)

    override fun devicePrefs(): SharedPreferences? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) getDeviceProtectedPreferences(context) else null

    override fun getLastDeviceActivity(startTimestamp: Long, monitoredApps: List<String>): UsageEvents.Event? =
        io.keepalive.android.getLastDeviceActivity(context, startTimestamp, monitoredApps)

    override fun scheduleAlarm(baseTimestamp: Long, periodMinutes: Int, stage: String, restPeriods: MutableList<RestPeriod>?) {
        setAlarm(context, baseTimestamp, periodMinutes, stage, restPeriods)
    }

    override fun showAreYouThereNotification(followupPeriodMinutes: Int) {
        AlertNotificationHelper(context).sendNotification(
            context.getString(R.string.initial_check_notification_title),
            String.format(
                context.getString(R.string.initial_check_notification_text),
                followupPeriodMinutes.toString()
            ),
            AppController.ARE_YOU_THERE_NOTIFICATION_ID
        )
    }

    override fun showAreYouThereOverlay(followupPeriodMinutes: Int) {
        AreYouThereOverlay.show(
            context,
            String.format(
                context.getString(R.string.initial_check_notification_text),
                followupPeriodMinutes.toString()
            )
        )
    }

    override fun acknowledgeAreYouThere() {
        AcknowledgeAreYouThere.acknowledge(context)
    }

    override fun dispatchFinalAlert(
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                getDeviceProtectedPreferences(context).edit(commit = true) {
                    putString("last_alarm_stage", "periodic")
                }
            } catch (e: Exception) {
                Log.e("doAlertCheck", "Error resetting alarm stage after sending alert", e)
            }
        }

        if (prefs.getBoolean("auto_restart_monitoring", false)) {
            DebugLogger.d("doAlertCheck", context.getString(R.string.debug_log_auto_restart_monitoring))
            scheduleAlarm(nowTimestamp, (checkPeriodHours * 60).toInt(), "periodic", restPeriods)
        }
    }
}
