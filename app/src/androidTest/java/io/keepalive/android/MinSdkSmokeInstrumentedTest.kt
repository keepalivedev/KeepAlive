package io.keepalive.android

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.keepalive.android.receivers.BootBroadcastReceiver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Behavioral smoke tests that work on **every API the app supports** —
 * including the minSdk floor of API 22.
 *
 * Why a separate class instead of relaxing `@SdkSuppress` on the existing
 * ones?  The other instrumented tests assert on
 * `NotificationManager.getActiveNotifications()` (added in API 23) and on
 * device-protected-storage state (added in API 24). This class deliberately
 * limits its assertions to side effects that are observable on every API:
 *  - SharedPreferences writes (`NextAlarmTimestamp`, `LastAlertAt`, etc.)
 *  - The implicit AlarmManager state inferred from `NextAlarmTimestamp`
 *  - The values setAlarm bakes into prefs
 *
 * That gives us behavioral coverage on API 22 (~5–10% of features —
 * scheduling and gating, not the notification half) where otherwise the
 * matrix only verifies that the APK installs.
 */
@RunWith(AndroidJUnit4::class)
class MinSdkSmokeInstrumentedTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun grantAllPermissions() {
            TestSetupUtil.setupTestEnvironment()
        }
    }

    private val ctx: Context = AlertFlowTestUtil.targetContext
    private val prefs get() = getEncryptedSharedPreferences(ctx)

    @Before fun setUp() {
        AlertFlowTestUtil.resetToCleanEnabledState()
        // Make sure NextAlarmTimestamp is unset so each test's assertion has
        // an unambiguous baseline.
        prefs.edit().remove("NextAlarmTimestamp").commit()
    }

    @After fun tearDown() {
        AlertFlowTestUtil.cancelAnyPendingAlarms()
        AlertFlowTestUtil.cancelAllNotifications()
        // finalAlarmWithNoActivityWritesLastAlertAt drives AlertService all
        // the way through the call step; if we don't hang up the dialer
        // stays foreground after the test process is torn down.
        AlertFlowTestUtil.endAnyActiveCall()
    }

    // ---- setAlarm primitive ------------------------------------------------

    @Test fun setAlarmWritesNextAlarmTimestampInTheFuture() {
        val before = System.currentTimeMillis()
        setAlarm(ctx, before, desiredAlarmInMinutes = 30, alarmStage = "periodic",
            restPeriods = null)

        val saved = prefs.getLong("NextAlarmTimestamp", 0)
        assertTrue("NextAlarmTimestamp should be set in the future; saved=$saved",
            saved > before)
        // ~30 min out — allow a wide window for slow emulators
        val msFromBefore = saved - before
        assertTrue(
            "expected ~30 min from now, got msFromBefore=$msFromBefore",
            msFromBefore in 25L * 60_000..35L * 60_000
        )
    }

    @Test fun setAlarmAgainOverwritesThePreviousNextAlarmTimestamp() {
        val now = System.currentTimeMillis()
        setAlarm(ctx, now, 5, "periodic", null)
        val first = prefs.getLong("NextAlarmTimestamp", 0)
        Thread.sleep(50)
        setAlarm(ctx, now, 60, "periodic", null)
        val second = prefs.getLong("NextAlarmTimestamp", 0)
        assertTrue(
            "second setAlarm should produce a later timestamp; first=$first second=$second",
            second > first
        )
    }

    // ---- AlarmReceiver / doAlertCheck ----- via AlertFlowTestUtil.fireAlarm

    @Test fun firingPeriodicAlarmWithNoActivitySchedulesAFollowupNearTheFollowupPeriod() {
        // No activity (fake monitored package never runs) → doAlertCheck
        // posts the prompt and schedules a final alarm at now + followup_minutes.
        // followup_time_period_minutes = 60 in the default reset state.
        val before = System.currentTimeMillis()
        AlertFlowTestUtil.fireAlarm("periodic")

        val saved = prefs.getLong("NextAlarmTimestamp", 0)
        val msFromBefore = saved - before
        assertTrue(
            "expected a final-followup alarm ~60 min out; saved=$saved msFromBefore=$msFromBefore",
            msFromBefore in 50L * 60_000..70L * 60_000
        )
    }

    @Test fun firingPeriodicAlarmWhenAppDisabledSchedulesNothing() {
        // Disable the app and verify the receiver short-circuits cleanly.
        prefs.edit().putBoolean("enabled", false).commit()

        AlertFlowTestUtil.fireAlarm("periodic")

        val saved = prefs.getLong("NextAlarmTimestamp", -1L)
        assertEquals(
            "disabled app must not schedule a follow-up alarm",
            -1L, saved
        )
    }

    @Test fun staleFinalAlarmDowngradesToPeriodicSchedulesAFollowup() {
        // A "final" alarm fired hours after it was scheduled is treated as
        // periodic — the user gets prompted again instead of an immediate alert.
        // Observable via NextAlarmTimestamp landing in the followup window
        // (i.e. final-alarm scheduled), not the much-larger checkPeriod window
        // (which would mean a full reschedule with no prompt).
        val before = System.currentTimeMillis()
        val longAgo = before - 24L * 60 * 60 * 1000

        AlertFlowTestUtil.fireAlarm("final", alarmTimestamp = longAgo)

        val saved = prefs.getLong("NextAlarmTimestamp", 0)
        val msFromBefore = saved - before
        assertTrue(
            "stale final → downgraded → final-followup ~60min out; got $msFromBefore",
            msFromBefore in 50L * 60_000..70L * 60_000
        )
    }

    // ---- BootBroadcastReceiver --------------------------------------------

    @Test fun bootCompletedWhenEnabledSchedulesAnAlarm() {
        val before = System.currentTimeMillis()
        BootBroadcastReceiver().onReceive(ctx, Intent(Intent.ACTION_BOOT_COMPLETED))

        val saved = prefs.getLong("NextAlarmTimestamp", 0)
        assertTrue(
            "BOOT_COMPLETED + enabled=true should schedule an alarm; saved=$saved",
            saved > before
        )
    }

    @Test fun bootCompletedWhenDisabledSchedulesNothing() {
        prefs.edit().putBoolean("enabled", false).commit()

        BootBroadcastReceiver().onReceive(ctx, Intent(Intent.ACTION_BOOT_COMPLETED))

        val saved = prefs.getLong("NextAlarmTimestamp", -1L)
        assertEquals(-1L, saved)
    }

    @Test fun unrelatedBroadcastIsIgnored() {
        BootBroadcastReceiver().onReceive(ctx, Intent("io.keepalive.unrelated.action"))

        val saved = prefs.getLong("NextAlarmTimestamp", -1L)
        assertEquals("non-boot intents must not schedule an alarm", -1L, saved)
    }

    // ---- AcknowledgeAreYouThere -------------------------------------------

    @Test fun acknowledgeReschedulesAroundTheCheckPeriod() {
        // Drive into "prompt active" state — final alarm scheduled ~60min out.
        AlertFlowTestUtil.fireAlarm("periodic")
        val finalAlarmAt = prefs.getLong("NextAlarmTimestamp", 0)
        assertTrue("final alarm should be set", finalAlarmAt > 0)

        // Ack — should rebase to a 12h periodic schedule (default check period).
        val before = System.currentTimeMillis()
        AcknowledgeAreYouThere.acknowledge(ctx)

        val periodicAt = prefs.getLong("NextAlarmTimestamp", 0)
        val msFromBefore = periodicAt - before
        assertTrue(
            "ack should schedule a 12h periodic alarm; got msFromBefore=$msFromBefore",
            msFromBefore in 11L * 60 * 60 * 1000..13L * 60 * 60 * 1000
        )
    }

    @Test fun acknowledgeFromNoActivePromptStillSchedulesPeriodic() {
        val before = System.currentTimeMillis()
        AcknowledgeAreYouThere.acknowledge(ctx)

        val saved = prefs.getLong("NextAlarmTimestamp", 0)
        val msFromBefore = saved - before
        assertTrue(
            "ack with no active prompt must still schedule a periodic alarm",
            msFromBefore > 60L * 60 * 1000
        )
    }

    // ---- LastAlertAt persistence (final alarm with no activity) -----------

    @Test fun finalAlarmWithNoActivityWritesLastAlertAt() {
        // Direct path: "final" stage + no activity → dispatchFinalAlert →
        // AlertService.sendAlert sets LastAlertAt. We can't use Robolectric's
        // service controller here, but we CAN observe whether the pref got
        // written within a reasonable window.
        prefs.edit().remove("LastAlertAt").commit()
        val before = System.currentTimeMillis()

        AlertFlowTestUtil.fireAlarm("final")

        // The AlertService runs on a background thread; poll briefly.
        val ok = AlertFlowTestUtil.waitUntil(timeoutMs = 10_000L) {
            prefs.getLong("LastAlertAt", 0) >= before
        }
        assertTrue(
            "final alarm with no activity should record LastAlertAt eventually",
            ok
        )
    }
}
