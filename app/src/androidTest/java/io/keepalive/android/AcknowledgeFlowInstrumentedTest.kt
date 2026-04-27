package io.keepalive.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented coverage of [AcknowledgeAreYouThere] — the single code path
 * that runs when the user confirms they're OK (via the notification tap,
 * the overlay button, or BOOT_COMPLETED after Direct Boot).
 */
@RunWith(AndroidJUnit4::class)
// Uses NotificationManager.getActiveNotifications() (API 23+).
@SdkSuppress(minSdkVersion = android.os.Build.VERSION_CODES.M)
class AcknowledgeFlowInstrumentedTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun grantAllPermissions() {
            TestSetupUtil.setupTestEnvironment()
        }
    }

    @Before fun setUp() {
        resetToCleanEnabledState()
    }

    @After fun tearDown() {
        AlertFlowTestUtil.cancelAnyPendingAlarms()
        AlertFlowTestUtil.cancelAllNotifications()
    }

    @Test fun acknowledgingFromTheAreYouThereStateResetsToPeriodic() {
        // Drive the app to a final-alarm-scheduled state.
        fireAlarm("periodic")
        assertTrue("prompt posted",
            waitUntil { hasNotification(AppController.ARE_YOU_THERE_NOTIFICATION_ID) })
        assertEquals("stage should be 'final' while awaiting response",
            "final", savedAlarmStage())

        // User taps I'm OK.
        AcknowledgeAreYouThere.acknowledge(targetContext)

        // Prompt is gone.
        assertTrue("prompt cleared",
            waitUntil { !hasNotification(AppController.ARE_YOU_THERE_NOTIFICATION_ID) })
        // Periodic alarm re-scheduled.
        assertTrue("a fresh periodic alarm should be set",
            hasPendingKeepAliveAlarm())
        assertEquals("stage reset to periodic", "periodic", savedAlarmStage())
    }

    @Test fun acknowledgeRecordsActivityTimestampForDirectBootRace() {
        val before = System.currentTimeMillis()
        Thread.sleep(5)

        AcknowledgeAreYouThere.acknowledge(targetContext)

        val saved = getDeviceProtectedPreferences(targetContext)
            .getLong("last_activity_timestamp", -1L)
        assertTrue("last_activity_timestamp must be recent so the Direct Boot " +
                "final-alarm branch can detect this ack — was $saved, expected >= $before",
            saved >= before)
    }

    @Test fun acknowledgeClearsTheDirectBootPendingFlag() {
        getDeviceProtectedPreferences(targetContext).edit()
            .putBoolean("direct_boot_notification_pending", true)
            .commit()

        AcknowledgeAreYouThere.acknowledge(targetContext)

        assertFalse("flag must be cleared after acknowledge",
            getDeviceProtectedPreferences(targetContext)
                .getBoolean("direct_boot_notification_pending", true))
    }

    @Test fun rapidAcknowledgeIsIdempotent() {
        fireAlarm("periodic")
        waitUntil { hasNotification(AppController.ARE_YOU_THERE_NOTIFICATION_ID) }

        // Simulate a user mashing the button — should still end up in a clean
        // state without alarm stacking or notification re-appearing.
        repeat(5) { AcknowledgeAreYouThere.acknowledge(targetContext) }

        assertFalse(hasNotification(AppController.ARE_YOU_THERE_NOTIFICATION_ID))
        assertTrue("periodic alarm still scheduled", hasPendingKeepAliveAlarm())
    }
}
