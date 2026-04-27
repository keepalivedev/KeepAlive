package io.keepalive.android

import io.keepalive.android.testing.FakeAlertCheckDeps
import io.keepalive.android.testing.fakeUsageEvent
import io.keepalive.android.testing.hours
import io.keepalive.android.testing.minutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for the [doAlertCheck] state machine. All side effects are captured
 * by [FakeAlertCheckDeps] — no AlarmManager / UsageStatsManager / Context are
 * actually hit. Runs under Robolectric only because the engine routes some
 * logging through `android.util.Log`, whose plain unit-test stub doesn't
 * include the 3-arg overload. Robolectric provides the real Log shadow.
 */
@RunWith(RobolectricTestRunner::class)
class DoAlertCheckTest {

    private lateinit var deps: FakeAlertCheckDeps

    // Defaults: 12h check period, 60-min followup. Matches production defaults.
    @Before fun setUp() {
        deps = FakeAlertCheckDeps()
        deps.credPrefs.edit()
            .putString("time_period_hours", "12")
            .putString("followup_time_period_minutes", "60")
            .apply()
    }

    // =====================================================================
    // Direct Boot branch (user locked)
    // =====================================================================

    @Test fun `direct boot with no saved alarm timestamp schedules periodic from now`() {
        deps.userUnlockedValue = false
        deps.nowValue = hours(5)
        // devPrefs has no NextAlarmTimestamp

        doAlertCheck(deps, "periodic")

        assertEquals(1, deps.scheduledAlarms.size)
        val scheduled = deps.scheduledAlarms[0]
        assertEquals(hours(5), scheduled.baseTimestamp)
        assertEquals(12 * 60, scheduled.periodMinutes)
        assertEquals("periodic", scheduled.stage)
        // No user-facing side effects
        assertEquals(0, deps.notificationShowCount)
        assertEquals(0, deps.finalAlertCalls.size)
    }

    @Test fun `direct boot periodic alarm not yet due reschedules`() {
        deps.userUnlockedValue = false
        deps.nowValue = hours(10)
        deps.devPrefs.edit().putLong("NextAlarmTimestamp", hours(12)).apply()

        doAlertCheck(deps, "periodic")

        assertEquals(1, deps.scheduledAlarms.size)
        val scheduled = deps.scheduledAlarms[0]
        // Reconstructed base time = savedAlarm - periodMinutes*60*1000
        assertEquals(hours(12) - 12 * 60 * 60 * 1000L, scheduled.baseTimestamp)
        assertEquals("periodic", scheduled.stage)
        // restPeriods passed as null to avoid re-adjusting
        assertNull(scheduled.restPeriods)
    }

    @Test fun `direct boot final alarm not yet due reschedules with followup period`() {
        deps.userUnlockedValue = false
        deps.nowValue = hours(12) + minutes(30)
        deps.devPrefs.edit().putLong("NextAlarmTimestamp", hours(13)).apply()

        doAlertCheck(deps, "final")

        assertEquals(1, deps.scheduledAlarms.size)
        val scheduled = deps.scheduledAlarms[0]
        assertEquals("final", scheduled.stage)
        assertEquals(60, scheduled.periodMinutes)
        // No alert yet — just a reschedule
        assertEquals(0, deps.finalAlertCalls.size)
    }

    @Test fun `direct boot final alarm due with no activity dispatches final alert`() {
        deps.userUnlockedValue = false
        deps.nowValue = hours(13)
        deps.devPrefs.edit()
            .putLong("NextAlarmTimestamp", hours(13))
            // last_activity_timestamp: older than 'are you there' posted-at (hours(12))
            .putLong("last_activity_timestamp", hours(5))
            .putBoolean("direct_boot_notification_pending", true)
            .apply()

        doAlertCheck(deps, "final")

        assertEquals(1, deps.finalAlertCalls.size)
        // The flag should be cleared so a subsequent reboot doesn't re-alert
        assertFalse(deps.devPrefs.getBoolean("direct_boot_notification_pending", true))
    }

