package io.keepalive.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.gson.Gson
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkConstructor
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests the watchdog's decision to re-arm, via [AlarmRecovery] with
 * [AlarmRecovery.Source.WATCHDOG].
 *
 * The watchdog must only ever put the alarm back — never run the alert check
 * itself. A WorkManager job holds no foreground-service exemption, so
 * dispatching from here would fail to start AlertService on API 31+ while
 * still writing last_alarm_stage = "alert_sent", losing the alert silently.
 * [setAlarm] and [doAlertCheck] are both mocked so these tests assert on which
 * one the worker reaches for.
 */
@RunWith(RobolectricTestRunner::class)
class MonitoringWatchdogWorkerTest {

    private val appCtx: Context = ApplicationProvider.getApplicationContext()
    private val ALERT_FUNCTIONS_KT = "io.keepalive.android.AlertFunctionsKt"
    private val UTILITY_FUNCTIONS_KT = "io.keepalive.android.UtilityFunctionsKt"

    private fun runWorker() {
        TestListenableWorkerBuilder<MonitoringWatchdogWorker>(appCtx).build().doWork()
    }

    private fun minutesAgo(m: Long) = System.currentTimeMillis() - m * 60_000L
    private fun minutesFromNow(m: Long) = System.currentTimeMillis() + m * 60_000L

    // the saved alarm state lives in device-protected storage; that is the copy
    // setAlarm() keeps current across Direct Boot and the only one recovery reads
    private fun setSavedAlarm(timestamp: Long, stage: String = "periodic") {
        getDeviceProtectedPreferences(appCtx).edit()
            .putLong("NextAlarmTimestamp", timestamp)
            .putString("last_alarm_stage", stage)
            .commit()
    }

    @Before fun setUp() {
        mockkStatic(ALERT_FUNCTIONS_KT)
        mockkStatic(UTILITY_FUNCTIONS_KT)
        every { doAlertCheck(any<Context>(), any()) } returns Unit
        every { setAlarm(any(), any(), any(), any(), any()) } returns Unit
        mockkObject(AreYouThereOverlay)
        mockkConstructor(AlertNotificationHelper::class)
        every { AreYouThereOverlay.show(any(), any(), any()) } returns Unit
        every { anyConstructed<AlertNotificationHelper>().sendNotification(any(), any(), any(), any()) } returns Unit
        getAppSharedPreferences(appCtx).edit()
            .putBoolean("enabled", true)
            .putString("followup_time_period_minutes", "60")
            .commit()
    }

    @After fun tearDown() {
        unmockkStatic(ALERT_FUNCTIONS_KT)
        unmockkStatic(UTILITY_FUNCTIONS_KT)
        unmockkObject(AreYouThereOverlay)
        unmockkConstructor(AlertNotificationHelper::class)
    }

    @Test fun `does nothing when monitoring is disabled`() {
        getAppSharedPreferences(appCtx).edit().putBoolean("enabled", false).commit()
        setSavedAlarm(minutesAgo(240))

        runWorker()

        verify(exactly = 0) { setAlarm(any(), any(), any(), any(), any()) }
    }

    @Test fun `does nothing while the next alarm is still in the future`() {
        setSavedAlarm(minutesFromNow(60))

        runWorker()

        verify(exactly = 0) { setAlarm(any(), any(), any(), any(), any()) }
    }

    @Test fun `does nothing while the alarm is only slightly overdue`() {
        // Inexact alarms are delivered up to an hour late by design below API 31,
        // and Doze maintenance windows stretch further. Re-arming one that is
        // merely running behind would be wasted work and a false "never fired"
        // log line.
        setSavedAlarm(minutesAgo(30))

        runWorker()

        verify(exactly = 0) { setAlarm(any(), any(), any(), any(), any()) }
    }

    @Test fun `re-arms once the alarm is overdue past the grace period`() {
        // The failure this exists for: the alarm was dropped by the OS and
        // nothing noticed, so its scheduled time slid into the past and stayed.
        setSavedAlarm(minutesAgo(120))

        runWorker()

        verify(exactly = 1) { setAlarm(any(), any(), any(), "periodic", any()) }
    }

    @Test fun `re-arms a final that is inside the follow-up window as final`() {
        // Overdue by more than the grace but less than the follow-up period: the
        // user's acknowledgement window has not expired, so it is still a final.
        getAppSharedPreferences(appCtx).edit()
            .putString("followup_time_period_minutes", "180").commit()
        setSavedAlarm(minutesAgo(90), stage = "final")

        runWorker()

        verify(exactly = 1) { setAlarm(any(), any(), any(), "final", any()) }
    }

