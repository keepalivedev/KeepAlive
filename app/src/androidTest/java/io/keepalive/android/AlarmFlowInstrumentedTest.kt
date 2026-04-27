package io.keepalive.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import io.keepalive.android.AlertFlowTestUtil.FAKE_CONTACT_A
import io.keepalive.android.AlertFlowTestUtil.fireAlarm
import io.keepalive.android.AlertFlowTestUtil.hasNotification
import io.keepalive.android.AlertFlowTestUtil.hasPendingKeepAliveAlarm
import io.keepalive.android.AlertFlowTestUtil.resetToCleanEnabledState
import io.keepalive.android.AlertFlowTestUtil.savedAlarmStage
import io.keepalive.android.AlertFlowTestUtil.targetContext
import io.keepalive.android.AlertFlowTestUtil.waitUntil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **Instrumented** (real device / emulator) tests for the alert flow.
 *
 * These don't mock out `SmsManager` / `AlarmManager` / `NotificationManager` —
 * they exercise the full stack. The only safety rail is that the fake contact
 * numbers are IANA-reserved for fiction (555-01xx) and will fail to deliver
 * even if the device has a SIM.
 *
 * Run with:
 *   ./gradlew connectedGooglePlayDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=io.keepalive.android.AlarmFlowInstrumentedTest
 *
 * Requires: device or emulator connected, network permissions granted
 * (handled by [TestSetupUtil.setupTestEnvironment]).
 */