    @Test fun `direct boot final alarm skipped when activity recorded after prompt`() {
        // This is the race case #4 we fixed: user unlocked (or tapped) at hours(12)+30min,
        // then final alarm fires a moment later. last_activity_timestamp > are-you-there
        // posted-at means we should acknowledge, not alert.
        deps.userUnlockedValue = false
        deps.nowValue = hours(13)
        deps.devPrefs.edit()
            .putLong("NextAlarmTimestamp", hours(13))
            .putLong("last_activity_timestamp", hours(12) + minutes(30))
            .putBoolean("direct_boot_notification_pending", true)
            .apply()

        doAlertCheck(deps, "final")

        assertEquals("should not dispatch the final alert", 0, deps.finalAlertCalls.size)
        assertEquals("should acknowledge instead", 1, deps.acknowledgeCount)
    }

    @Test fun `direct boot final alarm with activity exactly at prompt is treated as acknowledged`() {
        // Boundary: equals is considered "acknowledged" (>=).
        deps.userUnlockedValue = false
        deps.nowValue = hours(13)
        deps.devPrefs.edit()
            .putLong("NextAlarmTimestamp", hours(13))
            .putLong("last_activity_timestamp", hours(12)) // exactly at prompt time
            .apply()

        doAlertCheck(deps, "final")

        assertEquals(0, deps.finalAlertCalls.size)
        assertEquals(1, deps.acknowledgeCount)
    }

    @Test fun `direct boot periodic due shows notification and schedules final alarm`() {
        deps.userUnlockedValue = false
        deps.nowValue = hours(12)
        deps.devPrefs.edit().putLong("NextAlarmTimestamp", hours(12)).apply()

        doAlertCheck(deps, "periodic")

        assertEquals(1, deps.notificationShowCount)
        assertEquals(60, deps.notificationLastFollowupMinutes)
        // Final alarm scheduled
        assertEquals(1, deps.scheduledAlarms.size)
        assertEquals("final", deps.scheduledAlarms[0].stage)
        // Direct-boot pending flag set so BOOT_COMPLETED knows to ack on unlock
        assertTrue(deps.devPrefs.getBoolean("direct_boot_notification_pending", false))
        // No overlay during Direct Boot (service not directBootAware, screen not visible)
        assertEquals(0, deps.overlayShowCount)
    }

    // =====================================================================
    // Unlocked branch
    // =====================================================================

    @Test fun `unlocked with no recent activity sends prompt and schedules final`() {
        deps.userUnlockedValue = true
        deps.nowValue = hours(24) // well past a check period
        deps.lastActivity = null

        doAlertCheck(deps, "periodic")

        assertEquals(1, deps.notificationShowCount)
        assertEquals(1, deps.overlayShowCount)
        assertEquals(60, deps.overlayLastFollowupMinutes)
        assertEquals(1, deps.scheduledAlarms.size)
        assertEquals("final", deps.scheduledAlarms[0].stage)
        assertEquals(60, deps.scheduledAlarms[0].periodMinutes)
        // No final alert yet
        assertEquals(0, deps.finalAlertCalls.size)
    }

    @Test fun `unlocked with recent activity reschedules periodic`() {
        deps.userUnlockedValue = true
        deps.nowValue = hours(24)
        deps.lastActivity = fakeUsageEvent(timeStamp = hours(23))

        doAlertCheck(deps, "periodic")

        assertEquals(0, deps.notificationShowCount)
        assertEquals(0, deps.overlayShowCount)
        assertEquals(1, deps.scheduledAlarms.size)
        val scheduled = deps.scheduledAlarms[0]
        assertEquals("periodic", scheduled.stage)
        // Base is the last-activity timestamp so the next alarm fires checkPeriod later
        assertEquals(hours(23), scheduled.baseTimestamp)
        // last_activity_timestamp written to device-protected storage for Direct Boot recovery
        assertEquals(hours(23), deps.devPrefs.getLong("last_activity_timestamp", -1L))
    }

