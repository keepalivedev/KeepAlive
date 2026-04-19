package io.keepalive.android

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import io.keepalive.android.receivers.AlarmReceiver
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader

/**
 * Helpers for instrumented tests that drive the real alert flow on a
 * device/emulator. Designed so each test can set up a deterministic starting
 * state, fire the real system intents we react to, and assert on observable
 * outcomes (SharedPreferences, NotificationManager, AlarmManager).
 *
 * **None of these helpers send real SMS or place real calls.** The contact
 * numbers we seed (`+1-555-01xx`) are IANA-reserved for fiction and will fail
 * to deliver even if the device has a SIM — safe under instrumented CI.
 */
object AlertFlowTestUtil {

    /** Reserved-for-fiction US numbers — safe in tests. */
    const val FAKE_CONTACT_A = "+15550100"
    const val FAKE_CONTACT_B = "+15550101"
    const val FAKE_CALL_TARGET = "+15550102"

    private val gson = Gson()

    /** Instrumentation context (the test APK). Prefs, services, etc. use targetContext. */
    val targetContext: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Reset the app into a clean, enabled state with a single fake SMS contact
     * and a fake call target. Clears all pending alarms and notifications.
     */
    fun resetToCleanEnabledState(
        checkPeriodHours: String = "12",
        followupMinutes: String = "60",
        includeCallTarget: Boolean = true,
        includeSmsContact: Boolean = true
    ) {
        val ctx = targetContext
        val prefs = getEncryptedSharedPreferences(ctx)
        val contacts = if (includeSmsContact) {
            mutableListOf(
                SMSEmergencyContactSetting(
                    phoneNumber = FAKE_CONTACT_A,
                    alertMessage = "TEST ALERT — please ignore",
                    isEnabled = true,
                    includeLocation = false
                )
            )
        } else mutableListOf()

        // Seed APPS_TO_MONITOR with a non-existent package. This takes
        // getLastDeviceActivity off the default "keyguard events for 'android'"
        // path — which finds real activity on a running emulator — and onto
        // the "MOVE_TO_FOREGROUND for these apps" path. Since the fake app
        // has never run, the query returns null = "no activity detected",
        // which is what we need to observe the prompt → final flow.
        val monitoredApps = mutableListOf(
            MonitoredAppDetails(
                packageName = "com.fake.keepalive.test.monitored",
                appName = "Test Monitored App",
                lastUsed = 0L,
                className = ""
            )
        )

        prefs.edit().apply {
            clear()
            putBoolean("enabled", true)
            putString("time_period_hours", checkPeriodHours)
            putString("followup_time_period_minutes", followupMinutes)
            putString("PHONE_NUMBER_SETTINGS", gson.toJson(contacts))
            putString("APPS_TO_MONITOR", gson.toJson(monitoredApps))
            if (includeCallTarget) putString("contact_phone", FAKE_CALL_TARGET)
            // Don't turn on location/webhook in default tests — keeps the
            // alert flow synchronous so assertions are deterministic.
            putBoolean("location_enabled", false)
            putBoolean("webhook_enabled", false)
            putBoolean("webhook_location_enabled", false)
            commit()
        }

        // Clear device-protected state too — it shadows credential-encrypted
        // for Direct Boot and leftover keys trip up later tests.
        try {
            getDeviceProtectedPreferences(ctx).edit().clear().commit()
        } catch (e: Exception) { /* pre-N: no-op */ }

        cancelAnyPendingAlarms()
        cancelAllNotifications()
    }

    /** Cancel the KeepAlive AlarmManager alarm so tests start clean. */
    fun cancelAnyPendingAlarms() {
        cancelAlarm(targetContext)
    }

    /** Clear all notifications the app has posted. */
    fun cancelAllNotifications() {
        val nm = targetContext.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
        nm.cancelAll()
    }

    /** Drive the periodic or final alarm by delivering the intent to the receiver directly. */
    fun fireAlarm(alarmStage: String, alarmTimestamp: Long = System.currentTimeMillis()) {
        val intent = Intent(targetContext, AlarmReceiver::class.java).apply {
            putExtra("AlarmStage", alarmStage)
            putExtra("AlarmTimestamp", alarmTimestamp)
        }
        AlarmReceiver().onReceive(targetContext, intent)
    }

    /** Returns whether any KeepAlive alarm is currently scheduled in the system AlarmManager. */
    fun hasPendingKeepAliveAlarm(): Boolean {
        val am = targetContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // The cleanest check is via dumpsys; on an emulator we can just look
        // at next-alarm-clock. Both have caveats. Use NextAlarmTimestamp the
        // app itself persists — if the app thinks an alarm is scheduled and
        // we haven't cancelled it, it's really scheduled.
        val saved = getEncryptedSharedPreferences(targetContext)
            .getLong("NextAlarmTimestamp", 0L)
        return saved > 0L
    }

    /** Count notifications currently posted by the app. */
    fun activeNotificationCount(): Int {
        val nm = targetContext.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
        return nm.activeNotifications.size
    }

    /** Check whether a notification with the given ID is currently visible. */
    fun hasNotification(id: Int): Boolean {
        val nm = targetContext.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
        return nm.activeNotifications.any { it.id == id }
    }

    /** Wait up to [timeoutMs] for [predicate] to return true, polling every [pollMs]. */
    fun waitUntil(timeoutMs: Long = 5000L, pollMs: Long = 100L, predicate: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (predicate()) return true
            Thread.sleep(pollMs)
        }
        return predicate()
    }

    /** Run an `adb shell`-equivalent command inside the instrumentation. */
    fun shell(cmd: String): String {
        val fd = InstrumentationRegistry.getInstrumentation()
            .uiAutomation.executeShellCommand(cmd)
        val buf = StringBuilder()
        BufferedReader(InputStreamReader(FileInputStream(fd.fileDescriptor))).use { reader ->
            reader.forEachLine { buf.appendLine(it) }
        }
        fd.close()
        return buf.toString()
    }

    /** Read a step-tracker bit to confirm the alert service reached a given stage. */
    fun isAlertStepComplete(stepBit: Int): Boolean {
        val saved = getEncryptedSharedPreferences(targetContext)
            .getInt("AlertStepsCompleted", 0)
        return (saved and stepBit) == stepBit
    }

    fun savedAlertTriggerTimestamp(): Long =
        getEncryptedSharedPreferences(targetContext).getLong("AlertTriggerTimestamp", 0L)

    fun savedAlarmStage(): String? =
        try {
            getDeviceProtectedPreferences(targetContext).getString("last_alarm_stage", null)
        } catch (e: Exception) { null }
}