@RunWith(AndroidJUnit4::class)
// Many assertions read the active notification list via
// NotificationManager.getActiveNotifications() — which was added in API 23.
// Pre-M (API 22) the test runner skips this class entirely.
@SdkSuppress(minSdkVersion = android.os.Build.VERSION_CODES.M)
class AlarmFlowInstrumentedTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun grantAllPermissions() {
            TestSetupUtil.setupTestEnvironment()
        }
    }

    @Before fun setUp() {
        // Ensure no AlertService from a prior test is still running and
        // tailing writes that would race with ours.
        targetContext.stopService(android.content.Intent(
            targetContext, AlertService::class.java))
        Thread.sleep(300)
        resetToCleanEnabledState()
    }

    @After fun tearDown() {
        AlertFlowTestUtil.cancelAnyPendingAlarms()
        AlertFlowTestUtil.cancelAllNotifications()
        targetContext.stopService(android.content.Intent(
            targetContext, AlertService::class.java))
        // finalAlarmWithNoActivityStartsAlertService drives the real call
        // step against the fake +15550102 number — hang up so the dialer
        // doesn't stay foreground after the test process exits.
        AlertFlowTestUtil.endAnyActiveCall()
    }

    // ---- The core "no recent activity → Are you there? → final" flow ------

    @Test fun periodicAlarmWithNoActivityPostsAreYouThereAndSchedulesFinal() {
        // Fire the periodic alarm as AlarmManager would.
        fireAlarm("periodic")

        // The decision engine is synchronous up to the notification post.
        // Give the system a beat to actually surface the notification.
        assertTrue("Are-you-there notification should appear",
            waitUntil { hasNotification(AppController.ARE_YOU_THERE_NOTIFICATION_ID) })

        // And the final alarm must be scheduled.
        assertTrue("final alarm must be scheduled", hasPendingKeepAliveAlarm())
        assertEquals("stage saved for Direct Boot recovery",
            "final", savedAlarmStage())
    }

    @Test fun finalAlarmWithNoActivityStartsAlertService() {
        // Set up as if the "Are you there?" already happened and the followup
        // window elapsed without user activity.
        fireAlarm("periodic")
        waitUntil { hasNotification(AppController.ARE_YOU_THERE_NOTIFICATION_ID) }

        // Now simulate the final alarm firing, with no activity in between.
        // dispatchFinalAlert calls startForegroundService(AlertService) which
        // contractually obligates the OS to expect Service.startForeground()
        // within ~5 seconds. We assert via the side effect that
        // dispatchFinalAlert writes AFTER attempting to start the service:
        //   - last_alarm_stage gets reset to "periodic"
        // (the full AlertService internals are covered by
        // AlertServiceInstrumentedTest, which drives the service directly
        // from the instrumentation context).
        fireAlarm("final")

        assertTrue(
            "dispatchFinalAlert must reset last_alarm_stage to 'periodic' " +
                    "after attempting to start AlertService",
            waitUntil(timeoutMs = 5_000L) {
                AlertFlowTestUtil.savedAlarmStage() == "periodic"
            }
        )

        // CRITICAL teardown: AlertService was just queued to start as a
        // foreground service. If the test process exits while the service is
        // still in its "waiting for startForeground()" 5s window, Android
        // delivers ForegroundServiceDidNotStartInTimeException to the NEXT
        // instrumentation invocation — taking down whichever test runs after
        // this one. Wait for AlertService to honor the contract, then stop
        // it cleanly. waitForIdleSync gives onStartCommand a chance to run.
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .waitForIdleSync()
        Thread.sleep(1_000)
        targetContext.stopService(
            android.content.Intent(targetContext, AlertService::class.java)
        )
        Thread.sleep(500)
    }

    @Test fun disabledAppDoesNothingOnAlarm() {
        // Disable the app but leave prefs otherwise intact.
        getEncryptedSharedPreferences(targetContext).edit()
            .putBoolean("enabled", false).commit()

        fireAlarm("periodic")
        Thread.sleep(500)

        assertFalse("no notification should appear when app is disabled",
            hasNotification(AppController.ARE_YOU_THERE_NOTIFICATION_ID))
    }

    @Test fun staleFinalAlarmDowngradesToPeriodic() {
        // A final alarm that fired much later than scheduled (e.g., device
        // was off) should be treated as periodic — we prompt the user again
        // instead of immediately sending the alert.
        val longAgo = System.currentTimeMillis() - 24L * 60 * 60 * 1000

        fireAlarm("final", alarmTimestamp = longAgo)

        // Downgrade means doAlertCheck("periodic") ran. Most reliable
        // evidence: a NEW final alarm was scheduled (the saved stage flips
        // to "final" because periodic-due → schedule final). Asserting on
        // notification visibility is flaky on a busy emulator — the system
        // can take seconds to surface a post.
        assertTrue("downgrade should schedule a fresh final alarm",
            waitUntil(timeoutMs = 10_000L) { savedAlarmStage() == "final" })
        // Confirm the AlertService wasn't started — a true "final" stage
        // would have led to dispatching the real alert.
        assertFalse("must NOT have started AlertService",
            hasNotification(AppController.ALERT_SERVICE_NOTIFICATION_ID))
    }

    // ---- Prefs integration -----------------------------------------------

    @Test fun deviceProtectedPrefsAreUpdatedOnEveryAlarmCycle() {
        val beforeStamp = System.currentTimeMillis()
        Thread.sleep(5)

        fireAlarm("periodic")
        waitUntil { hasNotification(AppController.ARE_YOU_THERE_NOTIFICATION_ID) }

        val devPrefs = getDeviceProtectedPreferences(targetContext)
        val savedCheck = devPrefs.getLong("last_check_timestamp", 0L)
        assertTrue("last_check_timestamp should be written on each cycle, got $savedCheck",
            savedCheck >= beforeStamp)
    }

    @Test fun seedingAnEnabledContactMakesItTheAlertTarget() {
        // Sanity check: the fake contact we seeded is what the alert code reads.
        val contacts: MutableList<SMSEmergencyContactSetting> = loadJSONSharedPreference(
            getEncryptedSharedPreferences(targetContext), "PHONE_NUMBER_SETTINGS"
        )
        assertEquals(1, contacts.size)
        assertEquals(FAKE_CONTACT_A, contacts[0].phoneNumber)
        assertEquals(true, contacts[0].isEnabled)
    }
}
