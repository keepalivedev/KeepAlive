package io.keepalive.android

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.keepalive.android.AlertFlowTestUtil.hasNotification
import io.keepalive.android.AlertFlowTestUtil.hasPendingKeepAliveAlarm
import io.keepalive.android.AlertFlowTestUtil.resetToCleanEnabledState
import io.keepalive.android.AlertFlowTestUtil.targetContext
import io.keepalive.android.AlertFlowTestUtil.waitUntil
import io.keepalive.android.receivers.BootBroadcastReceiver
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for [BootBroadcastReceiver]. Simulates boot intents to
 * verify the receiver restores alarms and handles Direct Boot acknowledgement
 * correctly. True reboot behavior can only be tested manually; this covers
 * the receiver logic that runs on every real reboot.
 */
@RunWith(AndroidJUnit4::class)
class BootFlowInstrumentedTest {

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

    private fun deliverBoot(action: String) {
        val intent = Intent(action)
        BootBroadcastReceiver().onReceive(targetContext, intent)
    }

    @Test fun bootCompletedWithNoPendingFlagRestoresAlarm() {
        // Simulate the state right after a reboot where the app was running
        // normally: no Direct Boot prompt was pending.
        getDeviceProtectedPreferences(targetContext).edit()
            .putBoolean("direct_boot_notification_pending", false)
            .putString("last_alarm_stage", "periodic")
            .commit()

        deliverBoot(Intent.ACTION_BOOT_COMPLETED)

        // doAlertCheck ran with stage=periodic → schedules a new alarm.
        // Either way, app should have an alarm scheduled within a short window.
        assertTrue("receiver should schedule an alarm after boot",
            waitUntil { hasPendingKeepAliveAlarm() })
    }

    @Test fun bootCompletedWithPendingDirectBootFlagAcknowledges() {
        // Simulate: device rebooted while "Are you there?" was pending, then
        // the user unlocked → BOOT_COMPLETED fires. Unlock is proof of
        // activity. We must NOT re-alert; we must clear the pending flag.
        val devPrefs = getDeviceProtectedPreferences(targetContext)
        devPrefs.edit()
            .putBoolean("direct_boot_notification_pending", true)
            .putString("last_alarm_stage", "final")
            .commit()

        // Seed an "Are you there?" notification so we can assert it's cancelled.
        AlertNotificationHelper(targetContext).sendNotification(
            "Are you there?", "Please respond", AppController.ARE_YOU_THERE_NOTIFICATION_ID
        )
        // Real NotificationManager has a small delay before active-notifications
        // reflects a post. Poll.
        assertTrue("seeded notification should be visible",
            waitUntil { hasNotification(AppController.ARE_YOU_THERE_NOTIFICATION_ID) })

        deliverBoot(Intent.ACTION_BOOT_COMPLETED)

        assertTrue("pending flag should be cleared",
            waitUntil { !devPrefs.getBoolean("direct_boot_notification_pending", true) })
        assertTrue("are-you-there notification should be cancelled by acknowledge",
            waitUntil { !hasNotification(AppController.ARE_YOU_THERE_NOTIFICATION_ID) })
    }

    @Test fun lockedBootCompletedWithUnlockedUserIsSkipped() {
        // On an app-redeploy, both LOCKED_BOOT_COMPLETED and BOOT_COMPLETED
        // fire even though the user is already unlocked. The locked handler
        // must bail so we don't double-run doAlertCheck.
        // This test can only observe behavior — it doesn't flip the unlock
        // state (impossible without a real reboot). It just confirms the
        // receiver doesn't crash on a spurious LOCKED intent.
        deliverBoot("android.intent.action.LOCKED_BOOT_COMPLETED")
        // No assertion — this is a "doesn't-blow-up" test; pairs with the
        // unit-test coverage which asserts the branching logic.
    }

    @Test fun disabledAppIgnoresBootIntents() {
        getEncryptedSharedPreferences(targetContext).edit()
            .putBoolean("enabled", false).commit()

        deliverBoot(Intent.ACTION_BOOT_COMPLETED)
        Thread.sleep(500)

        assertFalse("no alarm should be scheduled when the app is disabled",
            hasPendingKeepAliveAlarm())
    }
}
