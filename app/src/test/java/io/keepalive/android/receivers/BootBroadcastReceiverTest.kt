package io.keepalive.android.receivers

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import io.keepalive.android.AcknowledgeAreYouThere
import io.keepalive.android.AlertNotificationHelper
import io.keepalive.android.AreYouThereOverlay
import io.keepalive.android.RestPeriod
import com.google.gson.Gson
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.keepalive.android.doAlertCheck
import io.keepalive.android.getDeviceProtectedPreferences
import io.keepalive.android.getAppSharedPreferences
import io.keepalive.android.isUserUnlocked
import io.keepalive.android.setAlarm
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests the boot-intent branching that decides whether to acknowledge a
 * pending "Are you there?" prompt or run a fresh alert check after reboot.
 *
 * Mocks out [doAlertCheck] (covered elsewhere), [AcknowledgeAreYouThere] (same),
 * and [isUserUnlocked] (stateful in Robolectric but brittle to toggle). We
 * only assert on *which* of these gets called per boot scenario.
 */
@RunWith(RobolectricTestRunner::class)
class BootBroadcastReceiverTest {

    private val appCtx: Context = ApplicationProvider.getApplicationContext()
    private val ALERT_FUNCTIONS_KT = "io.keepalive.android.AlertFunctionsKt"
    private val UTILITY_FUNCTIONS_KT = "io.keepalive.android.UtilityFunctionsKt"

    @Before fun setUp() {
        mockkStatic(ALERT_FUNCTIONS_KT)
        mockkStatic(UTILITY_FUNCTIONS_KT)
        mockkObject(AcknowledgeAreYouThere)
        mockkObject(AreYouThereOverlay)
        mockkConstructor(AlertNotificationHelper::class)
        every { doAlertCheck(any<Context>(), any()) } returns Unit
        every { AcknowledgeAreYouThere.acknowledge(any()) } returns Unit
        every { AreYouThereOverlay.show(any(), any(), any()) } returns Unit
        every { anyConstructed<AlertNotificationHelper>().sendNotification(any(), any(), any(), any()) } returns Unit
        // Default: app enabled, user unlocked
        every { isUserUnlocked(any()) } returns true
        every { setAlarm(any(), any(), any(), any(), any()) } returns Unit
        getAppSharedPreferences(appCtx).edit()
            .putBoolean("enabled", true)
            .commit()
        getDeviceProtectedPreferences(appCtx).edit()
            .putString("last_alarm_stage", "periodic")
            .putBoolean("direct_boot_notification_pending", false)
            // default to an alarm that was already due, so recovery runs the
            // check the older tests assert on rather than rescheduling
            .putLong("NextAlarmTimestamp", System.currentTimeMillis() - 2 * 60 * 60_000L)
            // the migration gate is not what these tests are about
            .putBoolean("alert_sent_marker_migrated", true)
            .commit()
    }

    // Recovery must not act on state a build without the alert_sent marker left
    // behind: a locked-boot recovery would re-arm and overwrite the timestamp the
    // migration keys on, and a deliberately disarmed user would be re-armed.