    @Test fun `unlocked final alarm with no activity dispatches final alert`() {
        deps.userUnlockedValue = true
        deps.nowValue = hours(13)
        deps.lastActivity = null

        doAlertCheck(deps, "final")

        assertEquals(1, deps.finalAlertCalls.size)
        // Should NOT also schedule a prompt notification
        assertEquals(0, deps.notificationShowCount)
    }

    @Test fun `unlocked final alarm with recent activity reschedules periodic and skips alert`() {
        deps.userUnlockedValue = true
        deps.nowValue = hours(13)
        deps.lastActivity = fakeUsageEvent(timeStamp = hours(12) + minutes(30))

        doAlertCheck(deps, "final")

        assertEquals("should not dispatch alert when user returned", 0, deps.finalAlertCalls.size)
        assertEquals(1, deps.scheduledAlarms.size)
        assertEquals("periodic", deps.scheduledAlarms[0].stage)
    }

    @Test fun `unlocked every check writes last_check_timestamp to device prefs`() {
        deps.userUnlockedValue = true
        deps.nowValue = hours(24)

        doAlertCheck(deps, "periodic")

        assertEquals(hours(24), deps.devPrefs.getLong("last_check_timestamp", -1L))
    }

    @Test fun `unlocked without activity does not write last_activity_timestamp`() {
        // last_activity_timestamp is only written when lastInteractiveEvent != null.
        // An old value from a prior cycle should persist untouched.
        deps.userUnlockedValue = true
        deps.nowValue = hours(24)
        deps.devPrefs.edit().putLong("last_activity_timestamp", hours(10)).apply()
        deps.lastActivity = null

        doAlertCheck(deps, "periodic")

        assertEquals("must not overwrite stale activity", hours(10),
            deps.devPrefs.getLong("last_activity_timestamp", -1L))
    }

    @Test fun `unlocked query uses the monitored app packages from prefs`() {
        deps.userUnlockedValue = true
        deps.nowValue = hours(24)
        // APPS_TO_MONITOR is a JSON list
        val appsJson = """[
            {"packageName":"com.whatsapp","appName":"WhatsApp","lastUsed":0,"className":""},
            {"packageName":"com.google.android.gm","appName":"Gmail","lastUsed":0,"className":""}
        ]""".trimIndent()
        deps.credPrefs.edit().putString("APPS_TO_MONITOR", appsJson).apply()

        doAlertCheck(deps, "periodic")

        assertEquals(1, deps.activityQueries.size)
        val (_, apps) = deps.activityQueries[0]
        assertEquals(listOf("com.whatsapp", "com.google.android.gm"), apps)
    }

    @Test fun `unlocked with empty apps-to-monitor falls back to system events`() {
        deps.userUnlockedValue = true
        deps.nowValue = hours(24)
        // No APPS_TO_MONITOR set — engine passes an empty list and DeviceActivityQuery
        // then falls back to "android" keyguard events. The engine itself just passes
        // whatever apps it loaded.
        doAlertCheck(deps, "periodic")

        assertEquals(1, deps.activityQueries.size)
        val (_, apps) = deps.activityQueries[0]
        assertTrue("empty when no monitored apps configured", apps.isEmpty())
    }

    @Test fun `checkPeriodHours below 1 is honored`() {
        // e.g. 0.5 hours (30 minutes). Division happens in floats.
        deps.credPrefs.edit().putString("time_period_hours", "0.5").apply()
        deps.userUnlockedValue = true
        deps.nowValue = hours(2)
        deps.lastActivity = fakeUsageEvent(timeStamp = hours(2) - minutes(10))

        doAlertCheck(deps, "periodic")

        assertEquals(1, deps.scheduledAlarms.size)
        // period = 0.5h * 60 = 30 minutes
        assertEquals(30, deps.scheduledAlarms[0].periodMinutes)
    }
}