    @Test fun `re-arming a final re-posts the prompt`() {
        // The re-armed final fires a minute later. A force stop cancels the
        // notification along with the alarm, so the user would otherwise have
        // nothing to answer; the helper is a no-op if the prompt survived.
        getAppSharedPreferences(appCtx).edit()
            .putString("followup_time_period_minutes", "180").commit()
        setSavedAlarm(minutesAgo(90), stage = "final")

        runWorker()

        verify(exactly = 1) { anyConstructed<AlertNotificationHelper>().sendNotification(any(), any(), any(), any()) }
        // the full-screen prompt reads the saved deadline when it opens, so the
        // replacement alarm has to be persisted before the notification goes out
        verifyOrder {
            setAlarm(any(), any(), any(), "final", any())
            anyConstructed<AlertNotificationHelper>().sendNotification(any(), any(), any(), any())
        }
    }

    @Test fun `downgrades a stale final to periodic so the user is re-prompted`() {
        // Same rule AlarmReceiver applies to a late delivery (AlarmStageRule): a
        // final older than the follow-up period gets a fresh "Are you there?"
        // rather than an immediate real alert. The replacement alarm carries a
        // fresh timestamp, so the rule has to be applied before re-arming or the
        // receiver would see delay ≈ 0 and fire it as final.
        setSavedAlarm(minutesAgo(180), stage = "final")

        runWorker()

        verify(exactly = 1) { setAlarm(any(), any(), any(), "periodic", any()) }
        verify(exactly = 0) { setAlarm(any(), any(), any(), "final", any()) }
        verify(exactly = 0) { anyConstructed<AlertNotificationHelper>().sendNotification(any(), any(), any(), any()) }
    }

    @Test fun `never runs the alert check from the worker thread`() {
        // Regression guard. doAlertCheck here would reach dispatchFinalAlert on a
        // thread with no FGS exemption: AlertService fails to start on API 31+,
        // the throw is swallowed, and "alert_sent" is recorded anyway - the alert
        // is lost and monitoring stays disarmed for good.
        setSavedAlarm(minutesAgo(120), stage = "final")

        runWorker()

        verify(exactly = 0) { doAlertCheck(any<Context>(), any()) }
    }

    @Test fun `stays disarmed after an alert was already sent`() {
        // "alert_sent" means Auto-Restart Monitoring is off and the disarm was
        // deliberate - re-arming here would reintroduce issue #181.
        setSavedAlarm(minutesAgo(120), stage = "alert_sent")

        runWorker()

        verify(exactly = 0) { setAlarm(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { doAlertCheck(any<Context>(), any()) }
    }

    @Test fun `ignores a stale credential-store timestamp`() {
        // After a locked reboot, setAlarm() runs while credential storage is
        // unavailable, so only the device-protected copy is current. A watchdog
        // that read the credential copy would see the pre-reboot time, decide the
        // alarm was dropped, and replace a 60-minute final with a 1-minute one.
        getAppSharedPreferences(appCtx).edit()
            .putLong("NextAlarmTimestamp", minutesAgo(600)).commit()
        setSavedAlarm(minutesFromNow(45), stage = "final")

        runWorker()

        verify(exactly = 0) { setAlarm(any(), any(), any(), any(), any()) }
    }

    @Test fun `a final set by a long follow-up period is not treated as implausible`() {
        // The follow-up may legally exceed the check period (both allow up to 7
        // days). A 48h follow-up puts the final ~46h out on a 12h check; resetting
        // it would kill the escalation and the real alert could never go out.
        getAppSharedPreferences(appCtx).edit()
            .putString("time_period_hours", "12")
            .putString("followup_time_period_minutes", "2880")
            .commit()
        setSavedAlarm(minutesFromNow(46 * 60), stage = "final")

        runWorker()

        verify(exactly = 0) { setAlarm(any(), any(), any(), any(), any()) }
    }

    @Test fun `a multi-day period stretched by a nightly rest is not treated as implausible`() {
        // Rest minutes are skipped on every day of the period, so a 3-day check
        // with 23:00-07:00 rest legitimately lands ~108h out. Resetting it would
        // re-produce the same time and loop forever, and the check would never run.
        getAppSharedPreferences(appCtx).edit()
            .putString("time_period_hours", "72")
            .putString("REST_PERIODS", Gson().toJson(mutableListOf(RestPeriod(23, 0, 7, 0))))
            .commit()
        setSavedAlarm(minutesFromNow(108 * 60))

        runWorker()

        verify(exactly = 0) { setAlarm(any(), any(), any(), any(), any()) }
    }

    @Test fun `a never-set alarm time starts a fresh cycle`() {
        getDeviceProtectedPreferences(appCtx).edit().remove("NextAlarmTimestamp").commit()

        runWorker()

        verify(exactly = 1) { setAlarm(any(), any(), any(), "periodic", any()) }
    }

    @Test fun `starts a fresh cycle when the saved time is implausibly far out`() {
        // A saved time the user's own period could never produce (clock moved
        // backward, or a legacy out-of-range period) would otherwise leave the
        // watchdog waiting on it indefinitely.
        getAppSharedPreferences(appCtx).edit().putString("time_period_hours", "12").commit()
        setSavedAlarm(minutesFromNow(5 * 24 * 60))

        runWorker()

        verify(exactly = 1) { setAlarm(any(), any(), any(), "periodic", any()) }
    }
}