    @Test fun `recovery waits for the alert_sent migration while locked`() {
        every { isUserUnlocked(any()) } returns false
        val legacyFinal = System.currentTimeMillis() - 3 * 60 * 60_000L
        getDeviceProtectedPreferences(appCtx).edit()
            .remove("alert_sent_marker_migrated")
            .putString("last_alarm_stage", "periodic")
            .putLong("NextAlarmTimestamp", legacyFinal)
            .commit()

        BootBroadcastReceiver().onReceive(
            appCtx, Intent("android.intent.action.LOCKED_BOOT_COMPLETED"))

        verify(exactly = 0) { setAlarm(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { doAlertCheck(any<Context>(), any()) }
        assertFalse(getDeviceProtectedPreferences(appCtx).getBoolean("alert_sent_marker_migrated", false))
        assertEquals(legacyFinal, getDeviceProtectedPreferences(appCtx).getLong("NextAlarmTimestamp", 0L))
    }

    @Test fun `recovery runs the alert_sent migration itself once unlocked`() {
        // AppController.onCreate only runs once per process; if that was a locked
        // start, the BOOT_COMPLETED that follows unlock is the first chance.
        val legacyFinal = System.currentTimeMillis() - 3 * 60 * 60_000L
        getDeviceProtectedPreferences(appCtx).edit()
            .remove("alert_sent_marker_migrated")
            .putString("last_alarm_stage", "periodic")
            .putLong("NextAlarmTimestamp", legacyFinal)
            .commit()
        getAppSharedPreferences(appCtx).edit()
            .putBoolean("auto_restart_monitoring", false)
            .putLong("LastAlertAt", legacyFinal + 5_000L)
            .commit()

        BootBroadcastReceiver().onReceive(appCtx, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertEquals("alert_sent", getDeviceProtectedPreferences(appCtx).getString("last_alarm_stage", null))
        verify(exactly = 0) { setAlarm(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { doAlertCheck(any<Context>(), any()) }
    }

    @After fun tearDown() {
        unmockkStatic(ALERT_FUNCTIONS_KT)
        unmockkStatic(UTILITY_FUNCTIONS_KT)
        unmockkObject(AcknowledgeAreYouThere)
        unmockkObject(AreYouThereOverlay)
        unmockkConstructor(AlertNotificationHelper::class)
    }

    @Test fun `receiver does nothing when app is disabled`() {
        getAppSharedPreferences(appCtx).edit().putBoolean("enabled", false).commit()
        val intent = Intent(Intent.ACTION_BOOT_COMPLETED)

        BootBroadcastReceiver().onReceive(appCtx, intent)

        verify(exactly = 0) { doAlertCheck(any<Context>(), any()) }
        verify(exactly = 0) { AcknowledgeAreYouThere.acknowledge(any()) }
    }

    @Test fun `receiver ignores unrelated intents`() {
        BootBroadcastReceiver().onReceive(appCtx, Intent("something.else"))

        verify(exactly = 0) { doAlertCheck(any<Context>(), any()) }
        verify(exactly = 0) { AcknowledgeAreYouThere.acknowledge(any()) }
    }

    @Test fun `BOOT_COMPLETED with pending flag triggers acknowledge`() {
        // User just unlocked — the unlock IS the acknowledgement. We must NOT
        // re-run doAlertCheck with stage=final (which would otherwise alert).
        getDeviceProtectedPreferences(appCtx).edit()
            .putBoolean("direct_boot_notification_pending", true)
            .putString("last_alarm_stage", "final")
            .commit()

        BootBroadcastReceiver().onReceive(appCtx, Intent(Intent.ACTION_BOOT_COMPLETED))

        verify(exactly = 1) { AcknowledgeAreYouThere.acknowledge(any()) }
        verify(exactly = 0) { doAlertCheck(any<Context>(), any()) }
    }

    @Test fun `BOOT_COMPLETED without pending flag runs doAlertCheck with saved stage`() {
        getDeviceProtectedPreferences(appCtx).edit()
            .putBoolean("direct_boot_notification_pending", false)
            .putString("last_alarm_stage", "periodic")
            .commit()

        BootBroadcastReceiver().onReceive(appCtx, Intent(Intent.ACTION_BOOT_COMPLETED))

        verify(exactly = 1) { doAlertCheck(any<Context>(), "periodic") }
        verify(exactly = 0) { AcknowledgeAreYouThere.acknowledge(any()) }
    }

    @Test fun `BOOT_COMPLETED with an overdue final inside the follow-up runs the final check`() {
        // An escalation already in flight completes on its own clock. The prompt
        // did not survive the reboot, but the acknowledgement window has not
        // expired either, and this is what the OS delivering the alarm late would do.
        getDeviceProtectedPreferences(appCtx).edit()
            .putString("last_alarm_stage", "final")
            .putLong("NextAlarmTimestamp", System.currentTimeMillis() - 10 * 60_000L)
            .commit()

        BootBroadcastReceiver().onReceive(appCtx, Intent(Intent.ACTION_BOOT_COMPLETED))

        verify(exactly = 1) { doAlertCheck(any<Context>(), "final") }
    }

    @Test fun `BOOT_COMPLETED with a final overdue past the follow-up re-prompts instead`() {
        // Same rule AlarmReceiver applies to a late delivery: a final older than
        // the follow-up period gets a fresh "Are you there?" rather than an
        // immediate alert against a prompt the user never saw.
        getAppSharedPreferences(appCtx).edit().putString("followup_time_period_minutes", "60").commit()
        getDeviceProtectedPreferences(appCtx).edit()
            .putString("last_alarm_stage", "final")
            .putLong("NextAlarmTimestamp", System.currentTimeMillis() - 3 * 60 * 60_000L)
            .commit()

        BootBroadcastReceiver().onReceive(appCtx, Intent(Intent.ACTION_BOOT_COMPLETED))

        verify(exactly = 1) { doAlertCheck(any<Context>(), "periodic") }
        verify(exactly = 0) { doAlertCheck(any<Context>(), "final") }
    }

    @Test fun `BOOT_COMPLETED with a pending final puts it back and re-posts the prompt`() {
        // The prompt does not survive a reboot (notification cancelled, overlay
        // died with the process) but the final's schedule is still right and a
        // final deliberately ignores rest periods. Keep the clock, give the user
        // something to answer.
        val dueAt = System.currentTimeMillis() + 30 * 60_000L
        getDeviceProtectedPreferences(appCtx).edit()
            .putString("last_alarm_stage", "final")
            .putLong("NextAlarmTimestamp", dueAt)
            .commit()

        BootBroadcastReceiver().onReceive(appCtx, Intent(Intent.ACTION_BOOT_COMPLETED))

        verify(exactly = 1) { setAlarm(any(), dueAt, 0, "final", null) }
        verify(exactly = 1) { anyConstructed<AlertNotificationHelper>().sendNotification(any(), any(), any(), any()) }
        verify(exactly = 1) { AreYouThereOverlay.show(any(), any(), dueAt) }
        verify(exactly = 0) { doAlertCheck(any<Context>(), any()) }
    }

    @Test fun `LOCKED_BOOT_COMPLETED with a pending final flags the re-posted prompt for Direct Boot`() {
        // While locked the overlay can't be drawn; the notification is posted and
        // the same flag the Direct Boot path uses is set, so the unlock that
        // follows counts as the acknowledgement.
        every { isUserUnlocked(any()) } returns false
        val dueAt = System.currentTimeMillis() + 30 * 60_000L
        getDeviceProtectedPreferences(appCtx).edit()
            .putString("last_alarm_stage", "final")
            .putLong("NextAlarmTimestamp", dueAt)
            .putBoolean("direct_boot_notification_pending", false)
            .commit()

        BootBroadcastReceiver().onReceive(
            appCtx, Intent("android.intent.action.LOCKED_BOOT_COMPLETED"))

        verify(exactly = 1) { setAlarm(any(), dueAt, 0, "final", null) }
        verify(exactly = 0) { AreYouThereOverlay.show(any(), any(), any()) }
        assertTrue(getDeviceProtectedPreferences(appCtx)
            .getBoolean("direct_boot_notification_pending", false))
    }

    // Replacing the package cancels every alarm the app registered. Without this
    // the app stayed disarmed until the next reboot or a manual restart, while the
    // main screen still showed a countdown from the stale saved timestamp.
    @Test fun `MY_PACKAGE_REPLACED runs doAlertCheck for an overdue periodic alarm`() {
        BootBroadcastReceiver().onReceive(
            appCtx, Intent("android.intent.action.MY_PACKAGE_REPLACED"))

        verify(exactly = 1) { doAlertCheck(any<Context>(), "periodic") }
    }

    @Test fun `MY_PACKAGE_REPLACED never acknowledges`() {
        // BOOT_COMPLETED may treat a pending prompt as answered because the user
        // unlocked the device. An unattended Play Store update is proof of
        // nothing, so it must not clear a pending "Are you there?" escalation.
        getDeviceProtectedPreferences(appCtx).edit()
            .putBoolean("direct_boot_notification_pending", true)
            .putString("last_alarm_stage", "final")
            .commit()

        BootBroadcastReceiver().onReceive(
            appCtx, Intent("android.intent.action.MY_PACKAGE_REPLACED"))

        verify(exactly = 0) { AcknowledgeAreYouThere.acknowledge(any()) }
    }

    @Test fun `MY_PACKAGE_REPLACED with a pending final puts it back and re-posts the prompt`() {
        val dueAt = System.currentTimeMillis() + 30 * 60_000L
        getDeviceProtectedPreferences(appCtx).edit()
            .putString("last_alarm_stage", "final")
            .putLong("NextAlarmTimestamp", dueAt)
            .commit()

        BootBroadcastReceiver().onReceive(
            appCtx, Intent("android.intent.action.MY_PACKAGE_REPLACED"))

        verify(exactly = 1) { setAlarm(any(), dueAt, 0, "final", null) }
        verify(exactly = 1) { anyConstructed<AlertNotificationHelper>().sendNotification(any(), any(), any(), any()) }
        verify(exactly = 0) { doAlertCheck(any<Context>(), any()) }
    }

    @Test fun `implausibly distant saved alarm time starts a fresh cycle`() {
        // A saved time this configuration could never produce is corrupt (clock
        // moved backward, or a legacy out-of-range period). Rescheduling it would
        // strand monitoring - the watchdog also waits while the saved time is in
        // the future - and doAlertCheck's Direct Boot path would re-read and
        // reschedule it, so recovery writes a sane alarm itself.
        getAppSharedPreferences(appCtx).edit().putString("time_period_hours", "12").commit()
        getDeviceProtectedPreferences(appCtx).edit()
            .putLong("NextAlarmTimestamp", System.currentTimeMillis() + 5L * 24 * 60 * 60_000L)
            .commit()

        BootBroadcastReceiver().onReceive(appCtx, Intent(Intent.ACTION_BOOT_COMPLETED))

        verify(exactly = 1) { setAlarm(any(), any(), any(), "periodic", any()) }
        verify(exactly = 0) { doAlertCheck(any<Context>(), any()) }
    }

    @Test fun `a saved alarm time within the user's own period is still rescheduled`() {
        // Guards the bound from being tightened into uselessness: it is relative to
        // the configured period, so a 7-day period legitimately schedules days out.
        getAppSharedPreferences(appCtx).edit().putString("time_period_hours", "168").commit()
        val sixDaysOut = System.currentTimeMillis() + 6L * 24 * 60 * 60_000L
        getDeviceProtectedPreferences(appCtx).edit()
            .putLong("NextAlarmTimestamp", sixDaysOut)
            .commit()

        BootBroadcastReceiver().onReceive(appCtx, Intent(Intent.ACTION_BOOT_COMPLETED))

        verify(exactly = 1) { setAlarm(any(), sixDaysOut, 0, "periodic", null) }
        verify(exactly = 0) { doAlertCheck(any<Context>(), any()) }
    }

    @Test fun `a period stretched by a nightly rest is still rescheduled`() {
        // Rest minutes are skipped on every day of the period, so a 3-day check
        // with 23:00-07:00 rest legitimately lands ~108h out - well past a naive
        // period + 24h bound.
        getAppSharedPreferences(appCtx).edit()
            .putString("time_period_hours", "72")
            .putString("REST_PERIODS", Gson().toJson(mutableListOf(RestPeriod(23, 0, 7, 0))))
            .commit()
        val stretched = System.currentTimeMillis() + 108L * 60 * 60_000L
        getDeviceProtectedPreferences(appCtx).edit()
            .putLong("NextAlarmTimestamp", stretched)
            .commit()

        BootBroadcastReceiver().onReceive(appCtx, Intent(Intent.ACTION_BOOT_COMPLETED))

        verify(exactly = 1) { setAlarm(any(), stretched, 0, "periodic", null) }
    }

    @Test fun `a never-set alarm time starts a fresh cycle`() {
        getDeviceProtectedPreferences(appCtx).edit().remove("NextAlarmTimestamp").commit()

        BootBroadcastReceiver().onReceive(appCtx, Intent(Intent.ACTION_BOOT_COMPLETED))

        verify(exactly = 1) { setAlarm(any(), any(), any(), "periodic", any()) }
        verify(exactly = 0) { doAlertCheck(any<Context>(), any()) }
    }

    @Test fun `BOOT_COMPLETED reschedules a periodic alarm that is not due and records the unlock`() {
        // A reboot cancels the alarm without making its schedule wrong, so it goes
        // back for the same time. The unlock that let BOOT_COMPLETED run is
        // activity, and doAlertCheck() used to record it on every boot; keep the
        // Direct Boot activity check from comparing against a stale value later.
        val before = System.currentTimeMillis()
        val dueAt = before + 30 * 60_000L
        getDeviceProtectedPreferences(appCtx).edit()
            .putLong("NextAlarmTimestamp", dueAt)
            .putLong("last_activity_timestamp", 0L)
            .commit()

        BootBroadcastReceiver().onReceive(appCtx, Intent(Intent.ACTION_BOOT_COMPLETED))

        verify(exactly = 1) { setAlarm(any(), dueAt, 0, "periodic", null) }
        verify(exactly = 0) { doAlertCheck(any<Context>(), any()) }
        assertTrue(getDeviceProtectedPreferences(appCtx)
            .getLong("last_activity_timestamp", 0L) >= before)
    }

    @Test fun `a locked reschedule does not count as activity`() {
        every { isUserUnlocked(any()) } returns false
        val dueAt = System.currentTimeMillis() + 30 * 60_000L
        getDeviceProtectedPreferences(appCtx).edit()
            .putLong("NextAlarmTimestamp", dueAt)
            .putLong("last_activity_timestamp", 0L)
            .commit()

        BootBroadcastReceiver().onReceive(
            appCtx, Intent("android.intent.action.LOCKED_BOOT_COMPLETED"))

        verify(exactly = 1) { setAlarm(any(), dueAt, 0, "periodic", null) }
        assertEquals(0L, getDeviceProtectedPreferences(appCtx).getLong("last_activity_timestamp", 0L))
    }

    @Test fun `MY_PACKAGE_REPLACED reschedule does not count as activity`() {
        val dueAt = System.currentTimeMillis() + 30 * 60_000L
        getDeviceProtectedPreferences(appCtx).edit()
            .putLong("NextAlarmTimestamp", dueAt)
            .putLong("last_activity_timestamp", 0L)
            .commit()

        BootBroadcastReceiver().onReceive(
            appCtx, Intent("android.intent.action.MY_PACKAGE_REPLACED"))

        verify(exactly = 1) { setAlarm(any(), dueAt, 0, "periodic", null) }
        assertEquals(0L, getDeviceProtectedPreferences(appCtx).getLong("last_activity_timestamp", 0L))
    }

    @Test fun `LOCKED_BOOT_COMPLETED when user is unlocked is skipped`() {
        // During an app redeploy/update, both LOCKED_BOOT_COMPLETED and
        // BOOT_COMPLETED fire while the device is already unlocked. The
        // BOOT_COMPLETED handler will take care of things.
        every { isUserUnlocked(any()) } returns true

        BootBroadcastReceiver().onReceive(
            appCtx, Intent("android.intent.action.LOCKED_BOOT_COMPLETED"))

        verify(exactly = 0) { doAlertCheck(any<Context>(), any()) }
    }

    @Test fun `LOCKED_BOOT_COMPLETED while still locked runs doAlertCheck with saved stage`() {
        every { isUserUnlocked(any()) } returns false
        getDeviceProtectedPreferences(appCtx).edit()
            .putString("last_alarm_stage", "periodic")
            .commit()

        BootBroadcastReceiver().onReceive(
            appCtx, Intent("android.intent.action.LOCKED_BOOT_COMPLETED"))

        verify(exactly = 1) { doAlertCheck(any<Context>(), "periodic") }
    }

    // "alert_sent" is written by dispatchFinalAlert when Auto-Restart Monitoring
    // is off. Restoring a periodic cycle from it would silently re-arm
    // monitoring after a reboot and cause false alerts (issue #181).

    @Test fun `BOOT_COMPLETED with alert_sent stage stays disarmed`() {
        // The Direct Boot pending flag was already cleared when the alert
        // fired, so this BOOT_COMPLETED (delivered at first unlock) falls
        // through to the stage restore — which must not re-arm.
        getDeviceProtectedPreferences(appCtx).edit()
            .putBoolean("direct_boot_notification_pending", false)
            .putString("last_alarm_stage", "alert_sent")
            .commit()

        BootBroadcastReceiver().onReceive(appCtx, Intent(Intent.ACTION_BOOT_COMPLETED))

        verify(exactly = 0) { doAlertCheck(any<Context>(), any()) }
        verify(exactly = 0) { AcknowledgeAreYouThere.acknowledge(any()) }
    }

    @Test fun `LOCKED_BOOT_COMPLETED with alert_sent stage stays disarmed`() {
        // A later reboot with no interaction in between: the single
        // LOCKED_BOOT_COMPLETED must not start a fresh cycle either.
        every { isUserUnlocked(any()) } returns false
        getDeviceProtectedPreferences(appCtx).edit()
            .putString("last_alarm_stage", "alert_sent")
            .commit()

        BootBroadcastReceiver().onReceive(
            appCtx, Intent("android.intent.action.LOCKED_BOOT_COMPLETED"))

        verify(exactly = 0) { doAlertCheck(any<Context>(), any()) }
        verify(exactly = 0) { AcknowledgeAreYouThere.acknowledge(any()) }
    }
}
